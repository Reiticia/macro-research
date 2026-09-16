mod analysis;
mod event;
mod market;

pub use analysis::AnalysisRepository;
pub use event::EventRepository;
pub use market::MarketRepository;

use std::str::FromStr;

use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use sqlx::{Row, sqlite::SqliteRow};

use crate::{
    error::AppError,
    model::{EconomicEvent, EventStatus},
};

pub(crate) fn decimal_from_row(row: &SqliteRow, column: &str) -> Result<Option<Decimal>, AppError> {
    let value: Option<String> = row.try_get(column)?;
    value
        .map(|raw| Decimal::from_str(&raw).map_err(|error| AppError::Internal(error.to_string())))
        .transpose()
}

pub(crate) fn datetime_from_row(row: &SqliteRow, column: &str) -> Result<DateTime<Utc>, AppError> {
    let value: String = row.try_get(column)?;
    DateTime::parse_from_rfc3339(&value)
        .map(|date| date.with_timezone(&Utc))
        .map_err(|error| AppError::Internal(format!("invalid database timestamp {value}: {error}")))
}

pub(crate) fn event_from_row(row: SqliteRow) -> Result<EconomicEvent, AppError> {
    let status: String = row.try_get("status")?;
    Ok(EconomicEvent {
        id: row.try_get("id")?,
        provider: row.try_get("provider")?,
        provider_id: row.try_get("provider_id")?,
        release_group_id: row.try_get("release_group_id")?,
        country: row.try_get("country")?,
        currency: row.try_get("currency")?,
        category: row.try_get("category")?,
        event: row.try_get("event")?,
        event_zh_cn: row.try_get("event_zh_cn")?,
        event_zh_tw: row.try_get("event_zh_tw")?,
        event_time: datetime_from_row(&row, "event_time")?,
        importance: row.try_get::<i64, _>("importance")? as u8,
        actual: decimal_from_row(&row, "actual")?,
        previous: decimal_from_row(&row, "previous")?,
        consensus: decimal_from_row(&row, "consensus")?,
        forecast: decimal_from_row(&row, "forecast")?,
        unit: row.try_get("unit")?,
        status: EventStatus::from_str(&status).map_err(AppError::Internal)?,
        time_exact: row.try_get("time_exact")?,
    })
}
