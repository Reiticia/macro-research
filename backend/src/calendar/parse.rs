use std::str::FromStr;

use chrono::{DateTime, Utc};
use rust_decimal::Decimal;

use crate::model::EventStatus;

/// Normalizes display values such as `0.2%`, `768B` or `1,234` into a plain decimal.
///
/// The keyless feeds publish numbers as formatted strings; the rule engine compares them
/// numerically, so the unit suffix has to become an actual multiplier.
pub fn parse_event_number(raw: &str) -> Option<Decimal> {
    let mut value = raw.trim().to_owned();
    if value.is_empty() || matches!(value.as_str(), "-" | "--" | "N/A" | "n/a") {
        return None;
    }
    value = value
        .replace(['%', ',', '$', '€', '£'], "")
        .replace('\u{2212}', "-")
        .trim()
        .trim_start_matches(['<', '>'])
        .trim()
        .to_owned();
    if value.is_empty() {
        return None;
    }
    let multiplier = match value.chars().last().map(|c| c.to_ascii_uppercase()) {
        Some('K') => Decimal::from(1_000),
        Some('M') => Decimal::from(1_000_000),
        Some('B') => Decimal::from(1_000_000_000),
        Some('T') => Decimal::from(1_000_000_000_000i64),
        _ => Decimal::ONE,
    };
    if multiplier != Decimal::ONE {
        value = value[..value.len() - 1].trim().to_owned();
    }
    Decimal::from_str(&value)
        .or_else(|_| Decimal::from_scientific(&value))
        .ok()
        .map(|number| (number * multiplier).normalize())
}

/// Best-effort unit of a formatted release value.
pub fn detect_unit(raw: &str) -> Option<String> {
    let value = raw.trim().to_ascii_uppercase();
    if value.contains('%') {
        Some("%".to_owned())
    } else if value.ends_with('K') {
        Some("count".to_owned())
    } else if value.ends_with(['M', 'B', 'T']) {
        Some("currency".to_owned())
    } else {
        None
    }
}

/// Recomputes the time-sensitive status of a release on every read.
pub fn release_status(
    actual: Option<Decimal>,
    event_time: DateTime<Utc>,
    now: DateTime<Utc>,
) -> EventStatus {
    match actual {
        None if event_time <= now => EventStatus::DataUnavailable,
        None => EventStatus::Scheduled,
        Some(_) if event_time < now - chrono::Duration::seconds(86_400) => EventStatus::Historical,
        Some(_) => EventStatus::Released,
    }
}

/// Canonical key identifying "the same occurrence" across the two calendar feeds.
///
/// The weekly fallback uses different titles for the same indicator, so a closed and
/// individually verified alias table maps them onto one key. Guessing here would attach
/// another indicator's value to the row.
pub fn occurrence_key(
    country: &str,
    event_time: DateTime<Utc>,
    event: &str,
) -> (String, DateTime<Utc>, String) {
    (country.to_owned(), event_time, canonical_title(event))
}

fn canonical_title(event: &str) -> String {
    let normalized: String = event
        .to_lowercase()
        .replace("m/m", "mom")
        .replace("y/y", "yoy")
        .replace("q/q", "qoq")
        .chars()
        .filter(|character| character.is_ascii_alphanumeric())
        .collect();
    TITLE_ALIASES
        .iter()
        .find(|(alias, _)| *alias == normalized)
        .map(|(_, canonical)| (*canonical).to_owned())
        .unwrap_or(normalized)
}

/// Titles the two feeds use for the same indicator. Each entry was verified against both
/// feeds for the same country and exact release instant; see `docs/client_architecture.md`.
const TITLE_ALIASES: &[(&str, &str)] = &[
    ("corecpimom", "coreinflationratemom"),
    ("cpimom", "inflationratemom"),
    ("corecpiyoy", "coreinflationrateyoy"),
    ("cpiyoy", "inflationrateyoy"),
    ("crudeoilinventories", "eiacrudeoilstockschange"),
    ("naturalgasstorage", "eianaturalgasstockschange"),
    ("finalwholesaleinventoriesmom", "wholesaleinventoriesmom"),
    (
        "prelimuomconsumersentiment",
        "michiganconsumersentimentprel",
    ),
    (
        "prelimuominflationexpectations",
        "michiganinflationexpectationsprel",
    ),
    ("nfibsmallbusinessindex", "nfibbusinessoptimismindex"),
    ("consumercreditmom", "consumercreditchange"),
    ("adpweeklyemploymentchange", "adpemploymentchangeweekly"),
    ("unemploymentclaims", "initialjoblessclaims"),
];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formatted_values_become_plain_decimals() {
        assert_eq!(parse_event_number("0.2%").unwrap().to_string(), "0.2");
        assert_eq!(
            parse_event_number("768B").unwrap().to_string(),
            "768000000000"
        );
        assert_eq!(parse_event_number("1,234").unwrap().to_string(), "1234");
        assert_eq!(
            parse_event_number("$1.5T").unwrap().to_string(),
            "1500000000000"
        );
        assert_eq!(parse_event_number("−0.3").unwrap().to_string(), "-0.3");
        assert_eq!(parse_event_number(">2.1").unwrap().to_string(), "2.1");
        assert!(parse_event_number("N/A").is_none());
        assert!(parse_event_number("-").is_none());
        assert!(parse_event_number("").is_none());
    }

    #[test]
    fn units_are_detected_only_from_real_suffixes() {
        assert_eq!(detect_unit("0.2%").as_deref(), Some("%"));
        assert_eq!(detect_unit("12K").as_deref(), Some("count"));
        assert_eq!(detect_unit("1.5B").as_deref(), Some("currency"));
        assert_eq!(detect_unit("3.1"), None);
    }

    #[test]
    fn aliases_collapse_the_two_feeds_onto_one_occurrence() {
        let time = Utc::now();
        assert_eq!(
            occurrence_key("United States", time, "Core CPI m/m").2,
            occurrence_key("United States", time, "Core Inflation Rate MoM").2,
        );
        assert_ne!(
            occurrence_key("United States", time, "CPI m/m").2,
            occurrence_key("United States", time, "Core CPI m/m").2,
        );
    }
}
