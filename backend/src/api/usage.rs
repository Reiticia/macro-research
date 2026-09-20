use axum::{
    Json,
    extract::{Query, State},
};

use crate::{AppState, error::AppError, llm_usage::LlmUsageSummary};

#[derive(Debug, serde::Deserialize)]
pub struct UsageQuery {
    /// Window in days for the per-category aggregates.
    days: Option<i64>,
    /// How many individual records to include.
    recent: Option<i64>,
}

/// Model-call audit: token spend grouped by category (scope + pass + model), with the newest
/// records attached so a spike can be traced back to a single request.
///
/// Records are pruned to `[limits] llm_usage_retention_days` before the summary is built, so no
/// extra scheduler is needed.
pub async fn usage(
    State(state): State<AppState>,
    Query(query): Query<UsageQuery>,
) -> Result<Json<LlmUsageSummary>, AppError> {
    let audit = state
        .llm_usage
        .as_ref()
        .ok_or_else(|| AppError::AnalysisUnavailable("usage auditing is disabled".into()))?;
    if let Err(error) = audit.prune().await {
        tracing::warn!(%error, "LLM usage pruning failed");
    }
    Ok(Json(
        audit
            .summary(query.days.unwrap_or(7), query.recent.unwrap_or(20))
            .await?,
    ))
}
