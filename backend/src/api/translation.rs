use axum::{
    Extension, Json,
    extract::{Query, State},
};
use serde::Deserialize;

use crate::{AppState, auth::TokenId, error::AppError, translation_correction::CorrectionStatus};

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CorrectionRequest {
    event_name: String,
    zh_cn: String,
    zh_tw: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CorrectionQuery {
    event_name: String,
}

/// Queues a reader-submitted translation correction. Nothing changes until the admin approves
/// it over Telegram; a muted event name is refused without notifying anyone.
pub async fn submit(
    State(state): State<AppState>,
    Extension(token): Extension<TokenId>,
    Json(body): Json<CorrectionRequest>,
) -> Result<Json<CorrectionStatus>, AppError> {
    Ok(Json(
        state
            .corrections
            .submit(&body.event_name, &body.zh_cn, &body.zh_tw, Some(token.0))
            .await?,
    ))
}

pub async fn latest(
    State(state): State<AppState>,
    Query(query): Query<CorrectionQuery>,
) -> Result<Json<Option<CorrectionStatus>>, AppError> {
    Ok(Json(state.corrections.latest_for(&query.event_name).await?))
}
