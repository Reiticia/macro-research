use std::collections::BTreeMap;

use async_trait::async_trait;
use chrono::{DateTime, Duration, SecondsFormat, Utc};
use rust_decimal::Decimal;
use serde::Deserialize;

use crate::{
    calendar::{CalendarProvider, parse::release_status, tradingview::trading_view_country},
    error::AppError,
    model::EconomicEvent,
};

const PAGE_LIMIT: usize = 500;

/// Keyless calendar: https://biquote.io/docs/#calendar. The upstream has no offset
/// pagination and silently caps arrays, so request days and bisect saturated windows.
pub struct BiquoteCalendarProvider {
    client: reqwest::Client,
    url: String,
}

impl BiquoteCalendarProvider {
    pub fn new(client: reqwest::Client, url: impl Into<String>) -> Self {
        Self {
            client,
            url: url.into(),
        }
    }
}

#[async_trait]
impl CalendarProvider for BiquoteCalendarProvider {
    async fn fetch_events(
        &self,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let mut events = BTreeMap::new();
        let mut cursor = start;
        let mut requests = 0;
        let now = Utc::now();
        while cursor < end {
            let day_end = (cursor + Duration::days(1)).min(end);
            let mut windows = vec![(cursor, day_end)];
            while let Some((from, to)) = windows.pop() {
                requests += 1;
                if requests > 512 {
                    return Err(AppError::Provider(
                        "BiQuote calendar request budget exceeded".into(),
                    ));
                }
                let rows: Vec<CalendarRow> = self
                    .client
                    .get(&self.url)
                    .query(&[
                        ("from", from.to_rfc3339_opts(SecondsFormat::Millis, true)),
                        ("to", to.to_rfc3339_opts(SecondsFormat::Millis, true)),
                        ("limit", PAGE_LIMIT.to_string()),
                    ])
                    .header("accept", "application/json")
                    .send()
                    .await?
                    .error_for_status()?
                    .json()
                    .await?;
                if rows.len() >= PAGE_LIMIT {
                    let milliseconds = (to - from).num_milliseconds();
                    if milliseconds <= 1_000 {
                        return Err(AppError::Provider(
                            "BiQuote calendar row cap reached; refusing a truncated range".into(),
                        ));
                    }
                    let middle = from + Duration::milliseconds(milliseconds / 2);
                    windows.push((middle, to));
                    windows.push((from, middle));
                    continue;
                }
                for row in rows {
                    let event = row.into_event(now)?;
                    // Both endpoints are accepted by the upstream; use half-open ranges here.
                    if event.event_time >= from && event.event_time < to {
                        events.insert(event.provider_id.clone(), event);
                    }
                }
            }
            cursor = day_end;
        }
        let mut events: Vec<_> = events.into_values().collect();
        events.sort_by_key(|event| event.event_time);
        Ok(events)
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CalendarRow {
    id: String,
    time: DateTime<Utc>,
    country_code: String,
    currency: Option<String>,
    name: String,
    importance: Option<String>,
    sector: Option<String>,
    unit: Option<String>,
    multiplier: Option<String>,
    actual: Option<serde_json::Number>,
    forecast: Option<serde_json::Number>,
    previous: Option<serde_json::Number>,
    revised_previous: Option<serde_json::Number>,
    time_mode: Option<String>,
}

impl CalendarRow {
    fn into_event(self, now: DateTime<Utc>) -> Result<EconomicEvent, AppError> {
        if self.id.trim().is_empty()
            || self.name.trim().is_empty()
            || self.country_code.trim().is_empty()
        {
            return Err(AppError::Provider(
                "invalid BiQuote calendar identity".into(),
            ));
        }
        let actual = decimal(self.actual)?;
        let expectation = decimal(self.forecast)?;
        let country_code = self.country_code.trim().to_ascii_uppercase();
        let unit = display_unit(
            self.unit.as_deref(),
            self.multiplier.as_deref(),
            self.currency.as_deref(),
        )
        .or_else(|| {
            // Closed, exact-name list observed in public responses: BiQuote publishes diffusion
            // indices with unit/multiplier "none". Only these two ISM series have a rule.
            ISM_DIFFUSION_INDICES
                .contains(&self.name.trim())
                .then(|| "index points".to_owned())
        });
        Ok(EconomicEvent {
            id: 0,
            provider: "biquote".into(),
            // `id` is an occurrence; `eventId` is a series and must NOT be the upsert key.
            provider_id: self.id,
            release_group_id: None,
            country: trading_view_country(&country_code)
                .unwrap_or(&country_code)
                .to_owned(),
            currency: self.currency,
            category: self
                .sector
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| self.name.clone()),
            event: self.name.trim().to_owned(),
            event_zh_cn: None,
            event_zh_tw: None,
            event_time: self.time,
            importance: match self.importance.as_deref() {
                Some("high") => 3,
                Some("medium") => 2,
                _ => 1,
            },
            actual,
            previous: decimal(self.revised_previous.or(self.previous))?,
            consensus: expectation,
            forecast: expectation,
            unit,
            status: release_status(actual, self.time, now),
            time_exact: self.time_mode.as_deref() == Some("exact"),
        })
    }
}

/// Exact BiQuote names of the ISM diffusion indices that carry a release-impact rule.
const ISM_DIFFUSION_INDICES: &[&str] = &["ISM Manufacturing PMI", "ISM Non-Manufacturing PMI"];

fn decimal(value: Option<serde_json::Number>) -> Result<Option<Decimal>, AppError> {
    value
        .map(|number| {
            let text = number.to_string();
            Decimal::from_str_exact(&text)
                .or_else(|_| Decimal::from_scientific(&text))
                .map(|value| value.normalize())
                .map_err(|_| AppError::Provider("invalid BiQuote calendar number".into()))
        })
        .transpose()
}

/// Values are already display-scaled; preserve them and attach the explicit scale once.
fn display_unit(
    unit: Option<&str>,
    multiplier: Option<&str>,
    currency: Option<&str>,
) -> Option<String> {
    let scale = match multiplier.unwrap_or("none") {
        "none" | "" => "",
        "thousands" => "K",
        "millions" => "M",
        "billions" => "B",
        "trillions" => "T",
        other => other,
    };
    let dimension = match unit.unwrap_or("none") {
        "none" | "" => "",
        "percent" => "%",
        "job" => "jobs",
        "barrel" => "barrels",
        "usd" => "USD",
        "currency" => currency.filter(|c| !c.is_empty()).unwrap_or("currency"),
        other => other,
    };
    let unit = [scale, dimension]
        .into_iter()
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join(" ");
    (!unit.is_empty()).then_some(unit)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shared_fixture_preserves_occurrences_values_units_and_time_precision() {
        let rows: Vec<CalendarRow> = serde_json::from_str(include_str!(
            "../../../android/app/src/test/resources/calendar-units/biquote.json"
        ))
        .unwrap();
        let now = "2026-10-28T16:00:00Z".parse().unwrap();
        let events: std::collections::HashMap<_, _> = rows
            .into_iter()
            .map(|r| {
                let event = r.into_event(now).unwrap();
                (event.provider_id.clone(), event)
            })
            .collect();
        assert_eq!(events.len(), 7);
        let cpi = &events["mql5:302115"];
        assert_eq!(cpi.country, "Australia");
        assert_eq!(cpi.actual.unwrap().to_string(), "1.3");
        assert_eq!(cpi.consensus, cpi.forecast);
        assert_eq!(cpi.unit.as_deref(), Some("%"));
        // A published zero is a value, not a missing release.
        assert_eq!(events["mql5:zero"].actual.unwrap().to_string(), "0");
        assert_eq!(events["mql5:zero"].previous.unwrap().to_string(), "1");
        assert_eq!(events["mql5:299659"].unit.as_deref(), Some("K jobs"));
        assert_eq!(
            events["mql5:299659"].forecast.unwrap().to_string(),
            "3104.4"
        );
        assert_eq!(events["mql5:currency"].unit.as_deref(), Some("B USD"));
        let tentative = &events["fxempire:tentative"];
        assert!(!tentative.time_exact);
        assert!(tentative.actual.is_none());
        assert!(tentative.unit.is_none());
        // Diffusion indices arrive with unit/multiplier "none"; only the exact ISM names get a
        // dimension, and a look-alike regional index is left alone.
        assert_eq!(events["mql5:ism"].event, "ISM Manufacturing PMI");
        assert_eq!(events["mql5:ism"].unit.as_deref(), Some("index points"));
        assert!(events["mql5:richmond"].unit.is_none());
    }
}
