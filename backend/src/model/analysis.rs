use std::{fmt, str::FromStr};

use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

use super::{MarketReaction, MarketSymbol};

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MacroSignal {
    StrongHawkish,
    Hawkish,
    Neutral,
    Dovish,
    StrongDovish,
    BullishOil,
    BearishOil,
}

impl MacroSignal {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::StrongHawkish => "strong_hawkish",
            Self::Hawkish => "hawkish",
            Self::Neutral => "neutral",
            Self::Dovish => "dovish",
            Self::StrongDovish => "strong_dovish",
            Self::BullishOil => "bullish_oil",
            Self::BearishOil => "bearish_oil",
        }
    }
}

impl fmt::Display for MacroSignal {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl FromStr for MacroSignal {
    type Err = String;

    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "strong_hawkish" => Ok(Self::StrongHawkish),
            "hawkish" => Ok(Self::Hawkish),
            "neutral" => Ok(Self::Neutral),
            "dovish" => Ok(Self::Dovish),
            "strong_dovish" => Ok(Self::StrongDovish),
            "bullish_oil" => Ok(Self::BullishOil),
            "bearish_oil" => Ok(Self::BearishOil),
            other => Err(format!("unknown macro signal: {other}")),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Direction {
    Up,
    Down,
    Flat,
}

impl Direction {
    /// Lowercase token used in API payloads and AI prompts.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Up => "up",
            Self::Down => "down",
            Self::Flat => "flat",
        }
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExpectedReaction {
    pub symbol: MarketSymbol,
    pub direction: Direction,
    pub rationale: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReactionComparison {
    pub symbol: MarketSymbol,
    pub expected: Direction,
    pub observed_change: Option<f64>,
    pub conforms: Option<bool>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalysisReport {
    pub id: i64,
    pub event_id: i64,
    pub raw_surprise: Option<Decimal>,
    pub macro_signal: MacroSignal,
    pub expected_reactions: Vec<ExpectedReaction>,
    pub observed_reactions: Vec<MarketReaction>,
    pub comparisons: Vec<ReactionComparison>,
    pub summary: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub historical: Option<super::HistoricalEvidence>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}
