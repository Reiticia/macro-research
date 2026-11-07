//! Source-specific identities verified against the 2026-11-06 upstream calendar responses.
//! Never strip phase suffixes or infer an identity from forecast/previous/Actual equality.
use chrono::{DateTime, NaiveDate};
use rust_decimal::Decimal;

use crate::model::EconomicEvent;

#[derive(Clone, Copy, PartialEq, Eq)]
struct Identity {
    metric: &'static str,
    phase: Option<&'static str>,
    percent: bool,
}

fn identity(event: &EconomicEvent) -> Option<Identity> {
    let (metric, phase, percent) = match (
        event.provider.as_str(),
        event.country.as_str(),
        event.event.as_str(),
    ) {
        ("trading_view", "United States", "Michigan Consumer Sentiment Prel") => {
            ("michigan.sentiment", Some("preliminary"), false)
        }
        ("trading_view", "United States", "Michigan Consumer Expectations Prel") => {
            ("michigan.expectations", Some("preliminary"), false)
        }
        ("trading_view", "United States", "Michigan Current Conditions Prel") => {
            ("michigan.conditions", Some("preliminary"), false)
        }
        ("trading_view", "United States", "Michigan Inflation Expectations Prel") => {
            ("michigan.inflation", Some("preliminary"), true)
        }
        ("trading_view", "United States", "Michigan 5 Year Inflation Expectations Prel") => {
            ("michigan.inflation5y", Some("preliminary"), true)
        }
        // Finals are recognized to veto an accidental match, not enabled for cross-source fills.
        ("trading_view", "United States", "Michigan Consumer Sentiment Final") => {
            ("michigan.sentiment", Some("final"), false)
        }
        ("trading_view", "United States", "Michigan Consumer Expectations Final") => {
            ("michigan.expectations", Some("final"), false)
        }
        ("trading_view", "United States", "Michigan Current Conditions Final") => {
            ("michigan.conditions", Some("final"), false)
        }
        ("trading_view", "United States", "Michigan Inflation Expectations Final") => {
            ("michigan.inflation", Some("final"), true)
        }
        ("trading_view", "United States", "Michigan 5 Year Inflation Expectations Final") => {
            ("michigan.inflation5y", Some("final"), true)
        }
        ("biquote", "United States", "Michigan Consumer Sentiment") => {
            ("michigan.sentiment", None, false)
        }
        ("biquote", "United States", "Michigan Consumer Expectations") => {
            ("michigan.expectations", None, false)
        }
        ("biquote", "United States", "Michigan Current Conditions") => {
            ("michigan.conditions", None, false)
        }
        ("biquote", "United States", "Michigan Inflation Expectations") => {
            ("michigan.inflation", None, true)
        }
        ("biquote", "United States", "Michigan 5-Year Inflation Expectations") => {
            ("michigan.inflation5y", None, true)
        }
        ("trading_view", "Canada", "Ivey PMI") | ("biquote", "Canada", "Ivey PMI n.s.a.") => {
            ("ivey.unadjusted", None, false)
        }
        ("trading_view", "Canada", "Ivey PMI s.a") | ("biquote", "Canada", "Ivey PMI") => {
            ("ivey.adjusted", None, false)
        }
        _ => return None,
    };
    if event.provider == "biquote" {
        let series = match metric {
            "michigan.sentiment" => "mql5:840210001",
            "michigan.expectations" => "mql5:840210002",
            "michigan.conditions" => "mql5:840210003",
            "michigan.inflation" => "mql5:840210004",
            "michigan.inflation5y" => "mql5:840210005",
            "ivey.adjusted" => "mql5:124020001",
            "ivey.unadjusted" => "mql5:124020002",
            _ => return None,
        };
        if event
            .release_metadata
            .series_id
            .as_deref()
            .is_some_and(|id| id != series)
        {
            return None;
        }
    }
    Some(Identity {
        metric,
        phase,
        percent,
    })
}

/// Monthly labels from TV ("Nov. 2026") and BiQuote ("2026-11-01T00:00:00Z").
/// Unknown forms are not guessed from the scheduled release month.
pub fn normalize_period(raw: &str) -> Option<String> {
    let raw = raw.trim();
    if let Ok(time) = DateTime::parse_from_rfc3339(raw) {
        return Some(time.format("%Y-%m").to_string());
    }
    if let Ok(date) = NaiveDate::parse_from_str(raw, "%Y-%m-%d") {
        return Some(date.format("%Y-%m").to_string());
    }
    for pattern in ["%d %b. %Y", "%d %b %Y", "%d %B %Y"] {
        if let Ok(date) = NaiveDate::parse_from_str(&format!("01 {raw}"), pattern) {
            return Some(date.format("%Y-%m").to_string());
        }
    }
    NaiveDate::parse_from_str(&format!("{raw}-01"), "%Y-%m-%d")
        .ok()
        .map(|date| date.format("%Y-%m").to_string())
}

/// Some(false) is authoritative: neither a cached Chinese label, same English spelling,
/// an old provider alias nor the generic alias table may override it.
pub fn verified_relation(left: &EconomicEvent, right: &EconomicEvent) -> Option<bool> {
    if let (Some(l), Some(r)) = (
        &left.release_metadata.period,
        &right.release_metadata.period,
    ) && l.trim() != r.trim()
    {
        // Only known monthly series may discard the day component. Weekly/daily
        // periods must never collapse simply because they fall in the same month.
        let monthly = (identity(left).is_some() && identity(right).is_some())
            || super::audited_identity::same_monthly_metric(left, right)
            || (known_dimension(left) == Some("jobs") && known_dimension(right) == Some("jobs"));
        if !monthly
            || !matches!((normalize_period(l), normalize_period(r)), (Some(l), Some(r)) if l == r)
        {
            return Some(false);
        }
    }
    // Leave previously verified Forex Factory aliases unchanged, but do not guess which
    // seasonal adjustment an unverified third source means by the ambiguous Ivey name.
    if !matches!(left.provider.as_str(), "trading_view" | "biquote")
        || !matches!(right.provider.as_str(), "trading_view" | "biquote")
    {
        if left.provider != right.provider
            && [identity(left), identity(right)]
                .into_iter()
                .flatten()
                .any(|key| key.metric.starts_with("ivey."))
        {
            return Some(false);
        }
        return None;
    }
    let a = identity(left);
    let b = identity(right);
    if a.is_none() && b.is_none() {
        let unverified_family = |event: &EconomicEvent| {
            let name = event.event.trim().to_ascii_lowercase();
            name.starts_with("michigan ") || name.starts_with("ivey pmi")
        };
        if left.provider != right.provider && (unverified_family(left) || unverified_family(right))
        {
            return Some(false);
        }
        return None;
    }
    let (Some(a), Some(b)) = (a, b) else {
        return Some(false);
    };
    if !left.time_exact
        || !right.time_exact
        || left.country != right.country
        || left.event_time != right.event_time
        || a.metric != b.metric
    {
        return Some(false);
    }
    let lp = left
        .release_metadata
        .period
        .as_deref()
        .and_then(normalize_period);
    let rp = right
        .release_metadata
        .period
        .as_deref()
        .and_then(normalize_period);
    if left.provider == right.provider {
        return Some(
            a.phase == b.phase
                && match (lp, rp) {
                    (Some(l), Some(r)) => l == r,
                    _ => true, // Existing occurrence ID can acquire its previously missing metadata.
                },
        );
    }
    if matches!(a.phase, Some("final")) || matches!(b.phase, Some("final")) {
        return Some(false);
    }
    Some(matches!((lp, rp), (Some(l), Some(r)) if l == r))
}

/// BiQuote does not label Michigan phase; contradictory TV phases at one instant are ambiguous.
pub fn ambiguous_phase(source: &EconomicEvent, local: &[EconomicEvent]) -> bool {
    let Some(key) = identity(source) else {
        return false;
    };
    let mut phases = std::collections::HashSet::new();
    for event in local {
        if event.country != source.country || event.event_time != source.event_time {
            continue;
        }
        if let Some(other) = identity(event)
            && other.metric == key.metric
            && let Some(phase) = other.phase
        {
            phases.insert(phase);
        }
    }
    phases.len() > 1
}

/// Shared fail-closed gate for ingest, fallback enrichment and release polling.
/// Provider aliases locate candidates; they do not authorize a cross-source write.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReleaseRejection {
    IdentityMismatch,
    PhaseConflict,
    AmbiguousTargets,
    MissingActual,
    IncompatibleUnits,
}

pub fn validate_release_identity(
    target: &EconomicEvent,
    source: &EconomicEvent,
    peers: &[EconomicEvent],
) -> Result<(), ReleaseRejection> {
    if !super::parse::same_occurrence(target, source) {
        return Err(ReleaseRejection::IdentityMismatch);
    }
    if ambiguous_phase(target, peers) || ambiguous_phase(source, peers) {
        return Err(ReleaseRejection::PhaseConflict);
    }
    if target.provider != source.provider {
        let candidates: std::collections::HashSet<_> = peers
            .iter()
            .filter(|peer| {
                peer.provider == target.provider && super::parse::same_occurrence(peer, source)
            })
            .map(|peer| &peer.provider_id)
            .collect();
        if candidates.len() > 1 {
            return Err(ReleaseRejection::AmbiguousTargets);
        }
    }
    Ok(())
}

pub fn release_value_for(
    target: &EconomicEvent,
    source: &EconomicEvent,
    peers: &[EconomicEvent],
) -> Result<Decimal, ReleaseRejection> {
    validate_release_identity(target, source, peers)?;
    if source.actual.is_none() {
        return Err(ReleaseRejection::MissingActual);
    }
    actual_for_target(target, source).ok_or(ReleaseRejection::IncompatibleUnits)
}

/// Preserve the original source across a cached/weekly-feed hop, not the intermediary.
pub fn inherit_actual_provenance(target: &mut EconomicEvent, source: &EconomicEvent) {
    target.release_metadata.actual_source = Some(
        source
            .release_metadata
            .actual_source
            .clone()
            .unwrap_or_else(|| source.provider.clone()),
    );
    target.release_metadata.actual_source_id = Some(
        source
            .release_metadata
            .actual_source_id
            .clone()
            .unwrap_or_else(|| source.provider_id.clone()),
    );
    target.release_metadata.actual_observed_at = Some(
        source
            .release_metadata
            .actual_observed_at
            .unwrap_or_else(chrono::Utc::now),
    );
}

/// Convert only Actual into the target's display scale. Keep its forecast, previous and unit.
/// Unknown/mismatched dimensions fail closed instead of silently changing the comparison basis.
pub fn actual_for_target(target: &EconomicEvent, source: &EconomicEvent) -> Option<Decimal> {
    let actual = source.actual?;
    if verified_relation(target, source) == Some(false) {
        return None;
    }
    if let (Some(a), Some(b)) = (identity(target), identity(source)) {
        if a.metric != b.metric {
            return None;
        }
        let accepted = |event: &EconomicEvent, percent: bool| match event.unit.as_deref() {
            Some("%" | "percent") => percent,
            None | Some("index points" | "points") => !percent,
            _ => false,
        };
        return (accepted(target, a.percent) && accepted(source, b.percent)).then_some(actual);
    }
    let (target_scale, mut target_dimension) = unit(target.unit.as_deref())?;
    let (source_scale, mut source_dimension) = unit(source.unit.as_deref())?;
    // This verified indicator uses bare K on TV and K/K jobs on BiQuote. Supply
    // only the dimension, never infer a missing scale from a name or from another feed.
    if known_dimension(target) == Some("jobs") && known_dimension(source) == Some("jobs") {
        if target_dimension == "unknown"
            && matches!(target.unit.as_deref(), Some("K" | "M" | "B" | "T"))
        {
            target_dimension = "jobs";
        }
        if source_dimension == "unknown"
            && matches!(source.unit.as_deref(), Some("K" | "M" | "B" | "T"))
        {
            source_dimension = "jobs";
        }
    }
    if target_dimension != source_dimension {
        return None;
    }
    // Bare K/M/B/T say nothing about the physical dimension. Only identical unknown
    // scales are safe; conversion requires an explicit matching dimension on both sides.
    if target_dimension == "unknown" && target_scale != source_scale {
        return None;
    }
    actual.checked_mul(source_scale)?.checked_div(target_scale)
}

fn known_dimension(event: &EconomicEvent) -> Option<&'static str> {
    (matches!(event.provider.as_str(), "trading_view" | "biquote")
        && event.country == "United States"
        && super::parse::normalized_event_name(&event.event) == "nonfarmpayrolls")
        .then_some("jobs")
}

fn unit(value: Option<&str>) -> Option<(Decimal, &'static str)> {
    let raw = value.map(str::trim).unwrap_or("");
    let (scale, dimension) = match raw.split_once(' ') {
        Some((prefix, rest)) if matches!(prefix, "K" | "M" | "B" | "T") => (prefix, rest),
        _ if matches!(raw, "K" | "M" | "B" | "T") => (raw, ""),
        _ => ("", raw),
    };
    let dimension = match dimension {
        "" => "unknown",
        "%" | "percent" => "percent",
        "index points" | "points" => "index",
        "job" | "jobs" => "jobs",
        "barrel" | "barrels" => "barrels",
        "number" => "number",
        "USD" | "$" => "USD",
        "EUR" | "€" => "EUR",
        "GBP" | "£" => "GBP",
        "JPY" => "JPY",
        "CAD" => "CAD",
        "AUD" => "AUD",
        "CNY" => "CNY",
        _ => return None,
    };
    let scale: i64 = match scale {
        "" => 1,
        "K" => 1_000,
        "M" => 1_000_000,
        "B" => 1_000_000_000,
        "T" => 1_000_000_000_000,
        _ => return None,
    };
    Some((Decimal::from(scale), dimension))
}
