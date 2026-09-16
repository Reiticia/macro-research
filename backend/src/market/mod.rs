mod binance;
mod biquote;
mod cnbc;
mod monitored;
mod provider;
mod yahoo;

pub use binance::BinanceProvider;
pub use biquote::BiquoteProvider;
pub use cnbc::CnbcProvider;
pub use monitored::MonitoredProvider;
pub use provider::MarketDataProvider;
pub use yahoo::YahooProvider;

use std::{
    collections::HashMap,
    sync::Arc,
    time::{Duration, Instant},
};

use tokio::sync::{Mutex, RwLock};

use crate::{
    alert::HealthRegistry,
    error::AppError,
    model::{LiveQuote, MarketSymbol, Quote},
    repository::MarketRepository,
};

pub const YAHOO_KEY: &str = "market.yahoo";
pub const BINANCE_KEY: &str = "market.binance";
pub const BIQUOTE_KEY: &str = "market.biquote";
pub const CNBC_KEY: &str = "market.cnbc";

pub struct MarketService {
    providers: HashMap<MarketSymbol, Arc<dyn MarketDataProvider>>,
    yahoo: Arc<dyn MarketDataProvider>,
    binance: Arc<dyn MarketDataProvider>,
    biquote: Option<Arc<dyn MarketDataProvider>>,
    cnbc: Option<Arc<dyn MarketDataProvider>>,
    repository: MarketRepository,
    symbols: Vec<MarketSymbol>,
    health: Option<Arc<HealthRegistry>>,
    live_quote_cache: RwLock<HashMap<MarketSymbol, CachedLiveQuote>>,
    live_quote_locks: HashMap<MarketSymbol, Arc<Mutex<()>>>,
    live_quote_ttl: Duration,
    live_quote_stale_ttl: Duration,
}

struct CachedLiveQuote {
    quote: LiveQuote,
    fetched_at: Instant,
}

impl MarketService {
    pub fn new(
        yahoo: Arc<dyn MarketDataProvider>,
        binance: Arc<dyn MarketDataProvider>,
        repository: MarketRepository,
        symbols: Vec<MarketSymbol>,
    ) -> Self {
        let mut service = Self {
            providers: HashMap::new(),
            yahoo,
            binance,
            biquote: None,
            cnbc: None,
            repository,
            symbols,
            health: None,
            live_quote_cache: RwLock::new(HashMap::new()),
            live_quote_locks: MarketSymbol::ALL
                .into_iter()
                .map(|symbol| (symbol, Arc::new(Mutex::new(()))))
                .collect(),
            live_quote_ttl: Duration::from_secs(30),
            live_quote_stale_ttl: Duration::from_secs(300),
        };
        service.rebuild();
        service
    }

    /// Attaches the health registry, so every source call reports success or failure.
    pub fn with_health(mut self, health: Arc<HealthRegistry>) -> Self {
        self.health = Some(health);
        self.rebuild();
        self
    }

    pub fn symbols(&self) -> &[MarketSymbol] {
        &self.symbols
    }

    /// BiQuote first (with a Yahoo fallback) for FX, metals and DXY.
    pub fn with_biquote(mut self, biquote: Arc<dyn MarketDataProvider>) -> Self {
        self.biquote = Some(biquote);
        self.rebuild();
        self
    }

    /// CNBC first (with a Yahoo fallback) for Treasury yields, which many networks block.
    pub fn with_cnbc(mut self, cnbc: Arc<dyn MarketDataProvider>) -> Self {
        self.cnbc = Some(cnbc);
        self.rebuild();
        self
    }

    /// Rebuilds the per-symbol routing table from the registered raw providers.
    ///
    /// Yields have their own chain (CNBC with a Yahoo fallback), FX and metals prefer
    /// BiQuote, crypto is Binance-only, and everything else is Yahoo.
    fn rebuild(&mut self) {
        let health = self.health.clone();
        let monitored =
            |provider: Arc<dyn MarketDataProvider>,
             key: &'static str,
             fallback: Option<(Arc<dyn MarketDataProvider>, &'static str)>| {
                Arc::new(MonitoredProvider::new(
                    provider,
                    key,
                    fallback,
                    health.clone(),
                )) as Arc<dyn MarketDataProvider>
            };
        let mut providers: HashMap<MarketSymbol, Arc<dyn MarketDataProvider>> = HashMap::new();
        for symbol in MarketSymbol::ALL {
            let yahoo = self.yahoo.clone();
            let provider = if symbol.is_crypto() {
                monitored(self.binance.clone(), BINANCE_KEY, None)
            } else if matches!(symbol, MarketSymbol::Us2y | MarketSymbol::Us10y) {
                match &self.cnbc {
                    Some(cnbc) => monitored(cnbc.clone(), CNBC_KEY, Some((yahoo, YAHOO_KEY))),
                    None => monitored(yahoo, YAHOO_KEY, None),
                }
            } else if symbol.uses_biquote() {
                match &self.biquote {
                    Some(biquote) => {
                        monitored(biquote.clone(), BIQUOTE_KEY, Some((yahoo, YAHOO_KEY)))
                    }
                    None => monitored(yahoo, YAHOO_KEY, None),
                }
            } else {
                monitored(yahoo, YAHOO_KEY, None)
            };
            providers.insert(symbol, provider);
        }
        self.providers = providers;
    }

    pub fn with_live_quote_cache(mut self, ttl: Duration, stale_ttl: Duration) -> Self {
        self.live_quote_ttl = ttl;
        self.live_quote_stale_ttl = stale_ttl.max(ttl);
        self
    }

    pub async fn historical_candles(
        &self,
        symbol: MarketSymbol,
        start: chrono::DateTime<chrono::Utc>,
        end: chrono::DateTime<chrono::Utc>,
        interval: crate::model::Interval,
    ) -> Result<Vec<crate::model::Candle>, AppError> {
        self.providers
            .get(&symbol)
            .ok_or_else(|| AppError::Provider(format!("no provider for {symbol}")))?
            .candles(symbol, start, end, interval)
            .await
    }

    pub async fn quote(&self, symbol: MarketSymbol) -> Result<Quote, AppError> {
        self.providers
            .get(&symbol)
            .ok_or_else(|| AppError::Provider(format!("no provider for {symbol}")))?
            .quote(symbol)
            .await
    }

    pub async fn live_quote(&self, symbol: MarketSymbol) -> Result<LiveQuote, AppError> {
        if let Some(quote) = self.cached_live_quote(symbol, self.live_quote_ttl).await {
            return Ok(quote);
        }
        let lock = self
            .live_quote_locks
            .get(&symbol)
            .ok_or_else(|| AppError::Provider(format!("no quote lock for {symbol}")))?
            .clone();
        let _guard = lock.lock().await;
        if let Some(quote) = self.cached_live_quote(symbol, self.live_quote_ttl).await {
            return Ok(quote);
        }
        let result = self
            .providers
            .get(&symbol)
            .ok_or_else(|| AppError::Provider(format!("no provider for {symbol}")))?
            .live_quote(symbol)
            .await;
        match result {
            Ok(quote) => {
                self.live_quote_cache.write().await.insert(
                    symbol,
                    CachedLiveQuote {
                        quote: quote.clone(),
                        fetched_at: Instant::now(),
                    },
                );
                Ok(quote)
            }
            Err(error) => {
                if let Some(mut quote) = self
                    .cached_live_quote(symbol, self.live_quote_stale_ttl)
                    .await
                {
                    quote.stale = true;
                    tracing::debug!(symbol = %symbol, error = %error, "serving stale market quote");
                    Ok(quote)
                } else {
                    Err(error)
                }
            }
        }
    }

    async fn cached_live_quote(
        &self,
        symbol: MarketSymbol,
        maximum_age: Duration,
    ) -> Option<LiveQuote> {
        self.live_quote_cache
            .read()
            .await
            .get(&symbol)
            .filter(|cached| cached.fetched_at.elapsed() <= maximum_age)
            .map(|cached| cached.quote.clone())
    }

    pub async fn collect_for_event(&self, event_id: i64) -> Result<usize, AppError> {
        let mut saved = 0;
        for symbol in &self.symbols {
            match self.quote(*symbol).await {
                Ok(quote) => {
                    self.repository.save_quote(event_id, &quote).await?;
                    saved += 1;
                }
                Err(error) => {
                    tracing::warn!(event_id, symbol = %symbol, error = %error, "market quote failed");
                }
            }
        }
        Ok(saved)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use async_trait::async_trait;
    use chrono::Utc;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    struct CountingProvider {
        calls: AtomicUsize,
        fail: AtomicBool,
    }

    #[async_trait]
    impl MarketDataProvider for CountingProvider {
        async fn quote(&self, symbol: MarketSymbol) -> Result<Quote, AppError> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            if self.fail.load(Ordering::SeqCst) {
                return Err(AppError::Provider("fixture failure".into()));
            }
            Ok(Quote {
                symbol,
                timestamp: Utc::now(),
                price: 100.0,
            })
        }

        async fn candles(
            &self,
            _: MarketSymbol,
            _: chrono::DateTime<Utc>,
            _: chrono::DateTime<Utc>,
            _: crate::model::Interval,
        ) -> Result<Vec<crate::model::Candle>, AppError> {
            Ok(Vec::new())
        }
    }

    async fn service(
        provider: Arc<CountingProvider>,
        ttl: Duration,
        stale_ttl: Duration,
    ) -> MarketService {
        let pool = sqlx::sqlite::SqlitePoolOptions::new()
            .max_connections(1)
            .connect("sqlite::memory:")
            .await
            .unwrap();
        MarketService::new(
            provider.clone(),
            provider,
            MarketRepository::new(pool),
            vec![MarketSymbol::Bitcoin],
        )
        .with_live_quote_cache(ttl, stale_ttl)
    }

    #[tokio::test]
    async fn live_quotes_are_single_flight_and_cached() {
        let provider = Arc::new(CountingProvider {
            calls: AtomicUsize::new(0),
            fail: AtomicBool::new(false),
        });
        let service = service(
            provider.clone(),
            Duration::from_secs(60),
            Duration::from_secs(300),
        )
        .await;
        let (first, second) = tokio::join!(
            service.live_quote(MarketSymbol::Bitcoin),
            service.live_quote(MarketSymbol::Bitcoin)
        );
        assert_eq!(first.unwrap().price, 100.0);
        assert_eq!(second.unwrap().price, 100.0);
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn recent_cached_quote_is_used_when_provider_fails() {
        let provider = Arc::new(CountingProvider {
            calls: AtomicUsize::new(0),
            fail: AtomicBool::new(false),
        });
        let service = service(provider.clone(), Duration::ZERO, Duration::from_secs(300)).await;
        assert!(
            !service
                .live_quote(MarketSymbol::Bitcoin)
                .await
                .unwrap()
                .stale
        );
        provider.fail.store(true, Ordering::SeqCst);
        let stale = service.live_quote(MarketSymbol::Bitcoin).await.unwrap();
        assert!(stale.stale);
        assert_eq!(stale.price, 100.0);
        assert_eq!(provider.calls.load(Ordering::SeqCst), 2);
    }
}
