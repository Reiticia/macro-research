use std::{collections::HashSet, time::Duration as StdDuration};

use chrono::{Duration, Utc};

use crate::{
    AppState,
    calendar::parse::{conflicting_duplicate_actual, occurrence_key, same_named_occurrence},
    config::{CalendarConfig, SchedulerConfig},
    model::{AppEvent, EconomicEvent, EventStatus},
};

pub async fn event_watch_loop(
    state: AppState,
    calendar: CalendarConfig,
    scheduler: SchedulerConfig,
) {
    let mut interval =
        tokio::time::interval(StdDuration::from_secs(scheduler.watch_scan_seconds.max(5)));
    loop {
        interval.tick().await;
        if let Err(error) = watch_once(&state, &calendar, &scheduler).await {
            tracing::error!(error = %error, "event watcher iteration failed");
        }
    }
}

async fn watch_once(
    state: &AppState,
    calendar: &CalendarConfig,
    scheduler: &SchedulerConfig,
) -> Result<(), crate::error::AppError> {
    let now = Utc::now();
    let candidates = state
        .events
        .scheduled_to_watch(
            now,
            scheduler.watch_before_minutes,
            scheduler.release_timeout_minutes,
            calendar.minimum_importance,
        )
        .await?;
    for event in candidates {
        state
            .events
            .set_status(event.id, EventStatus::Watching)
            .await?;
        tracing::info!(event_id = event.id, event = %event.event, "event entered watching state");
        if let Some(selector) = &state.market_selector {
            let selector = selector.clone();
            let event = event.clone();
            tokio::spawn(async move {
                if let Err(error) = selector.selection_for(&event).await {
                    tracing::warn!(event_id = event.id, %error, "event market selection failed; collector will apply fallback");
                }
            });
        }
        if event.has_release() {
            release(state, &event).await?;
        }
    }

    let watching = state.events.watching().await?;
    let mut active = Vec::with_capacity(watching.len());
    for watched in watching {
        if now > watched.event_time + Duration::minutes(scheduler.release_timeout_minutes) {
            state
                .events
                .set_status(watched.id, EventStatus::Timeout)
                .await?;
            tracing::warn!(event_id = watched.id, "event release timed out");
        } else {
            active.push(watched);
        }
    }
    if active.is_empty() {
        return Ok(());
    }

    let start = active.iter().map(|event| event.event_time).min().unwrap() - Duration::hours(12);
    let end = active.iter().map(|event| event.event_time).max().unwrap() + Duration::hours(12);
    let mut remote = state.calendar_service.lookup(start, end).await?.events;
    // Chinese-name matching uses existing translations only, never a model call on a poll.
    let names: Vec<_> = remote
        .iter()
        .map(|e| e.event.clone())
        .collect::<HashSet<_>>()
        .into_iter()
        .collect();
    for chunk in names.chunks(500) {
        let translations = state.events.cached_event_name_translations(chunk).await?;
        for event in &mut remote {
            if let Some((cn, tw)) = translations.get(&event.event) {
                if event.event_zh_cn.is_none() {
                    event.event_zh_cn = Some(cn.clone());
                }
                if event.event_zh_tw.is_none() {
                    event.event_zh_tw = Some(tw.clone());
                }
            }
        }
    }
    let relevant: Vec<EconomicEvent> = active
        .iter()
        .filter_map(|watched| {
            let exact = remote.iter().find(|event| {
                event.provider == watched.provider && event.provider_id == watched.provider_id
            });
            exact
                .or_else(|| {
                    if active
                        .iter()
                        .filter(|event| same_occurrence(event, watched))
                        .count()
                        != 1
                    {
                        return None;
                    }
                    let mut matching = remote
                        .iter()
                        .filter(|event| same_occurrence(event, watched));
                    let first = matching.next()?;
                    matching.next().is_none().then_some(first)
                })
                .filter(|event| !conflicting_duplicate_actual(event, &remote))
                .cloned()
        })
        .collect();
    let updated_ids: HashSet<_> = state
        .events
        .save_events_without_observations(&relevant)
        .await?
        .into_iter()
        .collect();

    for watched in active {
        if updated_ids.contains(&watched.id) {
            let released = state.events.get(watched.id).await?;
            // Only the row actually updated by the deduplicating repository may advance.
            // A same-name legacy duplicate must not be released with a missing Actual.
            if released.has_release() && released.status == EventStatus::Watching {
                release(state, &released).await?;
            }
        }
    }
    Ok(())
}

fn same_occurrence(left: &EconomicEvent, right: &EconomicEvent) -> bool {
    same_named_occurrence(left, right)
        || (left.time_exact
            && right.time_exact
            && left.provider != right.provider
            && !occurrence_key(&left.country, left.event_time, &left.event)
                .2
                .is_empty()
            && occurrence_key(&left.country, left.event_time, &left.event)
                == occurrence_key(&right.country, right.event_time, &right.event))
}

async fn release(state: &AppState, event: &EconomicEvent) -> Result<(), crate::error::AppError> {
    state
        .events
        .set_status(event.id, EventStatus::Released)
        .await?;
    let _ = state.event_bus.send(AppEvent::EconomicEventReleased {
        event_id: event.id,
        event: event.event.clone(),
        event_zh_cn: event.event_zh_cn.clone(),
        event_zh_tw: event.event_zh_tw.clone(),
        actual: event.actual.map(|value| value.to_string()),
        consensus: event.consensus.map(|value| value.to_string()),
    });
    if let Some(fcm) = &state.fcm {
        let fcm = fcm.clone();
        let event = event.clone();
        tokio::spawn(async move {
            if let Err(error) = fcm.send_release(&event).await {
                tracing::warn!(event_id = event.id, %error, "FCM release notification failed");
            }
        });
    }
    tracing::info!(event_id = event.id, event = %event.event, "economic event released");
    Ok(())
}
