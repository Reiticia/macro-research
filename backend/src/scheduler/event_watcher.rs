use std::{collections::HashSet, time::Duration as StdDuration};

use chrono::{Duration, Utc};

use crate::{
    AppState,
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
    let watched_ids: HashSet<&str> = active
        .iter()
        .map(|event| event.provider_id.as_str())
        .collect();
    let relevant: Vec<EconomicEvent> = remote
        .into_iter()
        .filter(|event| watched_ids.contains(event.provider_id.as_str()))
        .collect();
    state
        .events
        .save_events_without_observations(&relevant)
        .await?;

    for watched in active {
        if let Some(updated) = relevant
            .iter()
            .find(|event| event.provider_id == watched.provider_id)
            && updated.has_release()
        {
            let released = state.events.get(watched.id).await?;
            release(state, &released).await?;
            continue;
        }
    }
    Ok(())
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
