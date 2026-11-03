use super::*;
use crate::market::{StockQuote, StockTimestampBasis};
use std::sync::{
    Mutex as StdMutex,
    atomic::{AtomicBool, AtomicUsize, Ordering},
};

struct Provider {
    calls: AtomicUsize,
    fail: AtomicBool,
    value: StdMutex<StockQuote>,
}
impl Provider {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            calls: AtomicUsize::new(0),
            fail: AtomicBool::new(false),
            value: StdMutex::new(StockQuote {
                symbol: "AAPL".into(),
                price: 123.456,
                currency: Some("USD".into()),
                timestamp: Utc::now() - chrono::Duration::seconds(1),
                timestamp_basis: StockTimestampBasis::ProviderQuote,
            }),
        })
    }
}
#[async_trait::async_trait]
impl StockQuoteProvider for Provider {
    async fn stock_quote(&self, _: &str) -> Result<StockQuote, AppError> {
        panic!("display must not use the sampling method")
    }
    async fn live_stock_quote(&self, symbol: &str) -> Result<StockQuote, AppError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        tokio::time::sleep(Duration::from_millis(5)).await;
        if self.fail.load(Ordering::SeqCst) {
            return Err(AppError::Provider("unavailable".into()));
        }
        let mut value = self.value.lock().unwrap().clone();
        // Allow tests to request independent symbols without changing the mock payload.
        if value.symbol == "AAPL" {
            value.symbol = symbol.into();
        }
        Ok(value)
    }
}
async fn expire(service: &EarningsQuoteService, symbol: &str, age: u64) {
    let cell = service.entries.lock().await[symbol].value.clone();
    let mut cache = cell.lock().await;
    cache.attempted = Some(Instant::now() - Duration::from_secs(6));
    cache.success = Some(Instant::now() - Duration::from_secs(age));
}

#[tokio::test]
async fn parallel_callers_share_five_second_cache_and_prices_are_strings() {
    let provider = Provider::new();
    let service = Arc::new(EarningsQuoteService::new(provider.clone()));
    let mut tasks = Vec::new();
    for _ in 0..20 {
        let service = service.clone();
        tasks.push(tokio::spawn(
            async move { service.quote("aapl").await.unwrap() },
        ));
    }
    for task in tasks {
        let quote = task.await.unwrap();
        assert_eq!(quote.symbol, "AAPL");
        assert_eq!(quote.price, "123.456");
        assert!(!quote.stale);
        let json = serde_json::to_value(quote).unwrap();
        assert!(json["price"].is_string());
        assert!(json.get("fetchedAt").is_some());
        assert_eq!(json["timestampBasis"], "provider_quote");
    }
    assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
    expire(&service, "AAPL", 6).await;
    service.quote("AAPL").await.unwrap();
    assert_eq!(provider.calls.load(Ordering::SeqCst), 2);
    service.quote("MSFT").await.unwrap();
    assert_eq!(provider.calls.load(Ordering::SeqCst), 3);
}

#[tokio::test]
async fn refresh_failure_has_bounded_stale_fallback_and_negative_caching() {
    let provider = Provider::new();
    let service = EarningsQuoteService::new(provider.clone());
    let original = service.quote("AAPL").await.unwrap();
    provider.fail.store(true, Ordering::SeqCst);
    expire(&service, "AAPL", 6).await;
    let old = service.quote("AAPL").await.unwrap();
    assert!(old.stale);
    assert_eq!(old.timestamp, original.timestamp);
    assert_eq!(old.fetched_at, original.fetched_at);
    service.quote("AAPL").await.unwrap();
    assert_eq!(provider.calls.load(Ordering::SeqCst), 2);
    expire(&service, "AAPL", 121).await;
    assert!(service.quote("AAPL").await.is_err());
    assert!(service.quote("AAPL").await.is_err());
    assert_eq!(provider.calls.load(Ordering::SeqCst), 3);
}

#[tokio::test]
async fn old_closed_market_price_keeps_its_timestamp_and_is_marked_stale() {
    let provider = Provider::new();
    provider.value.lock().unwrap().timestamp = Utc::now() - chrono::Duration::days(2);
    let service = EarningsQuoteService::new(provider.clone());
    let quote = service.quote("AAPL").await.unwrap();
    assert!(quote.stale);
    assert!(quote.fetched_at - quote.timestamp > chrono::Duration::days(1));
}

#[tokio::test]
async fn bad_identity_future_prices_and_invalid_values_never_enter_cache() {
    for case in 0..5 {
        let provider = Provider::new();
        {
            let mut quote = provider.value.lock().unwrap();
            match case {
                0 => quote.symbol = "WRONG".into(),
                1 => quote.timestamp = Utc::now() + chrono::Duration::hours(1),
                2 => quote.price = f64::NAN,
                3 => quote.price = 0.0,
                _ => quote.timestamp = Utc::now() - chrono::Duration::days(8),
            }
        }
        let service = EarningsQuoteService::new(provider.clone());
        assert!(service.quote("AAPL").await.is_err());
        assert!(service.quote("AAPL").await.is_err());
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
    }
    let provider = Provider::new();
    let service = EarningsQuoteService::new(provider.clone());
    for symbol in ["../AAPL", "https://example.test", "AAPL?x=1", ""] {
        assert!(service.quote(symbol).await.is_err());
    }
    assert_eq!(provider.calls.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn memory_is_bounded_and_a_new_service_has_no_persisted_prices() {
    let provider = Provider::new();
    let service = EarningsQuoteService::new(provider.clone());
    for i in 0..130 {
        service.quote(&format!("T{i}")).await.unwrap();
    }
    assert_eq!(service.entries.lock().await.len(), MAX_SYMBOLS);
    let restarted = EarningsQuoteService::new(provider);
    assert!(restarted.entries.lock().await.is_empty());
}
