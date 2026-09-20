use axum::{
    Json,
    extract::{Query, State},
};
use chrono::{DateTime, Duration, NaiveDate, TimeZone, Utc};
use serde::Deserialize;

use crate::{AppState, error::AppError, model::EconomicEvent};

#[derive(Debug, Deserialize)]
pub struct UpcomingQuery {
    days: Option<i64>,
}

pub async fn upcoming(
    State(state): State<AppState>,
    Query(query): Query<UpcomingQuery>,
) -> Result<Json<Vec<EconomicEvent>>, AppError> {
    let days = query.days.unwrap_or(7).clamp(1, 31);
    Ok(Json(state.events.upcoming(Utc::now(), days).await?))
}

#[derive(Debug, Deserialize)]
pub struct CalendarQuery {
    from: String,
    to: String,
    country: Option<String>,
    minimum_importance: Option<u8>,
}

pub async fn calendar(
    State(state): State<AppState>,
    Query(query): Query<CalendarQuery>,
) -> Result<Json<Vec<EconomicEvent>>, AppError> {
    let from = parse_boundary(&query.from, false)?;
    let to = parse_boundary(&query.to, true)?;
    if to < from || to - from > Duration::days(90) {
        return Err(AppError::InvalidRequest(
            "date range must be ordered and no longer than 90 days".to_owned(),
        ));
    }
    Ok(Json(
        state
            .events
            .calendar(from, to, query.country.as_deref(), query.minimum_importance)
            .await?,
    ))
}

fn parse_boundary(value: &str, end_of_day: bool) -> Result<DateTime<Utc>, AppError> {
    if let Ok(value) = DateTime::parse_from_rfc3339(value) {
        return Ok(value.with_timezone(&Utc));
    }
    let date = NaiveDate::parse_from_str(value, "%Y-%m-%d")
        .map_err(|_| AppError::InvalidRequest(format!("invalid date: {value}")))?;
    let time = if end_of_day {
        date.and_hms_opt(23, 59, 59)
    } else {
        date.and_hms_opt(0, 0, 0)
    }
    .ok_or_else(|| AppError::InvalidRequest(format!("invalid date: {value}")))?;
    Ok(Utc.from_utc_datetime(&time))
}
