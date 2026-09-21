use axum::{
    Extension, Json,
    extract::{Path, Query, State},
};
use chrono::{Duration, NaiveDate, TimeZone, Utc};
use serde::{Deserialize, Serialize};

use crate::{
    AppState,
    ai_analysis::AiAnalysisResponse,
    auth::TokenId,
    error::AppError,
    model::{AnalysisReport, EconomicEvent, EventObservation},
};

#[derive(Deserialize)]
pub struct ProviderQuery {
    provider: String,
    provider_id: String,
}

pub async fn by_provider(
    State(state): State<AppState>,
    Query(query): Query<ProviderQuery>,
) -> Result<Json<EconomicEvent>, AppError> {
    Ok(Json(
        state
            .events
            .find_provider_event(&query.provider, &query.provider_id)
            .await?
            .ok_or(AppError::NotFound)?,
    ))
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EventDetail {
    event: EconomicEvent,
    observations: Vec<EventObservation>,
}

async fn detail_for(state: &AppState, id: i64) -> Result<EventDetail, AppError> {
    let event = state.events.get(id).await?;
    let observations = state.events.observations(id).await?;
    Ok(EventDetail {
        event,
        observations,
    })
}

pub async fn detail(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> Result<Json<EventDetail>, AppError> {
    Ok(Json(detail_for(&state, id).await?))
}

/// Re-fetches the event's own day, bypassing the periodic sync interval, so a row whose
/// actual value has just been published can be refreshed on demand.
pub async fn refresh(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> Result<Json<EventDetail>, AppError> {
    let event = state.events.get(id).await?;
    let day_start = event
        .event_time
        .date_naive()
        .and_hms_opt(0, 0, 0)
        .ok_or_else(|| AppError::Internal("event date overflow".into()))?
        .and_utc();
    state
        .calendar_service
        .sync(day_start, day_start + Duration::days(1))
        .await?;
    Ok(Json(detail_for(&state, id).await?))
}

pub async fn analysis(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> Result<Json<AnalysisReport>, AppError> {
    Ok(Json(state.analyses.get(id).await?))
}

#[derive(Debug, Deserialize)]
pub struct HistoryQuery {
    /// One country, or several joined with commas (the client filters by a country set).
    country: Option<String>,
    category: Option<String>,
    limit: Option<u32>,
    offset: Option<u32>,
    from: Option<NaiveDate>,
    to: Option<NaiveDate>,
}

pub async fn history(
    State(state): State<AppState>,
    Query(query): Query<HistoryQuery>,
) -> Result<Json<Vec<EconomicEvent>>, AppError> {
    if matches!((query.from, query.to), (Some(from), Some(to)) if to < from || to - from >= Duration::days(93))
    {
        return Err(AppError::InvalidRequest(
            "history date range must be ordered and at most 93 days".into(),
        ));
    }
    let from = query
        .from
        .map(|d| d.and_hms_opt(0, 0, 0).unwrap().and_utc());
    let to = query
        .to
        .map(|d| {
            d.succ_opt()
                .ok_or_else(|| AppError::InvalidRequest("date overflow".into()))
        })
        .transpose()?
        .map(|d| d.and_hms_opt(0, 0, 0).unwrap().and_utc());
    let countries: Vec<String> = query
        .country
        .as_deref()
        .unwrap_or_default()
        .split(',')
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .collect();
    Ok(Json(
        state
            .events
            .history_page_multi(
                countries,
                query.category.as_deref(),
                query.limit.unwrap_or(100),
                query.offset.unwrap_or(0),
                from,
                to,
            )
            .await?,
    ))
}

#[derive(Debug, Deserialize)]
pub struct AiQuery {
    language: Option<String>,
    method: Option<u8>,
}

#[derive(Debug, Deserialize)]
pub struct ProviderAiQuery {
    provider: String,
    provider_id: String,
    language: Option<String>,
    method: Option<u8>,
}

#[derive(Debug, Deserialize)]
pub struct FeedbackRequest {
    language: String,
    method: u8,
    revision: i64,
    message: String,
}

fn language_or_default(value: Option<&str>) -> String {
    value.unwrap_or("en").to_owned()
}

async fn cached_ai_analysis(
    state: &AppState,
    id: i64,
    language: Option<&str>,
    method: Option<u8>,
) -> Result<Json<AiAnalysisResponse>, AppError> {
    let language = language_or_default(language);
    let method = method.unwrap_or(state.config.ai.default_method);
    if !(1..=3).contains(&method) {
        return Err(AppError::InvalidRequest("method must be 1..3".into()));
    }
    let row = sqlx::query(
        "SELECT * FROM ai_analysis WHERE event_id=? AND language=? AND method=? AND timezone='UTC'",
    )
    .bind(id)
    .bind(crate::shared_ai::language(&language))
    .bind(method)
    .fetch_optional(state.events.pool())
    .await?
    .ok_or(AppError::NotFound)?;
    Ok(Json(AiAnalysisResponse::cached(
        crate::ai_analysis::row_to_analysis(row)?,
    )))
}

/// Returns a briefing from the cache, generating only the requested shape on a cache miss.
pub async fn ai_analysis(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Query(query): Query<AiQuery>,
    Extension(token): Extension<TokenId>,
) -> Result<Json<AiAnalysisResponse>, AppError> {
    lazy_ai_analysis(
        &state,
        id,
        query.language.as_deref(),
        query.method,
        &token.0,
    )
    .await
}

/// Looks up or lazily generates a briefing for a client whose local event id differs from the
/// backend id.
pub async fn ai_analysis_by_provider(
    State(state): State<AppState>,
    Query(query): Query<ProviderAiQuery>,
    Extension(token): Extension<TokenId>,
) -> Result<Json<AiAnalysisResponse>, AppError> {
    let event = state
        .events
        .find_provider_event(&query.provider, &query.provider_id)
        .await?
        .ok_or(AppError::NotFound)?;
    lazy_ai_analysis(
        &state,
        event.id,
        query.language.as_deref(),
        query.method,
        &token.0,
    )
    .await
}

async fn lazy_ai_analysis(
    state: &AppState,
    id: i64,
    language: Option<&str>,
    method: Option<u8>,
    caller: &str,
) -> Result<Json<AiAnalysisResponse>, AppError> {
    // Keep already persisted briefings readable even when the model relay is currently disabled.
    match cached_ai_analysis(state, id, language, method).await {
        Ok(response) => return Ok(response),
        Err(AppError::NotFound) => {}
        Err(error) => return Err(error),
    }

    let service = state
        .ai_analysis_service
        .as_ref()
        .ok_or(AppError::NotFound)?;
    let event = state.events.get(id).await?;
    // A post-release briefing must never be generated from a future or unpublished event.
    if event.actual.is_none() {
        return Err(AppError::NotFound);
    }

    if let Err(error) = state.analyses.get(id).await {
        match error {
            AppError::NotFound => {
                state.analysis_service.analyze(id).await?;
            }
            other => return Err(other),
        }
    }

    let language = language_or_default(language);
    let method = method.unwrap_or(state.config.ai.default_method);
    let response = service
        .generate_lazy(
            id,
            &language,
            crate::ai_analysis::AnalysisMethod::from_u8(method),
            "UTC",
            caller,
            &state.quota,
        )
        .await?;
    Ok(Json(response))
}

/// Readers can report a frozen result, but cannot trigger model calls.
pub async fn analysis_feedback(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(token): Extension<TokenId>,
    Json(body): Json<FeedbackRequest>,
) -> Result<Json<serde_json::Value>, AppError> {
    let feedback = crate::shared_ai::submit_feedback(
        state.events.pool(),
        id,
        body.method,
        &body.language,
        body.revision,
        &token.0,
        &body.message,
    )
    .await?;
    Ok(Json(serde_json::json!({"id": feedback})))
}

#[allow(dead_code)]
fn utc(naive: chrono::NaiveDateTime) -> chrono::DateTime<Utc> {
    Utc.from_utc_datetime(&naive)
}
