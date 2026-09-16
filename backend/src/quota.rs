use std::sync::Arc;

use chrono::{Duration, Utc};
use sqlx::SqlitePool;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

use crate::{config::LimitConfig, error::AppError};

/// Result of asking the daily budget for one more call.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuotaOutcome {
    Allowed,
    Exhausted { retry_after_seconds: i64 },
}

/// Per-token daily counters plus a process-wide concurrency gate for model calls.
///
/// The server owns the model key, so an unthrottled caller could spend real money; every
/// expensive scope counts against the requesting token's daily budget.
pub struct QuotaService {
    pool: SqlitePool,
    limits: LimitConfig,
    ai_slots: Arc<Semaphore>,
}

impl QuotaService {
    pub fn new(pool: SqlitePool, limits: LimitConfig) -> Self {
        let slots = Arc::new(Semaphore::new(limits.ai_concurrency.max(1)));
        Self {
            pool,
            limits,
            ai_slots: slots,
        }
    }

    pub fn ai_analysis_limit(&self) -> u32 {
        self.limits.ai_analysis_per_token_per_day
    }

    /// Counts one use of `scope` for today and fails once the daily budget is exhausted.
    pub async fn consume(&self, token_id: &str, scope: &str, limit: u32) -> Result<(), AppError> {
        match self.try_consume(token_id, scope, limit).await? {
            QuotaOutcome::Allowed => Ok(()),
            QuotaOutcome::Exhausted {
                retry_after_seconds,
            } => Err(AppError::QuotaExceeded {
                retry_after_seconds,
            }),
        }
    }

    /// Non-failing form of [`Self::consume`].
    ///
    /// The AI endpoint prefers serving the previous analysis over an error, so it needs to learn
    /// that the budget is spent without turning that into an exception.
    pub async fn try_consume(
        &self,
        token_id: &str,
        scope: &str,
        limit: u32,
    ) -> Result<QuotaOutcome, AppError> {
        if limit == 0 {
            return Ok(QuotaOutcome::Allowed);
        }
        let day = Utc::now().format("%Y-%m-%d").to_string();
        let count: i64 = sqlx::query_scalar(
            r#"INSERT INTO api_usage (token_id, day, scope, count) VALUES (?, ?, ?, 1)
               ON CONFLICT(token_id, day, scope) DO UPDATE SET count = count + 1
               RETURNING count"#,
        )
        .bind(token_id)
        .bind(&day)
        .bind(scope)
        .fetch_one(&self.pool)
        .await?;
        if count > i64::from(limit) {
            return Ok(QuotaOutcome::Exhausted {
                retry_after_seconds: seconds_until_utc_midnight(),
            });
        }
        Ok(QuotaOutcome::Allowed)
    }

    /// Blocks until a model slot is free, bounding concurrent upstream calls.
    pub async fn acquire_ai_slot(&self) -> Result<OwnedSemaphorePermit, AppError> {
        self.ai_slots
            .clone()
            .acquire_owned()
            .await
            .map_err(|_| AppError::Internal("AI concurrency gate is closed".into()))
    }

    /// Today's usage for the status endpoint.
    pub async fn usage_today(&self, token_id: &str) -> Result<Vec<(String, i64)>, AppError> {
        let day = Utc::now().format("%Y-%m-%d").to_string();
        let rows = sqlx::query_as::<_, (String, i64)>(
            "SELECT scope, count FROM api_usage WHERE token_id = ? AND day = ? ORDER BY scope",
        )
        .bind(token_id)
        .bind(day)
        .fetch_all(&self.pool)
        .await?;
        Ok(rows)
    }
}

fn seconds_until_utc_midnight() -> i64 {
    let now = Utc::now();
    let tomorrow = (now + Duration::days(1))
        .date_naive()
        .and_hms_opt(0, 0, 0)
        .expect("midnight is a valid time");
    (tomorrow - now.naive_utc()).num_seconds().max(1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::LimitConfig;

    async fn service() -> QuotaService {
        let pool = SqlitePool::connect("sqlite::memory:").await.unwrap();
        sqlx::migrate!("./migrations").run(&pool).await.unwrap();
        QuotaService::new(
            pool,
            LimitConfig {
                ai_analysis_per_token_per_day: 2,
                ai_regenerate_cooldown_seconds: 0,
                ai_concurrency: 1,
                llm_usage_retention_days: 180,
            },
        )
    }

    #[tokio::test]
    async fn daily_budget_is_enforced_per_token() {
        let quota = service().await;
        assert!(quota.consume("alice", "ai_analysis", 2).await.is_ok());
        assert!(quota.consume("alice", "ai_analysis", 2).await.is_ok());
        let error = quota.consume("alice", "ai_analysis", 2).await.unwrap_err();
        assert!(matches!(error, AppError::QuotaExceeded { .. }));
        // Another token keeps its own budget.
        assert!(quota.consume("bob", "ai_analysis", 2).await.is_ok());
        let usage = quota.usage_today("alice").await.unwrap();
        assert_eq!(usage, vec![("ai_analysis".to_owned(), 3)]);
    }
}
