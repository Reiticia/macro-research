use axum::{
    Json,
    extract::{Path, Query, State},
};
use chrono::{Duration, NaiveDate};
use serde::{Deserialize, Serialize};

use crate::{
    AppState,
    error::AppError,
    event_description::{self, EventDescription},
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
    description: Option<EventDescription>,
}

async fn detail_for(state: &AppState, id: i64) -> Result<EventDetail, AppError> {
    let event = state.events.get(id).await?;
    let observations = state.events.observations(id).await?;
    let description = match state.events.description(id).await? {
        Some(description) => Some(description),
        None => {
            let description = event_description::for_event(&event);
            state.events.save_description(id, &description).await?;
            Some(description)
        }
    };
    Ok(EventDetail {
        event,
        observations,
        description,
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
    /// Exact importance levels joined with commas; omitted includes all levels, even unrated.
    importance: Option<String>,
    limit: Option<u32>,
    offset: Option<u32>,
    from: Option<NaiveDate>,
    to: Option<NaiveDate>,
}

pub async fn history(
    State(state): State<AppState>,
    Query(query): Query<HistoryQuery>,
) -> Result<Json<Vec<EconomicEvent>>, AppError> {
    let importance = query
        .importance
        .as_deref()
        .map(|value| {
            value
                .split(',')
                .map(|part| {
                    part.trim()
                        .parse::<u8>()
                        .ok()
                        .filter(|level| *level <= 3)
                        .ok_or_else(|| {
                            AppError::InvalidRequest(
                                "importance must be one or more levels between 0 and 3".into(),
                            )
                        })
                })
                .collect::<Result<Vec<_>, _>>()
        })
        .transpose()?;
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
                importance,
            )
            .await?,
    ))
}
