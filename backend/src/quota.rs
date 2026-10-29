use chrono::{Duration, Utc};
use sqlx::SqlitePool;

use crate::{config::LimitConfig, error::AppError};

/// Result of asking the daily budget for one more call.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum QuotaOutcome {
    Allowed,
    Exhausted { retry_after_seconds: i64 },
}

/// Historical per-token usage counters retained for status compatibility.
pub struct QuotaService {
    pool: SqlitePool,
}

impl QuotaService {
    pub fn new(pool: SqlitePool, _limits: LimitConfig) -> Self {
        Self { pool }
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
    /// Reports exhaustion without turning it into an exception.
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
        let count: Option<i64> = sqlx::query_scalar(
            r#"INSERT INTO api_usage (token_id, day, scope, count) VALUES (?, ?, ?, 1)
               ON CONFLICT(token_id, day, scope) DO UPDATE SET count = count + 1
               WHERE api_usage.count < ?
               RETURNING count"#,
        )
        .bind(token_id)
        .bind(&day)
        .bind(scope)
        .bind(i64::from(limit))
        .fetch_optional(&self.pool)
        .await?;
        if count.is_none() {
            return Ok(QuotaOutcome::Exhausted {
                retry_after_seconds: seconds_until_utc_midnight(),
            });
        }
        Ok(QuotaOutcome::Allowed)
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
        QuotaService::new(pool, LimitConfig::default())
    }

    #[tokio::test]
    async fn daily_budget_is_enforced_per_token() {
        let quota = service().await;
        assert!(quota.consume("alice", "test_scope", 2).await.is_ok());
        assert!(quota.consume("alice", "test_scope", 2).await.is_ok());
        let error = quota.consume("alice", "test_scope", 2).await.unwrap_err();
        assert!(matches!(error, AppError::QuotaExceeded { .. }));
        // Another token keeps its own budget.
        assert!(quota.consume("bob", "test_scope", 2).await.is_ok());
        let usage = quota.usage_today("alice").await.unwrap();
        assert_eq!(usage, vec![("test_scope".to_owned(), 2)]);
    }
}
