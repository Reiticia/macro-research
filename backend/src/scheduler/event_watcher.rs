use std::{collections::HashSet, time::Duration as StdDuration};

use chrono::{Duration, Utc};

use crate::{
    AppState,
    calendar::parse::{conflicting_duplicate_actual, same_occurrence},
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
        if !state
            .events
            .transition_status(event.id, EventStatus::Scheduled, EventStatus::Watching)
            .await?
        {
            continue;
        }
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
            if state
                .events
                .transition_status(watched.id, EventStatus::Watching, EventStatus::Timeout)
                .await?
            {
                tracing::warn!(event_id = watched.id, "event release timed out");
            }
        } else {
            active.push(watched);
        }
    }
    if active.is_empty() {
        return Ok(());
    }

    let start = active.iter().map(|event| event.event_time).min().unwrap() - Duration::hours(12);
    let end = active.iter().map(|event| event.event_time).max().unwrap() + Duration::hours(12);
    // Identity never depends on translated display labels. Ingest still enriches saved labels.
    let remote = state.calendar_service.lookup(start, end).await?.events;
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

pub(super) async fn release(
    state: &AppState,
    event: &EconomicEvent,
) -> Result<(), crate::error::AppError> {
    if !state.events.claim_release(event.id).await? {
        return Ok(());
    }
    let event = state.events.get(event.id).await?;
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
