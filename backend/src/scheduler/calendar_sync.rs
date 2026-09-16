use std::{sync::Arc, time::Duration as StdDuration};

use chrono::{Duration, Utc};

use crate::{calendar::CalendarService, config::CalendarConfig};

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
