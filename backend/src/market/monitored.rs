use std::sync::Arc;

use async_trait::async_trait;
use chrono::{DateTime, Utc};

use crate::{
    alert::HealthRegistry,
    error::AppError,
    market::MarketDataProvider,
    model::{Candle, Interval, LiveQuote, MarketSymbol, Quote},
};

/// Records per-source health and tries a fallback provider before giving up.
///
/// The market sources are not interchangeable (BiQuote has no Treasury yield, CNBC has no
/// indices), so the chain is explicit per symbol rather than a single global priority list.
pub struct MonitoredProvider {
    primary: Arc<dyn MarketDataProvider>,
    primary_key: &'static str,
    fallback: Option<(Arc<dyn MarketDataProvider>, &'static str)>,
    health: Option<Arc<HealthRegistry>>,
}

impl MonitoredProvider {
    pub fn new(
        primary: Arc<dyn MarketDataProvider>,
        primary_key: &'static str,
        fallback: Option<(Arc<dyn MarketDataProvider>, &'static str)>,
        health: Option<Arc<HealthRegistry>>,
    ) -> Self {
        Self {
            primary,
            primary_key,
            fallback,
            health,
        }
    }

    async fn record_success(&self, key: &str) {
        if let Some(health) = &self.health {
            health.record_success(key).await;
        }
    }

    async fn record_failure(&self, key: &str, error: &AppError) {
        if let Some(health) = &self.health {
            health.record_failure(key, error.to_string()).await;
        }
    }
}

#[async_trait]
impl MarketDataProvider for MonitoredProvider {
    async fn quote(&self, symbol: MarketSymbol) -> Result<Quote, AppError> {
        match self.primary.quote(symbol).await {
            Ok(quote) => {
                self.record_success(self.primary_key).await;
                Ok(quote)
            }
            Err(error) => {
                self.record_failure(self.primary_key, &error).await;
                let Some((fallback, key)) = &self.fallback else {
                    return Err(error);
                };
                match fallback.quote(symbol).await {
                    Ok(quote) => {
                        self.record_success(key).await;
                        Ok(quote)
                    }
                    Err(_) => {
                        self.record_failure(key, &error).await;
                        Err(error)
                    }
                }
            }
        }
    }

    async fn live_quote(&self, symbol: MarketSymbol) -> Result<LiveQuote, AppError> {
        match self.primary.live_quote(symbol).await {
            Ok(quote) => {
                self.record_success(self.primary_key).await;
                Ok(quote)
            }
            Err(error) => {
                self.record_failure(self.primary_key, &error).await;
                let Some((fallback, key)) = &self.fallback else {
                    return Err(error);
                };
                match fallback.live_quote(symbol).await {
                    Ok(quote) => {
                        self.record_success(key).await;
                        Ok(quote)
                    }
                    Err(_) => {
                        self.record_failure(key, &error).await;
                        Err(error)
                    }
                }
            }
        }
    }

    async fn candles(
        &self,
        symbol: MarketSymbol,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
        interval: Interval,
    ) -> Result<Vec<Candle>, AppError> {
        match self.primary.candles(symbol, start, end, interval).await {
            Ok(candles) => {
                self.record_success(self.primary_key).await;
                Ok(candles)
            }
            Err(error) => {
                self.record_failure(self.primary_key, &error).await;
                let Some((fallback, key)) = &self.fallback else {
                    return Err(error);
                };
                match fallback.candles(symbol, start, end, interval).await {
                    Ok(candles) => {
                        self.record_success(key).await;
                        Ok(candles)
                    }
                    Err(_) => {
                        self.record_failure(key, &error).await;
                        Err(error)
                    }
                }
            }
        }
    }
}
