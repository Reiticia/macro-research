//! Equity quotes are not macro asset enums; their source time must remain auditable.
use async_trait::async_trait;
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

use crate::error::AppError;

#[derive(Clone, Copy, Debug, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StockTimestampBasis {
    ProviderQuote,
    MinuteBarClose,
}

impl StockTimestampBasis {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::ProviderQuote => "provider_quote",
            Self::MinuteBarClose => "minute_bar_close",
        }
    }
}

#[derive(Clone, Debug)]
pub struct StockQuote {
    pub symbol: String,
    pub timestamp: DateTime<Utc>,
    pub price: f64,
    pub currency: Option<String>,
    pub timestamp_basis: StockTimestampBasis,
}

#[async_trait]
pub trait StockQuoteProvider: Send + Sync {
    async fn stock_quote(&self, symbol: &str) -> Result<StockQuote, AppError>;
}
