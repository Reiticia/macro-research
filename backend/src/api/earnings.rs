use crate::{AppState, earnings::EarningsCalendar, error::AppError};
use axum::{
    Json,
    extract::{Path, Query, State},
    http::header,
    response::IntoResponse,
};
use chrono::{NaiveDate, Utc};
use chrono_tz::America::New_York;
use serde::Deserialize;

pub async fn logo(
    State(state): State<AppState>,
    Path(symbol): Path<String>,
) -> Result<impl IntoResponse, AppError> {
    let bytes = state
        .earnings_service
        .logos
        .image(&symbol)
        .await?
        .ok_or(AppError::NotFound)?;
    Ok((
        [
            (header::CONTENT_TYPE, "image/png"),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff"),
        ],
        bytes,
    ))
}

/// Read-only persistent company-price timeline; this endpoint never starts a new collection.
pub async fn stock_market(
    State(state): State<AppState>,
    Path((date, symbol)): Path<(NaiveDate, String)>,
) -> Result<Json<crate::earnings_market::EarningsStockResponse>, AppError> {
    Ok(Json(
        state.earnings_service.stock_market(date, &symbol).await?,
    ))
}

/// Current price, independent of the selected earnings date and persistent sampling window.
pub async fn quote(
    State(state): State<AppState>,
    Path(symbol): Path<String>,
) -> Result<impl IntoResponse, AppError> {
    let service = state
        .earnings_service
        .live_quotes
        .as_ref()
        .ok_or_else(|| AppError::Provider("stock_quote_unavailable".into()))?;
    Ok((
        [(header::CACHE_CONTROL, "no-store")],
        Json(service.quote(&symbol).await?),
    ))
}

#[derive(Deserialize)]
pub struct EarningsQuery {
    date: Option<NaiveDate>,
    #[serde(default)]
    refresh: bool,
}

pub async fn calendar(
    State(state): State<AppState>,
    Query(query): Query<EarningsQuery>,
) -> Result<Json<EarningsCalendar>, AppError> {
    let date = query
        .date
        .unwrap_or_else(|| Utc::now().with_timezone(&New_York).date_naive());
    Ok(Json(
        state.earnings_service.calendar(date, query.refresh).await?,
    ))
}
