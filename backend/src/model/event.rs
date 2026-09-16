use std::{fmt, str::FromStr};

use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EconomicEvent {
    pub id: i64,
    pub provider: String,
    pub provider_id: String,
    pub release_group_id: Option<i64>,
    pub country: String,
    pub currency: Option<String>,
    pub category: String,
    pub event: String,
    pub event_zh_cn: Option<String>,
    pub event_zh_tw: Option<String>,
    pub event_time: DateTime<Utc>,
    pub importance: u8,
    pub actual: Option<Decimal>,
    pub previous: Option<Decimal>,
    pub consensus: Option<Decimal>,
    pub forecast: Option<Decimal>,
    pub unit: Option<String>,
    pub status: EventStatus,
    /// False for all-day / approximate releases: never attribute intraday reactions.
    #[serde(default = "exact_time_default")]
    pub time_exact: bool,
}

fn exact_time_default() -> bool {
    true
}

impl EconomicEvent {
    pub fn has_release(&self) -> bool {
        self.actual.is_some()
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum EventStatus {
    #[default]
    Scheduled,
    Watching,
    Released,
    CollectingMarketData,
    Analyzing,
    Completed,
    Timeout,
    Historical,
    /// The release time has passed but no source has published an actual value yet.
    DataUnavailable,
}

impl EventStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Scheduled => "scheduled",
            Self::Watching => "watching",
            Self::Released => "released",
            Self::CollectingMarketData => "collecting_market_data",
            Self::Analyzing => "analyzing",
            Self::Completed => "completed",
            Self::Timeout => "timeout",
            Self::Historical => "historical",
            Self::DataUnavailable => "data_unavailable",
        }
    }
}

impl fmt::Display for EventStatus {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl FromStr for EventStatus {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "scheduled" => Ok(Self::Scheduled),
            "watching" => Ok(Self::Watching),
            "released" => Ok(Self::Released),
            "collecting_market_data" => Ok(Self::CollectingMarketData),
            "analyzing" => Ok(Self::Analyzing),
            "completed" => Ok(Self::Completed),
            "timeout" => Ok(Self::Timeout),
            "historical" => Ok(Self::Historical),
            "data_unavailable" => Ok(Self::DataUnavailable),
            other => Err(format!("unknown event status: {other}")),
        }
    }
}
