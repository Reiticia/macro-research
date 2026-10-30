use std::time::Duration as StdDuration;

use chrono::{Duration, Utc};

use crate::{
    AppState,
    calendar::parse::occurrence_key,
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
                .cloned()
        })
        .collect();
    state
        .events
        .save_events_without_observations(&relevant)
        .await?;

    for watched in active {
        if let Some(updated) = relevant.iter().find(|event| {
            (event.provider == watched.provider && event.provider_id == watched.provider_id)
                || same_occurrence(event, &watched)
        }) && updated.has_release()
        {
            let released = state.events.get(watched.id).await?;
            release(state, &released).await?;
            continue;
        }
    }
    Ok(())
}

fn same_occurrence(left: &EconomicEvent, right: &EconomicEvent) -> bool {
    left.time_exact
        && right.time_exact
        && occurrence_key(&left.country, left.event_time, &left.event)
            == occurrence_key(&right.country, right.event_time, &right.event)
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
