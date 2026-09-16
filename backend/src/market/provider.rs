use async_trait::async_trait;
use chrono::{DateTime, Utc};

use crate::{
    error::AppError,
    model::{Candle, Interval, LiveQuote, MarketSymbol, Quote},
};

#[async_trait]
pub trait MarketDataProvider: Send + Sync {
    async fn quote(&self, symbol: MarketSymbol) -> Result<Quote, AppError>;

    async fn live_quote(&self, symbol: MarketSymbol) -> Result<LiveQuote, AppError> {
        let quote = self.quote(symbol).await?;
        Ok(LiveQuote {
            symbol: quote.symbol,
            timestamp: quote.timestamp,
            price: quote.price,
            provider: "provider".into(),
            change_percent: None,
            high: None,
            low: None,
            market_state: None,
            stale: false,
        })
    }

    async fn candles(
        &self,
        symbol: MarketSymbol,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
        interval: Interval,
    ) -> Result<Vec<Candle>, AppError>;
}
