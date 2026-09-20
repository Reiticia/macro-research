use std::sync::Arc;

use chrono::{DateTime, Duration, Utc};
use serde::Serialize;
use sqlx::{Row, SqlitePool};
use tokio::sync::Mutex;

use crate::{
    alert::service::{AlertService, Severity},
    error::AppError,
};

/// Health of one upstream source as reported by the scheduler and the API handlers.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SourceHealth {
    pub key: String,
    pub status: String,
    pub consecutive_failures: i64,
    pub last_error: Option<String>,
    pub last_changed_at: Option<String>,
    pub last_notified_at: Option<String>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Status {
    Healthy,
    Degraded,
    Down,
}

impl Status {
    fn as_str(self) -> &'static str {
        match self {
            Self::Healthy => "healthy",
            Self::Degraded => "degraded",
            Self::Down => "down",
        }
    }

    fn parse(value: &str) -> Self {
        match value {
            "degraded" => Self::Degraded,
            "down" => Self::Down,
            _ => Self::Healthy,
        }
    }
}

enum Outcome {
    Success,
    Failure(String),
    Degraded(String),
}

/// Tracks consecutive failures per source and notifies the admin on state transitions.
///
/// Updates are serialized: several schedulers record into the same rows, and a lost
/// read-modify-write would either drop an alert or repeat one.
pub struct HealthRegistry {
    pool: SqlitePool,
    alerts: Option<Arc<AlertService>>,
    failure_threshold: u32,
    cooldown_seconds: i64,
    lock: Mutex<()>,
}

impl HealthRegistry {
    pub fn new(
        pool: SqlitePool,
        alerts: Option<Arc<AlertService>>,
        failure_threshold: u32,
        cooldown_seconds: i64,
    ) -> Self {
        Self {
            pool,
            alerts,
            failure_threshold: failure_threshold.max(1),
            cooldown_seconds: cooldown_seconds.max(0),
            lock: Mutex::new(()),
        }
    }

    pub async fn record_success(&self, key: &str) {
        self.record(key, Outcome::Success).await;
    }

    pub async fn record_failure(&self, key: &str, error: impl Into<String>) {
        self.record(key, Outcome::Failure(error.into())).await;
    }

    /// The source answered through a degraded path (for example the calendar fallback feed).
    pub async fn record_degraded(&self, key: &str, error: impl Into<String>) {
        self.record(key, Outcome::Degraded(error.into())).await;
    }

    async fn record(&self, key: &str, outcome: Outcome) {
        let _guard = self.lock.lock().await;
        if let Err(error) = self.record_locked(key, outcome).await {
            tracing::warn!(key, %error, "source health update failed");
        }
    }

    async fn record_locked(&self, key: &str, outcome: Outcome) -> Result<(), AppError> {
        let now = Utc::now();
        let current = self.load(key).await?;
        let previous_status = current
            .as_ref()
            .map(|health| Status::parse(&health.status))
            // A source observed for the first time never produces a recovery notification.
            .unwrap_or(Status::Healthy);
        let previous_failures = current
            .as_ref()
            .map(|health| health.consecutive_failures)
            .unwrap_or(0);
        let (status, failures, last_error) = match outcome {
            Outcome::Success => (Status::Healthy, 0, None),
            Outcome::Failure(error) => {
                let failures = previous_failures + 1;
                let status = if failures >= i64::from(self.failure_threshold) {
                    Status::Down
                } else {
                    previous_status
                };
                (status, failures, Some(error))
            }
            Outcome::Degraded(error) => (Status::Degraded, previous_failures, Some(error)),
        };
        let changed = status != previous_status;
        let last_changed_at = if changed {
            now.to_rfc3339()
        } else {
            current
                .as_ref()
                .and_then(|health| health.last_changed_at.clone())
                .unwrap_or_else(|| now.to_rfc3339())
        };
        let notified_recently = current
            .as_ref()
            .and_then(|health| health.last_notified_at.as_deref())
            .and_then(|value| DateTime::parse_from_rfc3339(value).ok())
            .map(|parsed| {
                now - parsed.with_timezone(&Utc) < Duration::seconds(self.cooldown_seconds)
            })
            .unwrap_or(false);
        let should_notify = if changed {
            // A first-time healthy observation needs no announcement.
            !(status == Status::Healthy && current.is_none())
        } else {
            status != Status::Healthy && !notified_recently
        };
        let last_notified_at = if should_notify {
            Some(now.to_rfc3339())
        } else {
            current
                .as_ref()
                .and_then(|health| health.last_notified_at.clone())
        };

        sqlx::query(
            r#"INSERT INTO alert_state
                 (key, status, consecutive_failures, last_error, last_changed_at, last_notified_at)
               VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT(key) DO UPDATE SET
                 status = excluded.status,
                 consecutive_failures = excluded.consecutive_failures,
                 last_error = excluded.last_error,
                 last_changed_at = excluded.last_changed_at,
                 last_notified_at = excluded.last_notified_at"#,
        )
        .bind(key)
        .bind(status.as_str())
        .bind(failures)
        .bind(&last_error)
        .bind(&last_changed_at)
        .bind(&last_notified_at)
        .execute(&self.pool)
        .await?;

        if should_notify && let Some(alerts) = &self.alerts {
            let severity = match status {
                Status::Healthy => Severity::Info,
                Status::Degraded => Severity::Warning,
                Status::Down => Severity::Warning,
            };
            let message = match status {
                Status::Healthy => format!("数据源 {key} 已恢复。"),
                Status::Degraded => format!(
                    "数据源 {key} 已降级，正在使用回退路径。{}",
                    last_error.unwrap_or_default()
                ),
                Status::Down => format!(
                    "数据源 {key} 连续失败 {failures} 次，已判定不可用。{}",
                    last_error.unwrap_or_default()
                ),
            };
            alerts
                .notify(severity, key, status.as_str(), message)
                .await?;
        }
        Ok(())
    }

    async fn load(&self, key: &str) -> Result<Option<SourceHealth>, AppError> {
        let row = sqlx::query("SELECT * FROM alert_state WHERE key = ?")
            .bind(key)
            .fetch_optional(&self.pool)
            .await?;
        row.map(|row| {
            Ok(SourceHealth {
                key: row.try_get("key")?,
                status: row.try_get("status")?,
                consecutive_failures: row.try_get("consecutive_failures")?,
                last_error: row.try_get("last_error")?,
                last_changed_at: row.try_get("last_changed_at")?,
                last_notified_at: row.try_get("last_notified_at")?,
            })
        })
        .transpose()
    }

    pub async fn snapshot(&self) -> Result<Vec<SourceHealth>, AppError> {
        let rows = sqlx::query("SELECT * FROM alert_state ORDER BY key ASC")
            .fetch_all(&self.pool)
            .await?;
        rows.into_iter()
            .map(|row| {
                Ok(SourceHealth {
                    key: row.try_get("key")?,
                    status: row.try_get("status")?,
                    consecutive_failures: row.try_get("consecutive_failures")?,
                    last_error: row.try_get("last_error")?,
                    last_changed_at: row.try_get("last_changed_at")?,
                    last_notified_at: row.try_get("last_notified_at")?,
                })
            })
            .collect()
    }

    /// Escalation used when every calendar path failed, which the per-source states cannot
    /// express on their own.
    pub async fn notify_critical(&self, key: &str, message: String) {
        if let Some(alerts) = &self.alerts
            && let Err(error) = alerts
                .notify(Severity::Critical, key, "down", message)
                .await
        {
            tracing::warn!(%error, "critical alert delivery failed");
        }
    }
}
