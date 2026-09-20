//! Authenticated historical calendar. The public HTML page may ignore date filters;
//! never use it as a historical fallback.
use async_trait::async_trait;
use chrono::{DateTime, NaiveDateTime, Utc};
use serde_json::Value;

use crate::{
    calendar::{CalendarProvider, trading_economics::parse_number},
    error::AppError,
    model::{EconomicEvent, EventStatus},
};

pub struct TradingEconomicsApiProvider {
    client: reqwest::Client,
    base_url: String,
    api_key: String,
}

impl TradingEconomicsApiProvider {
    pub fn new(client: reqwest::Client, base_url: &str, api_key: String) -> Result<Self, AppError> {
        if api_key.trim().is_empty() {
            return Err(AppError::Config(
                "TE_API_KEY is required for historical calendars".into(),
            ));
        }
        Ok(Self {
            client,
            base_url: base_url.trim_end_matches('/').into(),
            api_key,
        })
    }
}

#[async_trait]
impl CalendarProvider for TradingEconomicsApiProvider {
    async fn fetch_events(
        &self,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        // Backfill asks for exactly one UTC day. Date endpoints are inclusive.
        let url = format!(
            "{}/calendar/country/All/{}/{}",
            self.base_url,
            start.format("%Y-%m-%d"),
            start.format("%Y-%m-%d")
        );
        let response = self
            .client
            .get(url)
            .query(&[
                ("c", self.api_key.as_str()),
                ("f", "json"),
                ("limit", "1000"),
            ])
            .send()
            .await
            .map_err(|e| AppError::Http(e.without_url()))?
            .error_for_status()
            .map_err(|e| AppError::Http(e.without_url()))?;
        // Never expose a credential-bearing request URL in logs or job errors.
        let rows: Vec<Value> = response
            .json()
            .await
            .map_err(|e| AppError::Http(e.without_url()))?;
        if rows.len() >= 1000 {
            return Err(AppError::Provider("calendar response reached the provider row cap; refusing a potentially truncated day".into()));
        }
        let mut events = Vec::new();
        for row in rows {
            let event = parse_event(&row)?;
            if event.event_time < start || event.event_time >= end {
                return Err(AppError::Provider("calendar returned an event outside the requested UTC day; no rows were imported".into()));
            }
            // DateSpan != 0 means an approximate/all-day time, unsuitable for
            // intraday attribution. Retain the calendar row, but flag its time.
            events.push(event);
        }
        Ok(events)
    }
}

fn text(row: &Value, key: &str) -> Option<String> {
    match row.get(key)? {
        Value::String(s) if !s.trim().is_empty() => Some(s.trim().to_owned()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

fn parse_event(row: &Value) -> Result<EconomicEvent, AppError> {
    let required = |key| {
        text(row, key).ok_or_else(|| AppError::Provider(format!("calendar row missing {key}")))
    };
    let date = required("Date")?;
    let event_time = DateTime::parse_from_rfc3339(&date)
        .map(|d| d.with_timezone(&Utc))
        .or_else(|_| {
            NaiveDateTime::parse_from_str(&date, "%Y-%m-%dT%H:%M:%S%.f").map(|d| d.and_utc())
        })
        .map_err(|_| AppError::Provider("invalid calendar UTC timestamp".into()))?;
    let number = |key| text(row, key).as_deref().and_then(parse_number);
    let actual = text(row, "Actual");
    let unit = text(row, "Unit").filter(|s| s == "%").or_else(|| {
        ["Actual", "Previous", "Forecast", "TEForecast"]
            .into_iter()
            .filter_map(|key| text(row, key))
            .any(|s| s.contains('%'))
            .then(|| "%".into())
    });
    let date_span = text(row, "DateSpan").and_then(|s| s.parse::<u8>().ok());
    Ok(EconomicEvent {
        id: 0,
        provider: "trading_economics".into(),
        provider_id: required("CalendarId")?,
        release_group_id: None,
        country: required("Country")?,
        currency: text(row, "Currency"),
        category: required("Category")?,
        event: required("Event")?,
        event_zh_cn: None,
        event_zh_tw: None,
        event_time,
        importance: text(row, "Importance")
            .and_then(|s| s.parse::<u8>().ok())
            .unwrap_or(0)
            .min(3),
        actual: actual.as_deref().and_then(parse_number),
        previous: number("Previous"),
        // TE Forecast = consensus, TEForecast = the provider's own model forecast.
        consensus: number("Forecast"),
        forecast: number("TEForecast"),
        unit,
        status: EventStatus::Historical,
        time_exact: date_span == Some(0),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use rust_decimal::Decimal;

    #[test]
    fn maps_consensus_and_provider_forecast_separately() {
        let event = parse_event(&serde_json::json!({
            "CalendarId": 123, "Date": "2026-06-08T12:30:00.000", "Country": "United States",
            "Category": "Inflation", "Event": "CPI", "Actual": "3.2%", "Previous": "3.0%",
            "Forecast": "3.1%", "TEForecast": "3.3%", "Importance": 3, "DateSpan": 0
        }))
        .unwrap();
        assert_eq!(event.provider_id, "123");
        assert_eq!(event.consensus, Some(Decimal::new(31, 1)));
        assert_eq!(event.forecast, Some(Decimal::new(33, 1)));
        assert_eq!(event.unit.as_deref(), Some("%"));
        assert!(event.time_exact);
        assert_eq!(event.status, EventStatus::Historical);
    }

    #[test]
    fn unknown_time_is_not_assumed_exact_and_bad_rows_fail_closed() {
        let mut row = serde_json::json!({"CalendarId": "1", "Date": "2026-06-08T00:00:00Z",
            "Country": "Japan", "Category": "Holiday", "Event": "Holiday"});
        assert!(!parse_event(&row).unwrap().time_exact);
        row["Date"] = "not a date".into();
        assert!(parse_event(&row).is_err());
    }
}
