//! Candidate canonical-series registry. Shadow diagnostics ONLY; never authorizes value writes.
use crate::model::EconomicEvent;
use serde::Deserialize;
use std::sync::OnceLock;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Catalog {
    schema_version: u32,
    mode: String,
    evidence: String,
    entries: Vec<Entry>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Entry {
    canonical: String,
    country: String,
    unit: String,
    tv_series: String,
    tv_title: String,
    bq_series: String,
    bq_title: String,
}
fn entries() -> &'static [Entry] {
    static CATALOG: OnceLock<Catalog> = OnceLock::new();
    &CATALOG
        .get_or_init(|| {
            let catalog: Catalog = serde_json::from_str(include_str!("series_registry.json"))
                .expect("static series catalog");
            assert_eq!(catalog.schema_version, 1);
            assert_eq!(catalog.mode, "shadow_only");
            assert!(!catalog.evidence.is_empty());
            catalog
        })
        .entries
}

#[derive(Debug, PartialEq, Eq)]
pub enum Rejection {
    UnknownSeries,
    Country,
    TitleDrift,
    Unit,
    MissingPeriod,
    DifferentPeriod,
    DifferentSeries,
    DifferentTime,
    InexactTime,
    Ambiguous,
}

fn resolve(event: &EconomicEvent) -> Result<&'static Entry, Rejection> {
    let series = event
        .release_metadata
        .series_id
        .as_deref()
        .ok_or(Rejection::UnknownSeries)?;
    let entry = entries()
        .iter()
        .find(|e| match event.provider.as_str() {
            "trading_view" => series == e.tv_series,
            "biquote" => series == e.bq_series,
            _ => false,
        })
        .ok_or(Rejection::UnknownSeries)?;
    if event.country != entry.country {
        return Err(Rejection::Country);
    }
    let title = if event.provider == "trading_view" {
        &entry.tv_title
    } else {
        &entry.bq_title
    };
    if &event.event != title {
        return Err(Rejection::TitleDrift);
    }
    if entry.unit != "percent" || !matches!(event.unit.as_deref(), Some("%" | "percent")) {
        return Err(Rejection::Unit);
    }
    Ok(entry)
}

/// Inputs must already have a matching catalog series; only known monthly series normalize months.
fn period(event: &EconomicEvent) -> Result<String, Rejection> {
    event
        .release_metadata
        .period
        .as_deref()
        .and_then(super::release_identity::normalize_period)
        .ok_or(Rejection::MissingPeriod)
}

pub fn compare(left: &EconomicEvent, right: &EconomicEvent) -> Result<&'static str, Rejection> {
    let a = resolve(left)?;
    let b = resolve(right)?;
    if a.canonical != b.canonical {
        return Err(Rejection::DifferentSeries);
    }
    if !left.time_exact || !right.time_exact {
        return Err(Rejection::InexactTime);
    }
    if left.event_time != right.event_time {
        return Err(Rejection::DifferentTime);
    }
    if period(left)? != period(right)? {
        return Err(Rejection::DifferentPeriod);
    }
    Ok(&a.canonical)
}

/// Only logs; neither this result nor Actual equality feeds same_occurrence/release_value_for.
/// Group indices make the work bounded to one occurrence instead of all pairs in a calendar.
pub fn log_shadow(targets: &[EconomicEvent], sources: &[EconomicEvent]) {
    let mut by_time = std::collections::HashMap::new();
    for source in sources {
        by_time
            .entry((source.country.as_str(), source.event_time))
            .or_insert_with(Vec::new)
            .push(source);
    }
    for target in targets {
        if resolve(target).is_err() {
            continue;
        }
        let candidates = by_time
            .get(&(target.country.as_str(), target.event_time))
            .map(Vec::as_slice)
            .unwrap_or(&[]);
        let matches: Vec<_> = candidates
            .iter()
            .filter_map(|s| compare(target, s).ok().map(|key| (key, *s)))
            .collect();
        let unique_target = targets
            .iter()
            .filter(|t| compare(target, t).is_ok())
            .count()
            == 1;
        if matches.len() == 1 && unique_target {
            let (canonical, source) = matches[0];
            tracing::info!(canonical, target_id=%target.provider_id, source_id=%source.provider_id,
                period=?target.release_metadata.period, actual_equal=?match (target.actual,source.actual) {(Some(a),Some(b))=>Some(a==b),_=>None},
                "canonical series shadow match; no write authorization");
        } else if matches.len() > 1 || !unique_target {
            tracing::warn!(target_id=%target.provider_id, reason=?Rejection::Ambiguous,"canonical series shadow rejected");
        } else {
            for source in candidates.iter().filter(|s| resolve(s).is_ok()) {
                tracing::debug!(target_id=%target.provider_id,source_id=%source.provider_id,reason=?compare(target,source).err(),"canonical series shadow no match");
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{EventStatus, ReleaseMetadata};
    fn event(entry: &Entry, provider: &str) -> EconomicEvent {
        EconomicEvent {
            id: 0,
            provider: provider.into(),
            provider_id: format!("{provider}:occurrence"),
            release_group_id: None,
            release_metadata: ReleaseMetadata {
                period: Some(
                    if provider == "biquote" {
                        "2026-10-01T00:00:00Z"
                    } else {
                        "Oct. 2026"
                    }
                    .into(),
                ),
                series_id: Some(
                    if provider == "biquote" {
                        &entry.bq_series
                    } else {
                        &entry.tv_series
                    }
                    .clone(),
                ),
                ..Default::default()
            },
            country: entry.country.clone(),
            currency: None,
            category: "fixture".into(),
            event: if provider == "biquote" {
                entry.bq_title.clone()
            } else {
                entry.tv_title.clone()
            },
            event_zh_cn: None,
            event_zh_tw: None,
            event_time: "2026-11-16T13:30:00Z".parse().unwrap(),
            importance: 3,
            actual: None,
            previous: None,
            consensus: None,
            forecast: None,
            unit: Some("%".into()),
            status: EventStatus::Scheduled,
            time_exact: true,
        }
    }
    #[test]
    fn catalog_pairs_match_without_any_numeric_evidence() {
        let mut keys = std::collections::HashSet::new();
        for entry in entries() {
            assert!(keys.insert(&entry.canonical));
            let tv = event(entry, "trading_view");
            let bq = event(entry, "biquote");
            assert_eq!(compare(&tv, &bq), Ok(entry.canonical.as_str()));
        }
    }
    #[test]
    fn every_series_is_distinct_and_phase_title_drift_is_rejected() {
        for a in entries() {
            for b in entries() {
                if a.canonical != b.canonical {
                    assert!(compare(&event(a, "trading_view"), &event(b, "biquote")).is_err());
                }
            }
        }
        let a = event(&entries()[0], "trading_view");
        let mut b = event(&entries()[0], "biquote");
        b.event.push_str(" s.a.");
        assert_eq!(compare(&a, &b), Err(Rejection::TitleDrift));
    }
    #[test]
    fn metadata_conflicts_never_pass_even_with_equal_values_or_chinese_names() {
        let mut a = event(&entries()[0], "trading_view");
        a.actual = Some(1.into());
        a.event_zh_cn = Some("相同".into());
        let original = event(&entries()[0], "biquote");
        for kind in [
            "country", "unit", "period", "time", "inexact", "series", "missing",
        ] {
            let mut b = original.clone();
            b.actual = a.actual;
            b.event_zh_cn = a.event_zh_cn.clone();
            match kind {
                "country" => b.country = "United States".into(),
                "unit" => b.unit = Some("USD".into()),
                "period" => b.release_metadata.period = Some("Sep. 2026".into()),
                "time" => b.event_time += chrono::Duration::nanoseconds(1),
                "inexact" => b.time_exact = false,
                "series" => b.release_metadata.series_id = Some("unverified".into()),
                _ => b.release_metadata.period = None,
            }
            assert!(compare(&a, &b).is_err(), "{kind}");
        }
    }
}
