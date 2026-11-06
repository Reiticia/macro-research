use std::{
    collections::{BTreeMap, HashMap},
    time::Duration as StdDuration,
};

use chrono::{DateTime, Duration, Utc};

use super::event_watcher::release;
use crate::{
    AppState,
    calendar::{
        parse::{conflicting_duplicate_actual, same_occurrence},
        release_identity::{ambiguous_phase, release_value_for, verified_relation},
    },
    error::AppError,
    model::EconomicEvent,
};

const POLL_SECONDS: u64 = 2;

/// One independent, non-overlapping poller per backend, not per device or event.
/// Missing Actual on an otherwise healthy TradingView feed must not suppress BiQuote.
pub async fn biquote_release_loop(state: AppState) {
    let mut interval = tokio::time::interval(StdDuration::from_secs(POLL_SECONDS));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        interval.tick().await;
        if let Err(error) = poll_once(&state, Utc::now()).await {
            tracing::warn!(%error, "BiQuote release poll failed");
        }
    }
}

async fn poll_once(state: &AppState, now: DateTime<Utc>) -> Result<(), AppError> {
    let timeout = state.config.scheduler.release_timeout_minutes.max(0);
    let candidates = state
        .events
        .biquote_release_candidates(now, timeout, state.config.calendar.minimum_importance)
        .await?;
    let mut groups = BTreeMap::new();
    for event in candidates {
        groups
            .entry(event.event_time)
            .or_insert_with(Vec::new)
            .push(event);
    }
    for (time, candidates) in groups {
        if Utc::now() > time + Duration::minutes(timeout) {
            continue;
        }
        // BiQuote has no occurrence-ID read endpoint. Query just this release instant;
        // simultaneous releases share a request instead of fetching a whole day per event.
        // Live API probes show subsecond bounds are truncated; use a full second and
        // continue checking the exact occurrence time locally.
        let end = time + Duration::seconds(1);
        let started = std::time::Instant::now();
        let remote = match state
            .calendar_service
            .lookup_biquote_release(time, end)
            .await
        {
            Ok(events) => events,
            Err(error) => {
                tracing::warn!(%error, event_time=%time, elapsed_ms=started.elapsed().as_millis(), "BiQuote release lookup failed");
                continue;
            }
        };
        // Include already-valued and legacy duplicate rows when proving uniqueness.
        let local = state.events.events_at_release_time(time).await?;
        let mut matched: HashMap<i64, Vec<&EconomicEvent>> = HashMap::new();
        for source in &remote {
            if source.provider != "biquote"
                || source.actual.is_none()
                || !source.time_exact
                || source.event_time != time
                || conflicting_duplicate_actual(source, &remote)
            {
                continue;
            }
            let owner = state
                .events
                .find_provider_event(&source.provider, &source.provider_id)
                .await?;
            let targets: Vec<_> = local
                .iter()
                .filter(|target| {
                    target.time_exact
                        && target.country == source.country
                        && target.event_time == source.event_time
                        && match verified_relation(target, source) {
                            Some(verified) => verified,
                            None => {
                                same_occurrence(target, source)
                                    && owner.as_ref().is_none_or(|owner| owner.id == target.id)
                            }
                        }
                })
                .collect();
            let mut providers = std::collections::HashSet::new();
            let verified_group = targets
                .iter()
                .all(|target| verified_relation(target, source) == Some(true));
            let unique_providers = targets
                .iter()
                .all(|target| providers.insert(&target.provider));
            if targets.is_empty()
                || !unique_providers
                || ambiguous_phase(source, &local)
                || targets.len() > 1 && !verified_group
            {
                tracing::debug!(provider_id=%source.provider_id, event=%source.event,
                    source_period=?source.release_metadata.period,
                    targets=targets.len(), reason="unmatched_or_ambiguous_identity", "BiQuote release match skipped");
                continue;
            }
            for target in targets {
                matched.entry(target.id).or_default().push(source);
            }
        }
        for expected in candidates {
            let Some(sources) = matched.get(&expected.id) else {
                continue;
            };
            let [source] = sources.as_slice() else {
                tracing::debug!(
                    event_id = expected.id,
                    reason = "multiple_source_occurrences",
                    "BiQuote release match skipped"
                );
                continue;
            };
            if let Err(reason) = release_value_for(&expected, source, &local) {
                tracing::warn!(event_id=expected.id, provider_id=%source.provider_id,
                    ?reason, "BiQuote release match skipped");
                continue;
            }
            // The response may have completed after the window closed or another watcher won.
            if Utc::now() > expected.event_time + Duration::minutes(timeout) {
                continue;
            }
            if state.events.fill_biquote_release(&expected, source).await? {
                tracing::info!(event_id=expected.id, event=%expected.event,
                    provider_id=%source.provider_id, source_period=?source.release_metadata.period,
                    target_period=?expected.release_metadata.period, observed_at=%Utc::now(),
                    release_delay_ms=(Utc::now()-expected.event_time).num_milliseconds(),
                    elapsed_ms=started.elapsed().as_millis(), "BiQuote release value received");
                release(state, &expected).await?;
            }
        }
    }
    Ok(())
}
