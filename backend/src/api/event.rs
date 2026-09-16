use axum::{
    Extension, Json,
    extract::{Path, Query, State},
};
use chrono::{Duration, NaiveDate, TimeZone, Utc};
use serde::{Deserialize, Serialize};

use crate::{
    AppState,
    ai_analysis::{AiAnalysisResponse, AnalysisMethod},
    auth::TokenId,
    error::AppError,
    model::{AnalysisReport, EconomicEvent, EventObservation},
    quota::QuotaOutcome,
};

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
    timezone: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct AiRequest {
    language: Option<String>,
    method: Option<u8>,
    timezone: Option<String>,
    #[serde(default)]
    regenerate: bool,
}

fn ai_service(
    state: &AppState,
) -> Result<&std::sync::Arc<crate::ai_analysis::AiAnalysisService>, AppError> {
    state.ai_analysis_service.as_ref().ok_or_else(|| {
        AppError::AnalysisUnavailable("AI analysis is not configured on this server".into())
    })
}

fn language_or_default(value: Option<&str>) -> String {
    value.unwrap_or("en").to_owned()
}

/// Returns the cached briefing, or 404 when nothing has been generated for this shape yet.
pub async fn ai_analysis(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Query(query): Query<AiQuery>,
) -> Result<Json<AiAnalysisResponse>, AppError> {
    let service = ai_service(&state)?;
    let language = language_or_default(query.language.as_deref());
    let method = AnalysisMethod::from_u8(query.method.unwrap_or(state.config.ai.default_method));
    let timezone = query.timezone.unwrap_or_else(|| "UTC".to_owned());
    service
        .cached(id, &language, method, &timezone)
        .await?
        .map(AiAnalysisResponse::cached)
        .map(Json)
        .ok_or(AppError::NotFound)
}

/// Generates (or regenerates) the briefing on the server's model key.
///
/// Rate limiting never costs the caller the content it already had: when the daily budget is
/// spent, the previous analysis is returned with `rateLimited: true` instead of a 429. Only a
/// caller with nothing cached receives the quota error.
pub async fn generate_ai_analysis(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Extension(token): Extension<TokenId>,
    Json(body): Json<AiRequest>,
) -> Result<Json<AiAnalysisResponse>, AppError> {
    let service = ai_service(&state)?;
    let language = language_or_default(body.language.as_deref());
    let method = AnalysisMethod::from_u8(body.method.unwrap_or(state.config.ai.default_method));
    let timezone = body.timezone.unwrap_or_else(|| "UTC".to_owned());

    match state
        .quota
        .try_consume(&token.0, "ai_analysis", state.quota.ai_analysis_limit())
        .await?
    {
        QuotaOutcome::Allowed => {
            let _slot = state.quota.acquire_ai_slot().await?;
            Ok(Json(
                service
                    .generate(id, &language, method, &timezone, body.regenerate, &token.0)
                    .await?,
            ))
        }
        QuotaOutcome::Exhausted {
            retry_after_seconds,
        } => match service.cached(id, &language, method, &timezone).await? {
            Some(cached) => {
                tracing::info!(
                    token = %token.0,
                    event_id = id,
                    "AI quota exhausted; serving the cached analysis"
                );
                Ok(Json(AiAnalysisResponse::throttled(
                    cached,
                    retry_after_seconds,
                )))
            }
            None => Err(AppError::QuotaExceeded {
                retry_after_seconds,
            }),
        },
    }
}

#[allow(dead_code)]
fn utc(naive: chrono::NaiveDateTime) -> chrono::DateTime<Utc> {
    Utc.from_utc_datetime(&naive)
}
