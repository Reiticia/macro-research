use chrono::{DateTime, Duration, Utc};
use serde::Serialize;
use serde_json::Value;
use sqlx::{Row, SqlitePool};

use crate::error::AppError;

/// What a model call was for. Every row in the audit log carries one of these, so the operator
/// can answer "which category spent the tokens" and not just "how many tokens were spent".
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum LlmScope {
    /// The post-release AI market briefing.
    AiAnalysis,
    /// Event-name translation batches.
    Translation,
    /// Jev market-symbol selection for a scheduled event.
    MarketSelection,
}

impl LlmScope {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::AiAnalysis => "ai_analysis",
            Self::Translation => "translation",
            Self::MarketSelection => "market_selection",
        }
    }
}

/// Finer-grained category inside a scope.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum LlmKind {
    /// Analysis pass one: numbers and rules only, no market moves.
    ExAnte,
    /// Analysis pass two: validates the ex-ante chain against the observed moves.
    Comparison,
    /// Analysis in a single pass with numbers, rules and moves together.
    SinglePass,
    /// One translation batch.
    Translation,
    /// One Jev market-symbol selection request.
    MarketSelection,
}

impl LlmKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::ExAnte => "ex_ante",
            Self::Comparison => "comparison",
            Self::SinglePass => "single_pass",
            Self::Translation => "translation",
            Self::MarketSelection => "market_selection",
        }
    }
}

/// Token usage reported by the relay. Every field is optional: gateways differ in what they echo.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct TokenUsage {
    pub prompt_tokens: Option<i64>,
    pub completion_tokens: Option<i64>,
    pub total_tokens: Option<i64>,
    pub reasoning_tokens: Option<i64>,
    pub cached_tokens: Option<i64>,
}

impl TokenUsage {
    pub fn is_empty(&self) -> bool {
        self.prompt_tokens.is_none()
            && self.completion_tokens.is_none()
            && self.total_tokens.is_none()
    }

    /// Parses the standard OpenAI `usage` object, tolerating strings, missing members and
    /// reasoning-model detail blocks.
    pub fn from_response(root: &Value) -> Self {
        let usage = root.get("usage").unwrap_or(&Value::Null);
        let detail = |path: &[&str], field: &str| -> Option<i64> {
            let mut node = usage;
            for key in path {
                node = node.get(*key)?;
            }
            node.get(field).and_then(as_i64)
        };
        let total = number(usage, "total_tokens");
        let prompt = number(usage, "prompt_tokens");
        let completion = number(usage, "completion_tokens");
        Self {
            prompt_tokens: prompt,
            completion_tokens: completion,
            // Some relays omit the total; it is derivable when both parts are present.
            total_tokens: total.or_else(|| match (prompt, completion) {
                (Some(prompt), Some(completion)) => Some(prompt + completion),
                _ => None,
            }),
            reasoning_tokens: detail(&["completion_tokens_details"], "reasoning_tokens"),
            cached_tokens: detail(&["prompt_tokens_details"], "cached_tokens"),
        }
    }

    /// Parses the usage block out of a raw response body, when it is JSON at all.
    pub fn from_body(body: &str) -> Self {
        serde_json::from_str::<Value>(body)
            .map(|root| Self::from_response(&root))
            .unwrap_or_default()
    }
}

fn number(node: &Value, key: &str) -> Option<i64> {
    node.get(key).and_then(as_i64)
}

fn as_i64(value: &Value) -> Option<i64> {
    match value {
        Value::Number(number) => number.as_i64(),
        Value::String(text) => text.trim().parse().ok(),
        _ => None,
    }
}

/// One recorded model call.
#[derive(Clone, Debug)]
pub struct LlmUsageEntry {
    pub scope: LlmScope,
    pub kind: LlmKind,
    /// Calling API token, or `server` for work the scheduler started on its own.
    pub token_id: String,
    pub event_id: Option<i64>,
    pub language: Option<String>,
    pub method: Option<u8>,
    pub model: String,
    /// Host of the relay that served the call, so two relays can be told apart.
    pub endpoint_host: String,
    pub usage: TokenUsage,
    pub latency_ms: i64,
    /// Number of HTTP attempts, including the compatibility retries.
    pub attempts: i64,
    pub status: &'static str,
    pub error: Option<String>,
}

impl LlmUsageEntry {
    pub fn ok(
        scope: LlmScope,
        kind: LlmKind,
        model: impl Into<String>,
        endpoint_host: impl Into<String>,
    ) -> Self {
        Self {
            scope,
            kind,
            token_id: "server".into(),
            event_id: None,
            language: None,
            method: None,
            model: model.into(),
            endpoint_host: endpoint_host.into(),
            usage: TokenUsage::default(),
            latency_ms: 0,
            attempts: 1,
            status: "ok",
            error: None,
        }
    }

    pub fn failed(mut self, error: impl Into<String>) -> Self {
        self.status = "error";
        self.error = Some(error.into());
        self
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LlmUsageRow {
    pub id: i64,
    pub created_at: String,
    pub scope: String,
    pub kind: String,
    pub token_id: String,
    pub event_id: Option<i64>,
    pub language: Option<String>,
    pub method: Option<i64>,
    pub model: String,
    pub endpoint_host: String,
    pub prompt_tokens: i64,
    pub completion_tokens: i64,
    pub total_tokens: i64,
    pub reasoning_tokens: i64,
    pub cached_tokens: i64,
    pub latency_ms: i64,
    pub attempts: i64,
    pub status: String,
    pub error: Option<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LlmUsageBucket {
    pub scope: String,
    pub kind: String,
    pub model: String,
    pub calls: i64,
    pub failures: i64,
    pub prompt_tokens: i64,
    pub completion_tokens: i64,
    pub total_tokens: i64,
    pub reasoning_tokens: i64,
    pub avg_latency_ms: i64,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LlmUsageSummary {
    pub days: i64,
    pub calls: i64,
    pub failures: i64,
    pub total_tokens: i64,
    pub by_category: Vec<LlmUsageBucket>,
    pub recent: Vec<LlmUsageRow>,
}

/// Append-only audit log of every model call: category, model, tokens, latency and outcome.
pub struct LlmUsageRepository {
    pool: SqlitePool,
    retention_days: i64,
}

impl LlmUsageRepository {
    pub fn new(pool: SqlitePool, retention_days: i64) -> Self {
        Self {
            pool,
            retention_days: retention_days.max(1),
        }
    }

    /// Writes one record. Callers must not fail their request because auditing failed, so this
    /// is only ever logged on error.
    pub async fn record(&self, entry: &LlmUsageEntry) -> Result<(), AppError> {
        sqlx::query(
            r#"INSERT INTO llm_usage (
                 created_at, scope, kind, token_id, event_id, language, method, model,
                 endpoint_host, prompt_tokens, completion_tokens, total_tokens,
                 reasoning_tokens, cached_tokens, latency_ms, attempts, status, error
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"#,
        )
        .bind(Utc::now().to_rfc3339())
        .bind(entry.scope.as_str())
        .bind(entry.kind.as_str())
        .bind(&entry.token_id)
        .bind(entry.event_id)
        .bind(&entry.language)
        .bind(entry.method.map(i64::from))
        .bind(&entry.model)
        .bind(&entry.endpoint_host)
        .bind(entry.usage.prompt_tokens.unwrap_or(0))
        .bind(entry.usage.completion_tokens.unwrap_or(0))
        .bind(entry.usage.total_tokens.unwrap_or(0))
        .bind(entry.usage.reasoning_tokens.unwrap_or(0))
        .bind(entry.usage.cached_tokens.unwrap_or(0))
        .bind(entry.latency_ms)
        .bind(entry.attempts)
        .bind(entry.status)
        .bind(&entry.error)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    /// Records without ever surfacing a failure to the caller.
    pub async fn record_best_effort(&self, entry: LlmUsageEntry) {
        if let Err(error) = self.record(&entry).await {
            tracing::warn!(%error, "LLM usage audit write failed");
        }
    }

    /// Aggregates by category (scope + kind + model) over the last `days`, plus the newest rows.
    pub async fn summary(&self, days: i64, recent_limit: i64) -> Result<LlmUsageSummary, AppError> {
        let days = days.clamp(1, 365);
        let cutoff = (Utc::now() - Duration::days(days)).to_rfc3339();
        let rows = sqlx::query(
            r#"SELECT scope, kind, model,
                      COUNT(*)                                        AS calls,
                      SUM(CASE WHEN status != 'ok' THEN 1 ELSE 0 END)  AS failures,
                      SUM(prompt_tokens)                              AS prompt_tokens,
                      SUM(completion_tokens)                          AS completion_tokens,
                      SUM(total_tokens)                               AS total_tokens,
                      SUM(reasoning_tokens)                           AS reasoning_tokens,
                      CAST(AVG(latency_ms) AS INTEGER)                 AS avg_latency_ms
               FROM llm_usage WHERE created_at >= ?
               GROUP BY scope, kind, model
               ORDER BY total_tokens DESC, calls DESC"#,
        )
        .bind(&cutoff)
        .fetch_all(&self.pool)
        .await?;
        let mut by_category = Vec::with_capacity(rows.len());
        let mut calls = 0;
        let mut failures = 0;
        let mut total_tokens = 0;
        for row in rows {
            let bucket = LlmUsageBucket {
                scope: row.try_get("scope")?,
                kind: row.try_get("kind")?,
                model: row.try_get("model")?,
                calls: row.try_get("calls")?,
                failures: row.try_get("failures")?,
                prompt_tokens: row.try_get("prompt_tokens")?,
                completion_tokens: row.try_get("completion_tokens")?,
                total_tokens: row.try_get("total_tokens")?,
                reasoning_tokens: row.try_get("reasoning_tokens")?,
                avg_latency_ms: row.try_get("avg_latency_ms")?,
            };
            calls += bucket.calls;
            failures += bucket.failures;
            total_tokens += bucket.total_tokens;
            by_category.push(bucket);
        }
        Ok(LlmUsageSummary {
            days,
            calls,
            failures,
            total_tokens,
            by_category,
            recent: self.recent(recent_limit).await?,
        })
    }

    pub async fn recent(&self, limit: i64) -> Result<Vec<LlmUsageRow>, AppError> {
        let rows = sqlx::query("SELECT * FROM llm_usage ORDER BY id DESC LIMIT ?")
            .bind(limit.clamp(1, 200))
            .fetch_all(&self.pool)
            .await?;
        rows.into_iter().map(row_to_usage).collect()
    }

    /// Drops records past the retention window. Called before serving a summary, so no extra
    /// scheduler is needed.
    pub async fn prune(&self) -> Result<u64, AppError> {
        let cutoff = (Utc::now() - Duration::days(self.retention_days)).to_rfc3339();
        let result = sqlx::query("DELETE FROM llm_usage WHERE created_at < ?")
            .bind(cutoff)
            .execute(&self.pool)
            .await?;
        Ok(result.rows_affected())
    }

    /// The timestamp of the newest record, used by the admin dashboard to detect a stalled relay.
    pub async fn last_recorded_at(&self) -> Result<Option<DateTime<Utc>>, AppError> {
        let value: Option<String> = sqlx::query_scalar("SELECT MAX(created_at) FROM llm_usage")
            .fetch_one(&self.pool)
            .await?;
        Ok(value.and_then(|raw| raw.parse().ok()))
    }
}

fn row_to_usage(row: sqlx::sqlite::SqliteRow) -> Result<LlmUsageRow, AppError> {
    Ok(LlmUsageRow {
        id: row.try_get("id")?,
        created_at: row.try_get("created_at")?,
        scope: row.try_get("scope")?,
        kind: row.try_get("kind")?,
        token_id: row.try_get("token_id")?,
        event_id: row.try_get("event_id")?,
        language: row.try_get("language")?,
        method: row.try_get("method")?,
        model: row.try_get("model")?,
        endpoint_host: row.try_get("endpoint_host")?,
        prompt_tokens: row.try_get("prompt_tokens")?,
        completion_tokens: row.try_get("completion_tokens")?,
        total_tokens: row.try_get("total_tokens")?,
        reasoning_tokens: row.try_get("reasoning_tokens")?,
        cached_tokens: row.try_get("cached_tokens")?,
        latency_ms: row.try_get("latency_ms")?,
        attempts: row.try_get("attempts")?,
        status: row.try_get("status")?,
        error: row.try_get("error")?,
    })
}

/// Host part of a base URL, so "which relay" is auditable without storing full URLs.
pub fn endpoint_host(base_url: &str) -> String {
    let trimmed = base_url.trim();
    let without_scheme = trimmed
        .strip_prefix("https://")
        .or_else(|| trimmed.strip_prefix("http://"))
        .unwrap_or(trimmed);
    without_scheme
        .split('/')
        .next()
        .unwrap_or(without_scheme)
        .to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn usage_is_parsed_from_the_standard_object_including_reasoning_details() {
        let body = r#"{
            "choices": [],
            "usage": {
                "prompt_tokens": 120, "completion_tokens": 800, "total_tokens": 920,
                "completion_tokens_details": {"reasoning_tokens": 700},
                "prompt_tokens_details": {"cached_tokens": 64}
            }
        }"#;
        let usage = TokenUsage::from_body(body);
        assert_eq!(usage.prompt_tokens, Some(120));
        assert_eq!(usage.completion_tokens, Some(800));
        assert_eq!(usage.total_tokens, Some(920));
        assert_eq!(usage.reasoning_tokens, Some(700));
        assert_eq!(usage.cached_tokens, Some(64));
    }

    #[test]
    fn a_relay_that_omits_usage_yields_zeroes_instead_of_failing() {
        assert!(TokenUsage::from_body(r#"{"choices":[]}"#).is_empty());
        assert!(TokenUsage::from_body("not json").is_empty());
        // String numbers and a missing total are tolerated.
        let usage =
            TokenUsage::from_body(r#"{"usage":{"prompt_tokens":"10","completion_tokens":"5"}}"#);
        assert_eq!(usage.total_tokens, Some(15));
    }

    #[test]
    fn the_relay_host_is_recorded_without_the_path() {
        assert_eq!(
            endpoint_host("https://relay.example.com/v1"),
            "relay.example.com"
        );
        assert_eq!(
            endpoint_host("http://127.0.0.1:8080/v1/chat/completions"),
            "127.0.0.1:8080"
        );
    }
}
