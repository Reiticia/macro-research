use std::sync::Arc;

use chrono::{Duration, Utc};
use sqlx::Row;

use crate::{
    AppState,
    alert::{AlertService, Severity},
    config::AlertConfig,
    repository::EventRepository,
};

/// Reports events whose release time has passed without any source publishing an actual value.
///
/// Those rows are exactly the ones that silently stay empty in the list, so the admin gets a
/// digest instead of a per-event flood, and the same event is not repeated for six hours.
pub async fn data_missing_loop(
    state: AppState,
    alerts: Arc<AlertService>,
    interval_seconds: u64,
    config: AlertConfig,
) {
    let mut interval =
        tokio::time::interval(std::time::Duration::from_secs(interval_seconds.max(60)));
    loop {
        interval.tick().await;
        if let Err(error) = check_once(&state.events, &alerts, &config).await {
            tracing::warn!(%error, "missing release check failed");
        }
    }
}

async fn check_once(
    events: &EventRepository,
    alerts: &AlertService,
    config: &AlertConfig,
) -> Result<(), crate::error::AppError> {
    // Do not consume per-event cooldowns while the admin has explicitly disabled this digest.
    if !alerts.notification_enabled("calendar.data_missing").await? {
        return Ok(());
    }
    let now = Utc::now();
    let older_than = now - Duration::minutes(config.data_missing_after_minutes.max(1));
    let missing = events
        .missing_release_values(older_than, now, config.digest_max_events as i64)
        .await?;
    if missing.is_empty() {
        return Ok(());
    }
    let cutoff =
        (now - Duration::seconds(config.data_missing_cooldown_seconds.max(0))).to_rfc3339();
    let pool = events.pool();
    let rows = sqlx::query("SELECT event_id FROM data_missing_notice WHERE notified_at >= ?")
        .bind(&cutoff)
        .fetch_all(pool)
        .await?;
    let recently_notified: Vec<i64> = rows
        .into_iter()
        .filter_map(|row| row.try_get("event_id").ok())
        .collect();
    let pending: Vec<_> = missing
        .into_iter()
        .filter(|event| !recently_notified.contains(&event.id))
        .collect();
    if pending.is_empty() {
        return Ok(());
    }
    let mut lines = vec![format!(
        "以下事件已到公布时间但超过 {} 分钟仍无公布值，请检查日历数据源：",
        config.data_missing_after_minutes
    )];
    for event in &pending {
        lines.push(format!(
            "• {} {} ({})",
            event.event_time.format("%m-%d %H:%M UTC"),
            event.event,
            event.country
        ));
    }
    alerts
        .notify(
            Severity::Warning,
            "calendar.data_missing",
            "degraded",
            lines.join("\n"),
        )
        .await?;
    for event in &pending {
        sqlx::query(
            r#"INSERT INTO data_missing_notice (event_id, notified_at) VALUES (?, ?)
               ON CONFLICT(event_id) DO UPDATE SET notified_at = excluded.notified_at"#,
        )
        .bind(event.id)
        .bind(now.to_rfc3339())
        .execute(pool)
        .await?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::alert::preferences::{self, NotificationKind};

    #[tokio::test]
    async fn disabling_missing_digest_does_not_consume_event_cooldown() {
        let pool = sqlx::sqlite::SqlitePoolOptions::new()
            .max_connections(1)
            .connect("sqlite::memory:")
            .await
            .unwrap();
        sqlx::migrate!("./migrations").run(&pool).await.unwrap();
        sqlx::query(
            "INSERT INTO economic_event (provider, provider_id, country, category, event, event_time, importance) \
             VALUES ('fixture', 'missing-pmi', 'Australia', 'business', 'Manufacturing PMI', ?, 2)",
        )
        .bind((Utc::now() - Duration::hours(1)).to_rfc3339())
        .execute(&pool).await.unwrap();
        let events = EventRepository::new(pool.clone());
        let config = AlertConfig::default();
        let alerts = AlertService::new(None, Vec::new(), pool.clone(), config.clone());

        preferences::set_enabled(&pool, NotificationKind::DataMissing, false)
            .await
            .unwrap();
        check_once(&events, &alerts, &config).await.unwrap();
        let notices: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM data_missing_notice")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(notices, 0);
        let attempts: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM alert_event")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(attempts, 0);

        // Source notifications can remain off without affecting the missing-value digest.
        preferences::set_enabled(&pool, NotificationKind::DataSources, false)
            .await
            .unwrap();
        preferences::set_enabled(&pool, NotificationKind::DataMissing, true)
            .await
            .unwrap();
        check_once(&events, &alerts, &config).await.unwrap();
        let notices: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM data_missing_notice")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(notices, 1);
        check_once(&events, &alerts, &config).await.unwrap();
        let attempts: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM alert_event WHERE key = 'calendar.data_missing'",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(attempts, 1);
    }
}
