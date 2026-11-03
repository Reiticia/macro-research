//! On-demand current equity prices. Bounded memory only, independent of release sampling.
use std::{collections::HashMap, sync::Arc, time::Duration};

use chrono::{DateTime, Utc};
use serde::Serialize;
use tokio::{sync::Mutex, time::Instant};

use crate::{earnings_logo::normalize_symbol, error::AppError, market::StockQuoteProvider};

#[cfg(test)]
#[path = "earnings_quote_tests.rs"]
mod tests;

const TTL: Duration = Duration::from_secs(5);
const STALE_TTL: Duration = Duration::from_secs(120);
const MAX_SYMBOLS: usize = 128;

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EarningsQuote {
    pub symbol: String,
    pub price: String,
    pub currency: Option<String>,
    pub timestamp: DateTime<Utc>,
    pub fetched_at: DateTime<Utc>,
    pub provider: &'static str,
    pub timestamp_basis: &'static str,
    pub stale: bool,
}

#[derive(Default)]
struct Cached {
    quote: Option<EarningsQuote>,
    success: Option<Instant>,
    attempted: Option<Instant>,
    failed: bool,
}
struct Entry {
    used: Instant,
    value: Arc<Mutex<Cached>>,
}
pub struct EarningsQuoteService {
    provider: Arc<dyn StockQuoteProvider>,
    entries: Mutex<HashMap<String, Entry>>,
}

impl EarningsQuoteService {
    pub fn new(provider: Arc<dyn StockQuoteProvider>) -> Self {
        Self {
            provider,
            entries: Mutex::new(HashMap::new()),
        }
    }

    pub async fn quote(&self, symbol: &str) -> Result<EarningsQuote, AppError> {
        let symbol = normalize_symbol(symbol)
            .ok_or_else(|| AppError::InvalidRequest("invalid stock symbol".into()))?;
        let cell = {
            let mut entries = self.entries.lock().await;
            if !entries.contains_key(&symbol) && entries.len() >= MAX_SYMBOLS {
                let oldest = entries
                    .iter()
                    .filter(|(_, e)| Arc::strong_count(&e.value) == 1)
                    .min_by_key(|(_, e)| e.used)
                    .map(|(key, _)| key.clone());
                if let Some(key) = oldest {
                    entries.remove(&key);
                } else {
                    return Err(AppError::QuotaExceeded {
                        retry_after_seconds: 5,
                    });
                }
            }
            let entry = entries.entry(symbol.clone()).or_insert_with(|| Entry {
                used: Instant::now(),
                value: Arc::new(Mutex::new(Cached::default())),
            });
            entry.used = Instant::now();
            entry.value.clone()
        };
        // Per-symbol lock: callers share one fetch, while unrelated symbols remain independent.
        let mut cache = cell.lock().await;
        if cache.attempted.is_none_or(|at| at.elapsed() >= TTL) {
            let result = tokio::time::timeout(
                Duration::from_secs(8),
                self.provider.live_stock_quote(&symbol),
            )
            .await;
            cache.attempted = Some(Instant::now());
            cache.failed = true;
            if let Ok(Ok(quote)) = result {
                let now = Utc::now();
                if quote.symbol == symbol
                    && quote.price.is_finite()
                    && quote.price > 0.0
                    && quote.timestamp <= now
                    && (now - quote.timestamp).num_days() < 7
                    && quote
                        .currency
                        .as_ref()
                        .is_none_or(|c| c.len() == 3 && c.bytes().all(|b| b.is_ascii_uppercase()))
                {
                    cache.quote = Some(EarningsQuote {
                        symbol,
                        price: quote.price.to_string(),
                        currency: quote.currency,
                        timestamp: quote.timestamp,
                        fetched_at: now,
                        provider: "yahoo",
                        timestamp_basis: quote.timestamp_basis.as_str(),
                        stale: false,
                    });
                    cache.success = Some(Instant::now());
                    cache.failed = false;
                }
            }
        }
        if cache.success.is_some_and(|at| at.elapsed() <= STALE_TTL)
            && let Some(mut quote) = cache.quote.clone()
        {
            quote.stale = cache.failed || (Utc::now() - quote.timestamp).num_seconds() > 90;
            return Ok(quote);
        }
        Err(AppError::Provider("stock_quote_unavailable".into()))
    }
}
