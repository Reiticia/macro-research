use std::{sync::Arc, time::Duration as StdDuration};

use chrono::{DateTime, Duration, Utc};

use crate::{calendar::CalendarService, config::CalendarConfig};

/// Refresh the last seven UTC calendar days, including today, once per server start.
/// Separate from the upcoming sync so historical requests cannot delay live monitoring.
pub async fn startup_calendar_sync(service: Arc<CalendarService>) {
    for (start, end) in recent_day_ranges(Utc::now()) {
        match service.refresh(start, end).await {
            Ok(fetched) => {
                if let Some(detail) = fetched.degraded_detail {
                    tracing::warn!(%start, %end, count = fetched.events.len(), %detail,
                        "startup calendar refresh degraded; weekly fallback may not cover history");
                } else {
                    tracing::info!(%start, %end, count = fetched.events.len(),
                        "startup calendar data and translations synchronized");
                }
            }
            Err(error) => tracing::error!(%start, %end, %error,
                "startup calendar refresh failed; continuing with next day"),
        }
    }
}

fn recent_day_ranges(now: DateTime<Utc>) -> Vec<(DateTime<Utc>, DateTime<Utc>)> {
    let today = now.date_naive().and_hms_opt(0, 0, 0).unwrap().and_utc();
    (0..7)
        .map(|index| {
            let start = today - Duration::days(6 - index);
            (start, start + Duration::days(1))
        })
        .collect()
}

pub async fn calendar_sync_loop(
    service: Arc<CalendarService>,
    config: CalendarConfig,
    interval_seconds: u64,
) {
    let mut interval = tokio::time::interval(StdDuration::from_secs(interval_seconds.max(60)));
    loop {
        interval.tick().await;
        let start = Utc::now() - Duration::hours(6);
        let end = start + Duration::days(config.sync_days.max(1));
        match service.refresh(start, end).await {
            Ok(fetched) => match &fetched.degraded_detail {
                Some(detail) => tracing::warn!(
                    count = fetched.events.len(),
                    detail,
                    "economic calendar synchronized from the fallback feed"
                ),
                None => tracing::info!(
                    count = fetched.events.len(),
                    "economic calendar synchronized"
                ),
            },
            Err(error) => {
                tracing::error!(error = %error, "economic calendar synchronization failed")
            }
        }
    }
}
