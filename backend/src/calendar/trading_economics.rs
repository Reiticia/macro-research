use std::str::FromStr;

use async_trait::async_trait;
use chrono::{DateTime, NaiveDate, NaiveDateTime, NaiveTime, TimeZone, Utc};
use rust_decimal::Decimal;
use scraper::{ElementRef, Html, Selector};

use crate::{
    calendar::CalendarProvider,
    error::AppError,
    model::{EconomicEvent, EventStatus},
};

pub struct TradingEconomicsProvider {
    client: reqwest::Client,
    base_url: String,
}

impl TradingEconomicsProvider {
    pub fn new(client: reqwest::Client, base_url: impl Into<String>) -> Self {
        Self {
            client,
            base_url: base_url.into(),
        }
    }
}

#[async_trait]
impl CalendarProvider for TradingEconomicsProvider {
    async fn fetch_events(
        &self,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let response = self
            .client
            .get(&self.base_url)
            .query(&[
                ("d1", start.format("%Y-%m-%d").to_string()),
                ("d2", end.format("%Y-%m-%d").to_string()),
            ])
            .header("accept", "text/html,application/xhtml+xml")
            .header("cookie", "cal-timezone-offset=0")
            .send()
            .await?
            .error_for_status()?;
        let html = response.text().await?;
        parse_calendar(&html)
    }
}

pub fn parse_calendar(html: &str) -> Result<Vec<EconomicEvent>, AppError> {
    let document = Html::parse_document(html);
    let row_selector = selector("tr[data-event], tr[data-url], tr[data-id]")?;
    let event_selector = selector(".calendar-event, .calendar-item, [data-event]")?;
    let country_selector = selector(".calendar-country, .country")?;
    let actual_selector = selector("#actual, .calendar-actual, [data-field='actual']")?;
    let previous_selector = selector("#previous, .calendar-previous, [data-field='previous']")?;
    let consensus_selector = selector("#consensus, .calendar-consensus, [data-field='consensus']")?;
    let forecast_selector = selector("#forecast, .calendar-forecast, [data-field='forecast']")?;
    let importance_selector = selector(".calendar-importance, .importance")?;
    let first_cell_selector = selector("td:first-child")?;

    let mut events = Vec::new();
    for row in document.select(&row_selector) {
        let event_name = attribute(&row, &["data-event", "data-title"])
            .or_else(|| child_text(&row, &event_selector));
        let Some(event_name) = event_name.filter(|value| !value.is_empty()) else {
            continue;
        };

        let Some(event_time) = attribute(&row, &["data-date", "data-datetime", "data-time"])
            .and_then(|value| parse_datetime(&value))
            .or_else(|| parse_table_datetime(&row, &first_cell_selector))
        else {
            tracing::debug!(event = %event_name, "calendar row has no parseable timestamp");
            continue;
        };

        let country = attribute(&row, &["data-country"])
            .or_else(|| child_text(&row, &country_selector))
            .map(|country| normalize_country(&country))
            .unwrap_or_else(|| "Unknown".to_owned());
        let category = attribute(&row, &["data-category"]).unwrap_or_else(|| event_name.clone());
        let actual_text = cell_value(&row, "data-actual", &actual_selector);
        let previous_text = cell_value(&row, "data-previous", &previous_selector);
        let consensus_text = cell_value(&row, "data-consensus", &consensus_selector);
        let forecast_text = cell_value(&row, "data-forecast", &forecast_selector);
        let parsed_values = [
            &actual_text,
            &previous_text,
            &consensus_text,
            &forecast_text,
        ]
        .map(|value| value.as_deref().and_then(parse_number));
        let unit = [
            &actual_text,
            &consensus_text,
            &previous_text,
            &forecast_text,
        ]
        .into_iter()
        .flatten()
        .find_map(|value| detect_unit(value));

        let importance = attribute(&row, &["data-importance"])
            .and_then(|value| parse_importance(&value))
            .or_else(|| {
                child_text(&row, &importance_selector).and_then(|value| parse_importance(&value))
            })
            .or_else(|| parse_importance_class(&row))
            .unwrap_or(0);
        let provider_id = attribute(&row, &["data-id", "data-event-id", "data-url"])
            .unwrap_or_else(|| format!("{}|{}|{}", country, event_time.timestamp(), event_name));

        events.push(EconomicEvent {
            id: 0,
            provider: "trading_economics".to_owned(),
            provider_id,
            release_group_id: None,
            country: country.clone(),
            currency: attribute(&row, &["data-currency"]).or_else(|| country_currency(&country)),
            category,
            event: event_name,
            event_zh_cn: None,
            event_zh_tw: None,
            event_time,
            importance,
            actual: parsed_values[0],
            previous: parsed_values[1],
            consensus: parsed_values[2],
            forecast: parsed_values[3],
            unit,
            status: EventStatus::Scheduled,
            time_exact: true,
        });
    }

    if events.is_empty() && html.contains("calendar") {
        tracing::warn!("no economic events found; the provider DOM may have changed");
    }
    Ok(events)
}

fn selector(value: &str) -> Result<Selector, AppError> {
    Selector::parse(value).map_err(|error| AppError::Internal(error.to_string()))
}

fn attribute(element: &ElementRef<'_>, names: &[&str]) -> Option<String> {
    names
        .iter()
        .find_map(|name| element.value().attr(name))
        .map(clean_text)
}

fn child_text(element: &ElementRef<'_>, selector: &Selector) -> Option<String> {
    element
        .select(selector)
        .next()
        .map(|element| clean_text(&element.text().collect::<Vec<_>>().join(" ")))
}

fn cell_value(
    element: &ElementRef<'_>,
    attribute_name: &str,
    selector: &Selector,
) -> Option<String> {
    attribute(element, &[attribute_name]).or_else(|| child_text(element, selector))
}

fn clean_text(value: &str) -> String {
    value
        .replace('\u{a0}', " ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .trim()
        .to_owned()
}

fn parse_datetime(value: &str) -> Option<DateTime<Utc>> {
    let value = value.trim();
    if let Ok(parsed) = DateTime::parse_from_rfc3339(value) {
        return Some(parsed.with_timezone(&Utc));
    }
    for format in [
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%d %H:%M",
        "%Y/%m/%d %H:%M:%S",
        "%Y-%m-%dT%H:%M:%S",
    ] {
        if let Ok(parsed) = NaiveDateTime::parse_from_str(value, format) {
            return Some(Utc.from_utc_datetime(&parsed));
        }
    }
    None
}

fn parse_table_datetime(
    row: &ElementRef<'_>,
    first_cell_selector: &Selector,
) -> Option<DateTime<Utc>> {
    let cell = row.select(first_cell_selector).next()?;
    let class = cell.value().attr("class")?;
    let date = class
        .split_whitespace()
        .find_map(|token| NaiveDate::parse_from_str(token, "%Y-%m-%d").ok())?;
    let text = clean_text(&cell.text().collect::<Vec<_>>().join(" "));
    let time = ["%I:%M %p", "%H:%M"]
        .into_iter()
        .find_map(|format| NaiveTime::parse_from_str(&text, format).ok())?;
    Some(Utc.from_utc_datetime(&date.and_time(time)))
}

fn parse_importance_class(row: &ElementRef<'_>) -> Option<u8> {
    let selector = Selector::parse("span[class*='calendar-date-']").ok()?;
    let class = row.select(&selector).next()?.value().attr("class")?;
    class.split_whitespace().find_map(|token| {
        token
            .strip_prefix("calendar-date-")
            .and_then(|value| value.parse::<u8>().ok())
            .map(|value| value.min(3))
    })
}

pub(crate) fn parse_number(value: &str) -> Option<Decimal> {
    let mut value = clean_text(value);
    if value.is_empty() || matches!(value.as_str(), "-" | "--" | "N/A") {
        return None;
    }
    value = value
        .replace(['%', ',', '$', '€', '£'], "")
        .replace('−', "-");
    let multiplier = match value.chars().last()?.to_ascii_uppercase() {
        'K' => {
            value.pop();
            Decimal::from(1_000)
        }
        'M' => {
            value.pop();
            Decimal::from(1_000_000)
        }
        'B' => {
            value.pop();
            Decimal::from(1_000_000_000)
        }
        'T' => {
            value.pop();
            Decimal::from(1_000_000_000_000_i64)
        }
        _ => Decimal::ONE,
    };
    let value = value.trim().trim_start_matches('<').trim_start_matches('>');
    Decimal::from_str(value)
        .ok()
        .map(|number| number * multiplier)
}

fn detect_unit(value: &str) -> Option<String> {
    let upper = value.trim().to_ascii_uppercase();
    if upper.contains('%') {
        Some("%".to_owned())
    } else if upper.ends_with('K') {
        Some("count".to_owned())
    } else if upper.ends_with('M') || upper.ends_with('B') || upper.ends_with('T') {
        Some("currency".to_owned())
    } else {
        None
    }
}

fn parse_importance(value: &str) -> Option<u8> {
    if let Ok(number) = value.trim().parse::<u8>() {
        return Some(number.min(3));
    }
    let lower = value.to_ascii_lowercase();
    if lower.contains("high") {
        Some(3)
    } else if lower.contains("medium") {
        Some(2)
    } else if lower.contains("low") {
        Some(1)
    } else {
        let stars = value.matches('★').count() as u8;
        (stars > 0).then_some(stars.min(3))
    }
}

fn country_currency(country: &str) -> Option<String> {
    let currency = match country.trim().to_ascii_lowercase().as_str() {
        "united states" | "us" | "usa" => "USD",
        "euro area" | "european union" => "EUR",
        "united kingdom" | "uk" => "GBP",
        "japan" => "JPY",
        "australia" => "AUD",
        "canada" => "CAD",
        "new zealand" => "NZD",
        "switzerland" => "CHF",
        "china" => "CNY",
        _ => return None,
    };
    Some(currency.to_owned())
}

fn normalize_country(country: &str) -> String {
    match country.trim().to_ascii_lowercase().as_str() {
        "us" | "usa" | "united states" => "United States".to_owned(),
        "uk" | "united kingdom" => "United Kingdom".to_owned(),
        "opec" => "OPEC".to_owned(),
        value => value
            .split_whitespace()
            .map(|word| {
                let mut characters = word.chars();
                characters
                    .next()
                    .map(|first| first.to_uppercase().collect::<String>() + characters.as_str())
                    .unwrap_or_default()
            })
            .collect::<Vec<_>>()
            .join(" "),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_calendar_rows_into_owned_domain_models() {
        let html = r#"
            <table id="calendar"><tbody>
              <tr data-id="us-cpi-202609" data-event="Inflation Rate YoY"
                  data-country="United States" data-category="Inflation"
                  data-date="2026-09-10T12:30:00Z" data-importance="3">
                <td class="calendar-actual">3.2%</td>
                <td class="calendar-previous">2.8%</td>
                <td class="calendar-consensus">2.9%</td>
                <td class="calendar-forecast">3.0%</td>
              </tr>
            </tbody></table>"#;
        let events = parse_calendar(html).unwrap();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0].provider_id, "us-cpi-202609");
        assert_eq!(events[0].actual, Some(Decimal::new(32, 1)));
        assert_eq!(events[0].importance, 3);
        assert_eq!(events[0].currency.as_deref(), Some("USD"));
        assert_eq!(events[0].unit.as_deref(), Some("%"));
    }

    #[test]
    fn normalizes_scaled_numbers() {
        assert_eq!(parse_number("263K"), Some(Decimal::from(263_000)));
        assert_eq!(parse_number("-1.2M"), Some(Decimal::from(-1_200_000)));
        assert_eq!(parse_number("--"), None);
    }

    #[test]
    fn parses_current_trading_economics_table_shape() {
        let html = r#"<table><tbody>
          <tr data-url="/japan/foreign-exchange-reserves" data-id="403508"
              data-country="japan" data-category="foreign exchange reserves"
              data-event="foreign exchange reserves">
            <td class="2026-09-06"><span class="event-0 calendar-date-3">11:50 PM</span></td>
            <td class="calendar-item"><span>JP</span></td>
            <td><a class="calendar-event">Foreign Exchange Reserves</a></td>
            <td><span id="actual">$1207.5B</span></td>
            <td><span id="previous">$1287.1B</span><span id="revised"></span></td>
            <td><span id="consensus">$1250B</span></td>
            <td><span id="forecast">$1240B</span></td>
          </tr></tbody></table>"#;
        let events = parse_calendar(html).unwrap();
        assert_eq!(events.len(), 1);
        assert_eq!(
            events[0].event_time.to_rfc3339(),
            "2026-09-06T23:50:00+00:00"
        );
        assert_eq!(events[0].importance, 3);
        assert_eq!(events[0].actual, Some(Decimal::from(1_207_500_000_000_i64)));
        assert_eq!(events[0].country, "Japan");
    }
}
