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
                    Err(fallback_error) => {
                        self.record_failure(key, &fallback_error).await;
                        Err(fallback_error)
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
                    Err(fallback_error) => {
                        self.record_failure(key, &fallback_error).await;
                        Err(fallback_error)
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
            Ok(candles) if !candles.is_empty() => {
                self.record_success(self.primary_key).await;
                Ok(candles)
            }
            Ok(empty) => {
                // An empty window is indistinguishable from a source gap: BiQuote only retains
                // recent intraday bars and CNBC only serves today, so a trading-day window can
                // come back empty while the fallback still has the data.
                let error = AppError::Provider(format!(
                    "{symbol} returned no candles between {start} and {end}"
                ));
                self.record_failure(self.primary_key, &error).await;
                let Some((fallback, key)) = &self.fallback else {
                    return Ok(empty);
                };
                match fallback.candles(symbol, start, end, interval).await {
                    Ok(candles) => {
                        self.record_success(key).await;
                        Ok(candles)
                    }
                    Err(fallback_error) => {
                        self.record_failure(key, &fallback_error).await;
                        Err(fallback_error)
                    }
                }
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
                    Err(fallback_error) => {
                        self.record_failure(key, &fallback_error).await;
                        Err(fallback_error)
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    struct Stub {
        bars: usize,
        calls: AtomicUsize,
    }

    #[async_trait]
    impl MarketDataProvider for Stub {
        async fn quote(&self, symbol: MarketSymbol) -> Result<Quote, AppError> {
            Err(AppError::Provider(format!("no quote for {symbol}")))
        }
        async fn candles(
            &self,
            symbol: MarketSymbol,
            start: DateTime<Utc>,
            _: DateTime<Utc>,
            interval: Interval,
        ) -> Result<Vec<Candle>, AppError> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            Ok((0..self.bars)
                .map(|n| Candle {
                    symbol,
                    timestamp: start + chrono::Duration::seconds(interval.seconds() * n as i64),
                    open: 100.0,
                    high: 100.0,
                    low: 100.0,
                    close: 100.0,
                    volume: None,
                })
                .collect())
        }
    }

    struct FailingSource(&'static str);

    #[async_trait]
    impl MarketDataProvider for FailingSource {
        async fn quote(&self, _: MarketSymbol) -> Result<Quote, AppError> {
            Err(AppError::Provider(self.0.into()))
        }

        async fn candles(
            &self,
            _: MarketSymbol,
            _: DateTime<Utc>,
            _: DateTime<Utc>,
            _: Interval,
        ) -> Result<Vec<Candle>, AppError> {
            Err(AppError::Provider(self.0.into()))
        }
    }

    async fn test_health() -> Arc<HealthRegistry> {
        let pool = sqlx::sqlite::SqlitePoolOptions::new()
            .max_connections(1)
            .connect("sqlite::memory:")
            .await
            .unwrap();
        sqlx::migrate!("./migrations").run(&pool).await.unwrap();
        Arc::new(HealthRegistry::new(pool, None, 3, 3600))
    }

    #[tokio::test]
    async fn failed_fallback_records_and_returns_its_own_error() {
        let primary_error = "CNBC returned no price for us2y";
        let fallback_error = "Yahoo ^UST2YR: Not Found";
        for operation in ["quote", "live_quote", "candles"] {
            let health = test_health().await;
            let provider = MonitoredProvider::new(
                Arc::new(FailingSource(primary_error)),
                crate::market::CNBC_KEY,
                Some((
                    Arc::new(FailingSource(fallback_error)),
                    crate::market::YAHOO_KEY,
                )),
                Some(health.clone()),
            );
            let start = Utc::now();
            for _ in 0..3 {
                let error = match operation {
                    "quote" => provider.quote(MarketSymbol::Us2y).await.unwrap_err(),
                    "live_quote" => provider.live_quote(MarketSymbol::Us2y).await.unwrap_err(),
                    _ => provider
                        .candles(
                            MarketSymbol::Us2y,
                            start,
                            start + chrono::Duration::hours(1),
                            Interval::OneMinute,
                        )
                        .await
                        .unwrap_err(),
                };
                assert_eq!(
                    error.to_string(),
                    format!("provider data error: {fallback_error}")
                );
            }
            let snapshot = health.snapshot().await.unwrap();
            assert_eq!(snapshot.len(), 2);
            for (key, expected_error) in [
                (crate::market::CNBC_KEY, primary_error),
                (crate::market::YAHOO_KEY, fallback_error),
            ] {
                let source = snapshot.iter().find(|source| source.key == key).unwrap();
                assert_eq!(source.status, "down", "{operation}: {key}");
                assert_eq!(source.consecutive_failures, 3);
                assert_eq!(
                    source.last_error.as_deref(),
                    Some(format!("provider data error: {expected_error}").as_str()),
                    "{operation}: {key}",
                );
            }
        }
    }

    #[tokio::test]
    async fn empty_primary_candles_fall_back_instead_of_reporting_success() {
        let primary = Arc::new(Stub {
            bars: 0,
            calls: AtomicUsize::new(0),
        });
        let fallback = Arc::new(Stub {
            bars: 2,
            calls: AtomicUsize::new(0),
        });
        let provider = MonitoredProvider::new(
            primary.clone(),
            "primary",
            Some((fallback.clone(), "fallback")),
            None,
        );
        let start = Utc::now() - chrono::Duration::days(3);
        let candles = provider
            .candles(
                MarketSymbol::Gold,
                start,
                start + chrono::Duration::hours(1),
                Interval::OneMinute,
            )
            .await
            .unwrap();
        assert_eq!(candles.len(), 2);
        assert_eq!(primary.calls.load(Ordering::SeqCst), 1);
        assert_eq!(fallback.calls.load(Ordering::SeqCst), 1);

        // A fallback that is also empty is a genuine no-data window, not an error.
        let provider = MonitoredProvider::new(
            Arc::new(Stub {
                bars: 0,
                calls: AtomicUsize::new(0),
            }),
            "primary",
            Some((
                Arc::new(Stub {
                    bars: 0,
                    calls: AtomicUsize::new(0),
                }),
                "fallback",
            )),
            None,
        );
        assert!(
            provider
                .candles(
                    MarketSymbol::Gold,
                    start,
                    start + chrono::Duration::hours(1),
                    Interval::OneMinute,
                )
                .await
                .unwrap()
                .is_empty()
        );
    }
}
