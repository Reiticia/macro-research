use std::{
    collections::{BTreeMap, HashMap},
    time::Duration as StdDuration,
};

use chrono::{DateTime, Duration, Utc};

use super::event_watcher::{release, same_occurrence};
use crate::{
    AppState, calendar::parse::conflicting_duplicate_actual, error::AppError, model::EconomicEvent,
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
        let mut remote = match state
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
        let names: Vec<_> = remote.iter().map(|event| event.event.clone()).collect();
        let translations = state.events.cached_event_name_translations(&names).await?;
        for event in &mut remote {
            if let Some((cn, tw)) = translations.get(&event.event) {
                event.event_zh_cn = event.event_zh_cn.clone().or_else(|| Some(cn.clone()));
                event.event_zh_tw = event.event_zh_tw.clone().or_else(|| Some(tw.clone()));
            }
        }
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
                        && match &owner {
                            Some(owner) => owner.id == target.id,
                            None => same_occurrence(target, source),
                        }
                })
                .collect();
            if let [target] = targets.as_slice() {
                matched.entry(target.id).or_default().push(source);
            }
        }
        for expected in candidates {
            let Some(sources) = matched.get(&expected.id) else {
                continue;
            };
            let [source] = sources.as_slice() else {
                continue;
            };
            // The response may have completed after the window closed or another watcher won.
            if Utc::now() > expected.event_time + Duration::minutes(timeout) {
                continue;
            }
            if state.events.fill_biquote_release(&expected, source).await? {
                tracing::info!(event_id=expected.id, event=%expected.event,
                    provider_id=%source.provider_id, observed_at=%Utc::now(),
                    release_delay_ms=(Utc::now()-expected.event_time).num_milliseconds(),
                    elapsed_ms=started.elapsed().as_millis(), "BiQuote release value received");
                release(state, &expected).await?;
            }
        }
    }
    Ok(())
}
