use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde_json::Value;

use crate::{
    calendar::{
        CalendarProvider,
        parse::{detect_unit, parse_event_number, release_status},
    },
    error::AppError,
    model::EconomicEvent,
};

/// Forex Factory weekly JSON. Keyless, but only covers the current week and publishes a
/// single expectation, so it is a degraded fallback rather than a full replacement.
pub struct ForexFactoryProvider {
    client: reqwest::Client,
    url: String,
}

impl ForexFactoryProvider {
    pub fn new(client: reqwest::Client, url: impl Into<String>) -> Self {
        Self {
            client,
            url: url.into(),
        }
    }
}

#[async_trait]
impl CalendarProvider for ForexFactoryProvider {
    async fn fetch_events(
        &self,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let response = self
            .client
            .get(&self.url)
            .header("accept", "application/json")
            .send()
            .await?;
        if response.status() == reqwest::StatusCode::TOO_MANY_REQUESTS {
            return Err(AppError::Provider("rate_limited".into()));
        }
        let body = response.error_for_status()?.text().await?;
        parse_events(&body, start, end, Utc::now())
    }
}

pub fn parse_events(
    json: &str,
    start: DateTime<Utc>,
    end: DateTime<Utc>,
    now: DateTime<Utc>,
) -> Result<Vec<EconomicEvent>, AppError> {
    let payload: Value = serde_json::from_str(json)
        .map_err(|error| AppError::Provider(format!("invalid Forex Factory payload: {error}")))?;
    let rows = payload
        .as_array()
        .ok_or_else(|| AppError::Provider("unexpected Forex Factory payload".into()))?;
    let mut events = Vec::new();
    for row in rows {
        let Some(entry) = row.as_object() else {
            continue;
        };
        let title = entry
            .get("title")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|value| !value.is_empty());
        let Some(title) = title else { continue };
        let date_text = entry.get("date").and_then(Value::as_str);
        let Some(date_text) = date_text else { continue };
        let Some(event_time) = DateTime::parse_from_rfc3339(date_text)
            .ok()
            .map(|value| value.with_timezone(&Utc))
        else {
            continue;
        };
        if event_time < start || event_time >= end {
            continue;
        }
        let currency = entry
            .get("country")
            .and_then(Value::as_str)
            .map(|value| value.trim().to_uppercase())
            .filter(|value| !value.is_empty())
            .unwrap_or_else(|| "Unknown".to_owned());
        let actual_text = entry.get("actual").and_then(Value::as_str).unwrap_or("");
        let forecast_text = entry.get("forecast").and_then(Value::as_str).unwrap_or("");
        let previous_text = entry.get("previous").and_then(Value::as_str).unwrap_or("");
        let actual = non_empty(actual_text).and_then(parse_event_number);
        // The weekly feed also exposes one expectation value; use it as the consensus baseline.
        let expectation = non_empty(forecast_text).and_then(parse_event_number);
        let unit = [actual_text, forecast_text, previous_text]
            .into_iter()
            .filter_map(non_empty)
            .find_map(detect_unit);
        events.push(EconomicEvent {
            id: 0,
            provider: "forex_factory".to_owned(),
            provider_id: format!("{currency}|{date_text}|{title}"),
            release_group_id: None,
            country: currencies_by_feed(&currency)
                .unwrap_or(&currency)
                .to_owned(),
            currency: (currency != "Unknown").then(|| currency.clone()),
            category: title.to_owned(),
            event: title.to_owned(),
            event_zh_cn: None,
            event_zh_tw: None,
            event_time,
            importance: match entry
                .get("impact")
                .and_then(Value::as_str)
                .map(|value| value.trim().to_ascii_lowercase())
                .as_deref()
            {
                Some("high") => 3,
                Some("medium") => 2,
                _ => 1,
            },
            actual,
            previous: non_empty(previous_text).and_then(parse_event_number),
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

fn non_empty(value: &str) -> Option<&str> {
    let trimmed = value.trim();
    (!trimmed.is_empty()).then_some(trimmed)
}

fn currencies_by_feed(code: &str) -> Option<&'static str> {
    Some(match code {
        "USD" => "United States",
        "EUR" => "Euro Area",
        "CNY" => "China",
        "JPY" => "Japan",
        "GBP" => "United Kingdom",
        "AUD" => "Australia",
        "CAD" => "Canada",
        "CHF" => "Switzerland",
        "NZD" => "New Zealand",
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r#"[
      {"title":"Core CPI m/m","country":"USD","date":"2026-09-11T08:30:00-04:00",
       "impact":"High","forecast":"0.2%","previous":"0.1%","actual":"0.3%"},
      {"title":"Outside Range","country":"USD","date":"2026-10-01T08:30:00-04:00",
       "impact":"Low","forecast":"1","previous":"1","actual":""},
      {"title":"Holiday","country":"JPY","date":"2026-09-11T00:00:00-04:00","impact":"Holiday"}
    ]"#;

    #[test]
    fn parses_and_filters_the_weekly_feed() {
        let start = DateTime::parse_from_rfc3339("2026-09-11T00:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let end = start + chrono::Duration::days(1);
        let now = start;
        let events = parse_events(SAMPLE, start, end, now).unwrap();
        assert_eq!(events.len(), 2);
        let cpi = events
            .iter()
            .find(|event| event.event.starts_with("Core CPI"))
            .unwrap();
        assert_eq!(cpi.country, "United States");
        assert_eq!(cpi.importance, 3);
        assert_eq!(cpi.actual.as_ref().unwrap().to_string(), "0.3");
        assert_eq!(cpi.consensus.as_ref().unwrap().to_string(), "0.2");
        assert_eq!(cpi.unit.as_deref(), Some("%"));
        assert_eq!(cpi.status, crate::model::EventStatus::Released);
        let holiday = events
            .iter()
            .find(|event| event.event == "Holiday")
            .unwrap();
        assert_eq!(holiday.country, "Japan");
        assert_eq!(holiday.importance, 1);
        assert_eq!(holiday.status, crate::model::EventStatus::Scheduled);
    }
}
