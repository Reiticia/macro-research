use std::time::Duration as StdDuration;

use chrono::{Duration, Utc};

use crate::{
    AppState,
    config::SchedulerConfig,
    model::{AppEvent, EventStatus},
};

pub async fn market_collect_loop(state: AppState, config: SchedulerConfig) {
    let mut interval =
        tokio::time::interval(StdDuration::from_secs(config.market_poll_seconds.max(5)));
    loop {
        interval.tick().await;
        if let Err(error) = collect_once(&state, &config).await {
            tracing::error!(error = %error, "market collector iteration failed");
        }
    }
}

async fn collect_once(
    state: &AppState,
    config: &SchedulerConfig,
) -> Result<(), crate::error::AppError> {
    let now = Utc::now();
    for event in state.events.market_active().await? {
        let collection_end =
            event.event_time + Duration::minutes(config.market_collect_after_minutes);
        if now <= collection_end {
            let saved = state.market_service.collect_for_event(event.id).await?;
            if saved > 0 {
                let _ = state
                    .event_bus
                    .send(AppEvent::MarketDataCollected { event_id: event.id });
            }
            if event.status == EventStatus::Released {
                state
                    .events
                    .set_status(event.id, EventStatus::CollectingMarketData)
                    .await?;
            }
            continue;
        }

        if matches!(
            event.status,
            EventStatus::Released | EventStatus::CollectingMarketData | EventStatus::Analyzing
        ) {
            state
                .events
                .set_status(event.id, EventStatus::Analyzing)
                .await?;
            match state.analysis_service.analyze(event.id).await {
                Ok(_) => {
                    state
                        .events
                        .set_status(event.id, EventStatus::Completed)
                        .await?;
                    let _ = state
                        .event_bus
                        .send(AppEvent::AnalysisCompleted { event_id: event.id });
                    tracing::info!(event_id = event.id, "event analysis completed");
                }
                Err(error) => {
                    state
                        .events
                        .set_status(event.id, EventStatus::CollectingMarketData)
                        .await?;
                    return Err(error);
                }
            }
        }
    }
    Ok(())
}
