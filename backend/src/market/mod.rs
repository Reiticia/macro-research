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

use tokio::{
    sync::{Mutex, RwLock},
    time::{Instant as TokioInstant, MissedTickBehavior},
};

use crate::{
    alert::HealthRegistry,
    error::AppError,
    model::{Candle, LiveQuote, MarketSymbol, Quote},
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
    live_quote_refresh_interval: Duration,
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
            live_quote_refresh_interval: Duration::from_secs(5),
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

    pub fn with_live_quote_refresh(
        mut self,
        refresh_interval: Duration,
        stale_ttl: Duration,
    ) -> Self {
        self.live_quote_refresh_interval = refresh_interval.max(Duration::from_secs(1));
        self.live_quote_stale_ttl = stale_ttl.max(self.live_quote_refresh_interval);
        self
    }

    /// Starts one refresh loop per configured symbol. Initial ticks are spread evenly across a
    /// refresh cycle so a large symbol set does not hit every upstream at the same instant.
    /// HTTP handlers only read this cache and therefore cannot amplify upstream traffic.
    pub fn start_live_quote_refresh(self: Arc<Self>) {
        let mut seen = std::collections::HashSet::new();
        let symbols: Vec<_> = self
            .symbols
            .iter()
            .copied()
            .filter(|symbol| seen.insert(*symbol))
            .collect();
        if symbols.is_empty() {
            tracing::warn!("live quote refresh disabled because no market symbols are configured");
            return;
        }
        let refresh_interval = self.live_quote_refresh_interval.max(Duration::from_secs(1));
        let spacing = refresh_interval.div_f64(symbols.len() as f64);
        for (index, symbol) in symbols.into_iter().enumerate() {
            let service = self.clone();
            tokio::spawn(async move {
                let first_tick = TokioInstant::now() + spacing.mul_f64(index as f64);
                let mut timer = tokio::time::interval_at(first_tick, refresh_interval);
                timer.set_missed_tick_behavior(MissedTickBehavior::Skip);
                loop {
                    timer.tick().await;
                    if let Err(error) = service.refresh_live_quote(symbol, true).await {
                        tracing::debug!(%symbol, %error, "live quote refresh failed; retaining cached value");
                    }
                }
            });
        }
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
        if let Some(quote) = self.fresh_cached_live_quote(symbol).await {
            return Ok(quote);
        }
        match self.refresh_live_quote(symbol, false).await {
            Ok(quote) => Ok(quote),
            Err(error) => {
                if let Some(mut quote) = self.cached_live_quote(symbol).await {
                    quote.stale = true;
                    tracing::debug!(symbol = %symbol, error = %error, "serving stale market quote");
                    Ok(quote)
                } else {
                    Err(error)
                }
            }
        }
    }

    /// Returns the program cache only. This is used by the REST endpoint so client polling never
    /// turns into a synchronized burst of upstream requests.
    pub async fn cached_live_quote(&self, symbol: MarketSymbol) -> Option<LiveQuote> {
        self.live_quote_cache
            .read()
            .await
            .get(&symbol)
            .filter(|cached| cached.fetched_at.elapsed() <= self.live_quote_stale_ttl)
            .map(|cached| {
                let mut quote = cached.quote.clone();
                if cached.fetched_at.elapsed() > self.live_quote_refresh_interval {
                    quote.stale = true;
                }
                quote
            })
    }

    async fn fresh_cached_live_quote(&self, symbol: MarketSymbol) -> Option<LiveQuote> {
        self.live_quote_cache
            .read()
            .await
            .get(&symbol)
            .filter(|cached| cached.fetched_at.elapsed() <= self.live_quote_refresh_interval)
            .map(|cached| cached.quote.clone())
    }

    async fn refresh_live_quote(
        &self,
        symbol: MarketSymbol,
        force: bool,
    ) -> Result<LiveQuote, AppError> {
        let lock = self
            .live_quote_locks
            .get(&symbol)
            .ok_or_else(|| AppError::Provider(format!("no quote lock for {symbol}")))?
            .clone();
        let _guard = lock.lock().await;
        if !force && let Some(quote) = self.fresh_cached_live_quote(symbol).await {
            return Ok(quote);
        }
        let quote = self
            .providers
            .get(&symbol)
            .ok_or_else(|| AppError::Provider(format!("no provider for {symbol}")))?
            .live_quote(symbol)
            .await?;
        self.live_quote_cache.write().await.insert(
            symbol,
            CachedLiveQuote {
                quote: quote.clone(),
                fetched_at: Instant::now(),
            },
        );
        Ok(quote)
    }

    pub async fn collect_for_event(
        &self,
        event_id: i64,
        selected_symbols: &[MarketSymbol],
    ) -> Result<usize, AppError> {
        let symbols: Vec<_> = selected_symbols
            .iter()
            .copied()
            .filter(|symbol| self.symbols.contains(symbol))
            .collect();
        if symbols.is_empty() {
            tracing::error!(
                event_id,
                "event market selection was empty or invalid; skipping quote collection"
            );
            return Ok(0);
        }
        let mut saved = 0;
        for symbol in &symbols {
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

    /// Persists candle bars as per-minute snapshots for one event, giving historical events
    /// the same reaction-timeline samples that live collection produces in real time.
    pub async fn save_candle_snapshots(
        &self,
        event_id: i64,
        candles: &[Candle],
    ) -> Result<usize, AppError> {
        self.repository
            .save_candle_snapshots(event_id, candles)
            .await
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
        .with_live_quote_refresh(ttl, stale_ttl)
    }

    #[tokio::test]
    async fn event_collection_only_requests_selected_configured_symbols() {
        let provider = Arc::new(CountingProvider {
            calls: AtomicUsize::new(0),
            fail: AtomicBool::new(false),
        });
        let pool = sqlx::sqlite::SqlitePoolOptions::new()
            .max_connections(1)
            .connect("sqlite::memory:")
            .await
            .unwrap();
        sqlx::migrate!("./migrations").run(&pool).await.unwrap();
        let event_repository = crate::repository::EventRepository::new(pool.clone());
        let event_id = event_repository
            .save_events(&[crate::model::EconomicEvent {
                id: 0,
                provider: "fixture".into(),
                provider_id: "selected-collection".into(),
                release_group_id: None,
                country: "United States".into(),
                currency: Some("USD".into()),
                category: "employment".into(),
                event: "Nonfarm Payrolls".into(),
                event_zh_cn: None,
                event_zh_tw: None,
                event_time: Utc::now(),
                importance: 3,
                actual: None,
                previous: None,
                consensus: None,
                forecast: None,
                unit: None,
                status: crate::model::EventStatus::Watching,
                time_exact: true,
            }])
            .await
            .unwrap()[0];
        let service = MarketService::new(
            provider.clone(),
            provider.clone(),
            MarketRepository::new(pool),
            vec![MarketSymbol::Bitcoin, MarketSymbol::Gold],
        );
        let saved = service
            .collect_for_event(event_id, &[MarketSymbol::Gold])
            .await
            .unwrap();
        assert_eq!(saved, 1);
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
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
        let service = service(
            provider.clone(),
            Duration::from_secs(1),
            Duration::from_secs(300),
        )
        .await;
        assert!(
            !service
                .live_quote(MarketSymbol::Bitcoin)
                .await
                .unwrap()
                .stale
        );
        service
            .live_quote_cache
            .write()
            .await
            .get_mut(&MarketSymbol::Bitcoin)
            .unwrap()
            .fetched_at = Instant::now() - Duration::from_secs(2);
        provider.fail.store(true, Ordering::SeqCst);
        let stale = service.live_quote(MarketSymbol::Bitcoin).await.unwrap();
        assert!(stale.stale);
        assert_eq!(stale.price, 100.0);
        assert_eq!(provider.calls.load(Ordering::SeqCst), 2);
    }
}
