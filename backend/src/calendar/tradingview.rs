use async_trait::async_trait;
use chrono::{DateTime, SecondsFormat, Utc};
use rust_decimal::Decimal;
use serde_json::Value;

use crate::{
    calendar::{CalendarProvider, parse::release_status},
    error::AppError,
    model::EconomicEvent,
};

/// Keyless TradingView economic calendar. Covers arbitrary date ranges and publishes
/// actual / previous / forecast, so it replaces the paid Trading Economics scrape as the
/// primary source.
pub struct TradingViewProvider {
    client: reqwest::Client,
    url: String,
}

impl TradingViewProvider {
    pub fn new(client: reqwest::Client, url: impl Into<String>) -> Self {
        Self {
            client,
            url: url.into(),
        }
    }
}

#[async_trait]
impl CalendarProvider for TradingViewProvider {
    async fn fetch_events(
        &self,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let response = self
            .client
            .get(&self.url)
            .query(&[
                ("from", start.to_rfc3339_opts(SecondsFormat::Secs, true)),
                (
                    "to",
                    (end - chrono::Duration::seconds(1)).to_rfc3339_opts(SecondsFormat::Secs, true),
                ),
            ])
            .header("accept", "application/json")
            .header("origin", "https://www.tradingview.com")
            .send()
            .await?
            .error_for_status()?;
        let body = response.text().await?;
        parse_events(&body, Utc::now())
    }
}

pub fn parse_events(json: &str, now: DateTime<Utc>) -> Result<Vec<EconomicEvent>, AppError> {
    let payload: Value = serde_json::from_str(json).map_err(|error| {
        AppError::Provider(format!("invalid TradingView calendar payload: {error}"))
    })?;
    let root = payload
        .as_object()
        .ok_or_else(|| AppError::Provider("unexpected TradingView calendar payload".into()))?;
    match root.get("status").and_then(Value::as_str) {
        Some("no_data") => return Ok(Vec::new()),
        Some("ok") => {}
        Some(other) => {
            return Err(AppError::Provider(format!(
                "TradingView calendar status: {other}"
            )));
        }
        None => {
            return Err(AppError::Provider(
                "TradingView calendar status: missing".into(),
            ));
        }
    }
    let rows = match root.get("result").and_then(Value::as_array) {
        Some(rows) => rows,
        None => return Ok(Vec::new()),
    };
    let mut events = Vec::with_capacity(rows.len());
    for row in rows {
        let Some(entry) = row.as_object() else {
            continue;
        };
        let Some(provider_id) = entry.get("id").and_then(scalar_text) else {
            continue;
        };
        let Some(title) = entry
            .get("title")
            .and_then(scalar_text)
            .map(|value| value.trim().to_owned())
            .filter(|value| !value.is_empty())
        else {
            continue;
        };
        let Some(event_time) = entry
            .get("date")
            .and_then(scalar_text)
            .and_then(|value| DateTime::parse_from_rfc3339(&value).ok())
            .map(|value| value.with_timezone(&Utc))
        else {
            continue;
        };
        let country_code = entry
            .get("country")
            .and_then(scalar_text)
            .unwrap_or_else(|| "Unknown".to_owned());
        let country = trading_view_country(&country_code)
            .map(str::to_owned)
            .unwrap_or(country_code);
        let actual = entry.get("actual").and_then(scalar_decimal);
        // TradingView publishes a single market expectation. Storing it as the consensus
        // baseline too keeps the rule engine from classifying every release as neutral.
        let expectation = entry.get("forecast").and_then(scalar_decimal);
        let unit = entry
            .get("unit")
            .and_then(scalar_text)
            .map(|value| value.trim().to_owned())
            .filter(|value| !value.is_empty() && !value.eq_ignore_ascii_case("none"));
        let importance = match entry.get("importance").and_then(Value::as_i64) {
            Some(1) => 3,
            Some(0) => 2,
            _ => 1,
        };
        events.push(EconomicEvent {
            id: 0,
            provider: "trading_view".to_owned(),
            provider_id,
            release_group_id: None,
            country,
            currency: entry.get("currency").and_then(scalar_text),
            category: entry
                .get("indicator")
                .and_then(scalar_text)
                .filter(|value| !value.trim().is_empty())
                .unwrap_or_else(|| title.clone()),
            event: title,
            event_zh_cn: None,
            event_zh_tw: None,
            event_time,
            importance,
            actual,
            previous: entry.get("previous").and_then(scalar_decimal),
            consensus: expectation,
            forecast: expectation,
            unit,
            status: release_status(actual, event_time, now),
            time_exact: true,
        });
    }
    events.sort_by_key(|event| event.event_time);
    events.dedup_by(|left, right| left.provider_id == right.provider_id);
    Ok(events)
}

/// JSON primitives may be strings or numbers depending on the endpoint version.
fn scalar_text(value: &Value) -> Option<String> {
    match value {
        Value::String(text) => Some(text.clone()),
        Value::Number(number) => Some(number.to_string()),
        _ => None,
    }
}

fn scalar_decimal(value: &Value) -> Option<Decimal> {
    match value {
        Value::Number(number) => Decimal::from_str_exact(&number.to_string()).ok(),
        Value::String(text) => crate::calendar::parse::parse_event_number(text),
        _ => None,
    }
}

fn trading_view_country(code: &str) -> Option<&'static str> {
    Some(match code {
        "US" => "United States",
        "EU" => "Euro Area",
        "CN" => "China",
        "JP" => "Japan",
        "GB" => "United Kingdom",
        "DE" => "Germany",
        "FR" => "France",
        "IT" => "Italy",
        "ES" => "Spain",
        "NL" => "Netherlands",
        "PT" => "Portugal",
        "GR" => "Greece",
        "IE" => "Ireland",
        "AT" => "Austria",
        "BE" => "Belgium",
        "FI" => "Finland",
        "LU" => "Luxembourg",
        "MT" => "Malta",
        "CY" => "Cyprus",
        "SK" => "Slovakia",
        "SI" => "Slovenia",
        "EE" => "Estonia",
        "LV" => "Latvia",
        "LT" => "Lithuania",
        "AU" => "Australia",
        "CA" => "Canada",
        "CH" => "Switzerland",
        "NZ" => "New Zealand",
        "KR" => "South Korea",
        "IN" => "India",
        "SG" => "Singapore",
        "HK" => "Hong Kong",
        "TW" => "Taiwan",
        "BR" => "Brazil",
        "MX" => "Mexico",
        "ZA" => "South Africa",
        "TR" => "Turkey",
        "RU" => "Russia",
        "SE" => "Sweden",
        "NO" => "Norway",
        "DK" => "Denmark",
        "PL" => "Poland",
        "CZ" => "Czechia",
        "HU" => "Hungary",
        "RO" => "Romania",
        "IL" => "Israel",
        "SA" => "Saudi Arabia",
        "ID" => "Indonesia",
        "MY" => "Malaysia",
        "TH" => "Thailand",
        "PH" => "Philippines",
        "VN" => "Vietnam",
        "AR" => "Argentina",
        "CL" => "Chile",
        "CO" => "Colombia",
        "PE" => "Peru",
        "EG" => "Egypt",
        "NG" => "Nigeria",
        "UA" => "Ukraine",
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"{
      "status": "ok",
      "result": [
        {"id": 101, "title": "CPI YoY", "country": "US", "currency": "USD",
         "indicator": "inflation", "importance": 1, "date": "2026-09-11T12:30:00Z",
         "actual": 3.2, "forecast": 3.1, "previous": 3.0, "unit": "%"},
        {"id": 102, "title": "All Day Holiday", "country": "JP", "importance": 0,
         "date": "2026-09-12T00:00:00Z", "actual": null, "forecast": null, "previous": null},
        {"id": 103, "title": "No time", "country": "US", "importance": -1}
      ]
    }"#;

    #[test]
    fn parses_calendar_entries_and_maps_importance() {
        let now = DateTime::parse_from_rfc3339("2026-09-11T13:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let events = parse_events(SAMPLE, now).unwrap();
        assert_eq!(events.len(), 2);
        let cpi = events
            .iter()
            .find(|event| event.provider_id == "101")
            .unwrap();
        assert_eq!(cpi.country, "United States");
        assert_eq!(cpi.importance, 3);
        assert_eq!(cpi.consensus, cpi.forecast);
        assert_eq!(cpi.unit.as_deref(), Some("%"));
        assert_eq!(cpi.status, crate::model::EventStatus::Released);
        let holiday = events
            .iter()
            .find(|event| event.provider_id == "102")
            .unwrap();
        assert_eq!(holiday.importance, 2);
        assert_eq!(holiday.status, crate::model::EventStatus::Scheduled);
    }

    #[test]
    fn no_data_is_an_empty_result_not_an_error() {
        let events = parse_events(r#"{"status":"no_data"}"#, Utc::now()).unwrap();
        assert!(events.is_empty());
        assert!(parse_events(r#"{"status":"broken"}"#, Utc::now()).is_err());
        assert!(parse_events("not json", Utc::now()).is_err());
    }
}
