use std::collections::BTreeMap;

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use super::MarketSymbol;

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoricalEvidence {
    pub fetched_at: DateTime<Utc>,
    /// Historical calendar endpoints may contain revised data, not release-time vintages.
    pub revised_data_possible: bool,
    pub coverage: Vec<HistoricalCoverage>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoricalCoverage {
    pub symbol: MarketSymbol,
    pub source: String,
    pub interval_seconds: Option<i64>,
    /// complete / partial / unavailable / error
    pub status: String,
    pub reason: Option<String>,
    pub available_horizons: Vec<i64>,
    pub baseline_time: Option<DateTime<Utc>>,
    pub sample_times: BTreeMap<i64, DateTime<Utc>>,
}
