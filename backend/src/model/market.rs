use std::{fmt, str::FromStr};

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MarketSymbol {
    Gold,
    Silver,
    Sp500,
    Nasdaq100,
    DowJones,
    Us2y,
    Us10y,
    Dxy,
    EurUsd,
    GbpUsd,
    UsdJpy,
    AudUsd,
    Wti,
    Brent,
    NaturalGas,
    Bitcoin,
    Ethereum,
}

impl MarketSymbol {
    pub const ALL: [Self; 17] = [
        Self::Gold,
        Self::Silver,
        Self::Sp500,
        Self::Nasdaq100,
        Self::DowJones,
        Self::Us2y,
        Self::Us10y,
        Self::Dxy,
        Self::EurUsd,
        Self::GbpUsd,
        Self::UsdJpy,
        Self::AudUsd,
        Self::Wti,
        Self::Brent,
        Self::NaturalGas,
        Self::Bitcoin,
        Self::Ethereum,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Gold => "gold",
            Self::Silver => "silver",
            Self::Sp500 => "sp500",
            Self::Nasdaq100 => "nasdaq100",
            Self::DowJones => "dow_jones",
            Self::Us2y => "us2y",
            Self::Us10y => "us10y",
            Self::Dxy => "dxy",
            Self::EurUsd => "eur_usd",
            Self::GbpUsd => "gbp_usd",
            Self::UsdJpy => "usd_jpy",
            Self::AudUsd => "aud_usd",
            Self::Wti => "wti",
            Self::Brent => "brent",
            Self::NaturalGas => "natural_gas",
            Self::Bitcoin => "bitcoin",
            Self::Ethereum => "ethereum",
        }
    }

    pub fn is_crypto(self) -> bool {
        matches!(self, Self::Bitcoin | Self::Ethereum)
    }

    pub fn uses_biquote(self) -> bool {
        matches!(
            self,
            Self::Gold
                | Self::Silver
                | Self::Dxy
                | Self::EurUsd
                | Self::GbpUsd
                | Self::UsdJpy
                | Self::AudUsd
        )
    }
}

impl fmt::Display for MarketSymbol {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl FromStr for MarketSymbol {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        let normalized = value.trim().to_ascii_lowercase().replace(['-', '/'], "_");
        match normalized.as_str() {
            "gold" => Ok(Self::Gold),
            "silver" => Ok(Self::Silver),
            "sp500" | "s&p500" => Ok(Self::Sp500),
            "nasdaq" | "nasdaq100" => Ok(Self::Nasdaq100),
            "dow" | "dow_jones" => Ok(Self::DowJones),
            "us2y" => Ok(Self::Us2y),
            "us10y" => Ok(Self::Us10y),
            "dxy" => Ok(Self::Dxy),
            "eur_usd" | "eurusd" => Ok(Self::EurUsd),
            "gbp_usd" | "gbpusd" => Ok(Self::GbpUsd),
            "usd_jpy" | "usdjpy" => Ok(Self::UsdJpy),
            "aud_usd" | "audusd" => Ok(Self::AudUsd),
            "wti" => Ok(Self::Wti),
            "brent" => Ok(Self::Brent),
            "natural_gas" | "natgas" => Ok(Self::NaturalGas),
            "bitcoin" | "btc" => Ok(Self::Bitcoin),
            "ethereum" | "eth" => Ok(Self::Ethereum),
            other => Err(format!("unknown market symbol: {other}")),
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Quote {
    pub symbol: MarketSymbol,
    pub timestamp: DateTime<Utc>,
    pub price: f64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LiveQuote {
    pub symbol: MarketSymbol,
    pub timestamp: DateTime<Utc>,
    pub price: f64,
    pub provider: String,
    pub change_percent: Option<f64>,
    pub high: Option<f64>,
    pub low: Option<f64>,
    pub market_state: Option<String>,
    pub stale: bool,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Candle {
    pub symbol: MarketSymbol,
    pub timestamp: DateTime<Utc>,
    pub open: f64,
    pub high: f64,
    pub low: f64,
    pub close: f64,
    pub volume: Option<f64>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Interval {
    OneMinute,
    FiveMinutes,
    FifteenMinutes,
    OneHour,
    OneDay,
}

impl Interval {
    pub fn seconds(self) -> i64 {
        match self {
            Self::OneMinute => 60,
            Self::FiveMinutes => 300,
            Self::FifteenMinutes => 900,
            Self::OneHour => 3600,
            Self::OneDay => 86400,
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MarketSnapshot {
    pub id: i64,
    pub event_id: i64,
    pub symbol: MarketSymbol,
    pub timestamp: DateTime<Utc>,
    pub price: f64,
    pub open: Option<f64>,
    pub high: Option<f64>,
    pub low: Option<f64>,
    pub close: Option<f64>,
    pub volume: Option<f64>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MarketReaction {
    pub event_id: i64,
    pub symbol: MarketSymbol,
    pub baseline_price: f64,
    pub reaction_unit: ReactionUnit,
    pub change_1m: Option<f64>,
    pub change_5m: Option<f64>,
    pub change_15m: Option<f64>,
    pub change_30m: Option<f64>,
    pub change_60m: Option<f64>,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReactionUnit {
    Percent,
    BasisPoints,
}
