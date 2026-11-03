//! Persistent 60-minute equity sampling after an observed Nasdaq EPS transition.
//! Detection time is NOT a company-confirmed publication time. Never replay old releases.
use std::{collections::HashMap, sync::Arc, time::Duration as StdDuration};

use chrono::{DateTime, Duration, NaiveDate, TimeZone, Utc};
use chrono_tz::America::New_York;
use serde::Serialize;
use sqlx::{Row, Sqlite, SqlitePool, Transaction};

use crate::{
    earnings::EarningsEvent,
    earnings_logo::normalize_symbol,
    error::AppError,
    market::{StockQuote, StockQuoteProvider},
};

#[cfg(test)]
#[path = "earnings_market_tests.rs"]
mod tests;

const WATCH_GAP_SECONDS: i64 = 300;
const COLLECTION_SECONDS: i64 = 3600;
const MAX_BATCH: i64 = 32;

/// Invoked in the same transaction as a validated primary-source daily snapshot.
/// No fallback, cache read, historical import or diagnostic probe may trigger a collection.
pub(crate) async fn observe_calendar(
    tx: &mut Transaction<'_, Sqlite>,
    date: NaiveDate,
    events: &[EarningsEvent],
    observed_at: DateTime<Utc>,
) -> Result<u64, AppError> {
    let today = observed_at.with_timezone(&New_York).date_naive();
    if date < today - Duration::days(1) || date > today {
        return Ok(0);
    }
    let mut counts = HashMap::new();
    for event in events.iter().filter(|event| event.source == "nasdaq") {
        if let Some(symbol) = normalize_symbol(&event.symbol) {
            *counts.entry(symbol).or_insert(0) += 1;
        }
    }
    let now = observed_at.timestamp();
    let mut created = 0;
    for event in events.iter().filter(|event| event.source == "nasdaq") {
        let Some(symbol) =
            normalize_symbol(&event.symbol).filter(|symbol| counts.get(symbol) == Some(&1))
        else {
            continue;
        };
        let old = sqlx::query("SELECT actual_eps, fiscal_quarter_ending, last_seen_at, observation_status FROM earnings_release_watch WHERE date=? AND symbol=?")
            .bind(date.to_string()).bind(&symbol).fetch_optional(&mut **tx).await?;
        let mut previous_actual: Option<String> = None;
        let mut status = if event.actual_eps.is_some() {
            "already_reported".to_owned()
        } else {
            "watching".to_owned()
        };
        let mut trigger = false;
        if let Some(old) = old {
            let last_seen: i64 = old.try_get("last_seen_at")?;
            if last_seen > now {
                continue;
            }
            previous_actual = old.try_get("actual_eps")?;
            let previous_quarter: Option<String> = old.try_get("fiscal_quarter_ending")?;
            status = old.try_get("observation_status")?;
            if previous_actual.is_none() && event.actual_eps.is_some() {
                let different_quarter = previous_quarter
                    .as_ref()
                    .zip(event.fiscal_quarter_ending.as_ref())
                    .is_some_and(|(a, b)| a != b);
                status = if different_quarter {
                    "quarter_changed"
                } else if now - last_seen > WATCH_GAP_SECONDS {
                    "missed_after_gap"
                } else {
                    trigger = true;
                    "tracking"
                }
                .into();
            }
        }
        if trigger {
            let result = sqlx::query("INSERT INTO earnings_stock_tracking(date,symbol,company_name,fiscal_quarter_ending,actual_eps,detected_at,ends_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(date,symbol) DO NOTHING")
                .bind(date.to_string()).bind(&symbol).bind(&event.name).bind(&event.fiscal_quarter_ending)
                .bind(&event.actual_eps).bind(now).bind(now + COLLECTION_SECONDS).execute(&mut **tx).await?;
            created += result.rows_affected();
        }
        // A null/corrected value must not turn an already observed release into a new release.
        sqlx::query("INSERT INTO earnings_release_watch(date,symbol,fiscal_quarter_ending,actual_eps,last_seen_at,observation_status) VALUES(?,?,?,?,?,?) ON CONFLICT(date,symbol) DO UPDATE SET fiscal_quarter_ending=COALESCE(excluded.fiscal_quarter_ending,earnings_release_watch.fiscal_quarter_ending), actual_eps=COALESCE(excluded.actual_eps,earnings_release_watch.actual_eps), last_seen_at=excluded.last_seen_at, observation_status=excluded.observation_status")
            .bind(date.to_string()).bind(&symbol).bind(&event.fiscal_quarter_ending)
            .bind(event.actual_eps.as_ref().or(previous_actual.as_ref())).bind(now).bind(status).execute(&mut **tx).await?;
    }
    Ok(created)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EarningsStockResponse {
    pub date: NaiveDate,
    pub symbol: String,
    pub time_basis: &'static str,
    pub release_time_exact: bool,
    pub observation_status: String,
    pub tracking: Option<StockTracking>,
    pub snapshots: Vec<StockSnapshot>,
    pub coverage: StockCoverage,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StockTracking {
    pub id: i64,
    pub company_name: String,
    pub fiscal_quarter_ending: Option<String>,
    pub actual_eps: String,
    pub detected_at: DateTime<Utc>,
    pub ends_at: DateTime<Utc>,
    pub status: String,
    pub currency: Option<String>,
    pub last_attempt_at: Option<DateTime<Utc>>,
    pub last_success_at: Option<DateTime<Utc>>,
    pub last_error: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StockSnapshot {
    pub timestamp: DateTime<Utc>,
    pub first_quote_at: DateTime<Utc>,
    pub last_quote_at: DateTime<Utc>,
    pub price: f64,
    pub open: f64,
    pub high: f64,
    pub low: f64,
    pub close: f64,
    pub provider: &'static str,
    pub timestamp_basis: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StockCoverage {
    pub minute_count: usize,
    pub expected_minute_count: usize,
    pub missing_minute_count: usize,
    pub window_ended: bool,
}

fn datetime(timestamp: i64) -> Result<DateTime<Utc>, AppError> {
    Utc.timestamp_opt(timestamp, 0)
        .single()
        .ok_or_else(|| AppError::Internal("invalid earnings stock timestamp".into()))
}

pub async fn market_response(
    pool: &SqlitePool,
    date: NaiveDate,
    symbol: &str,
    now: DateTime<Utc>,
) -> Result<EarningsStockResponse, AppError> {
    let symbol = normalize_symbol(symbol)
        .ok_or_else(|| AppError::InvalidRequest("invalid stock symbol".into()))?;
    let observation_status: Option<String> = sqlx::query_scalar(
        "SELECT observation_status FROM earnings_release_watch WHERE date=? AND symbol=?",
    )
    .bind(date.to_string())
    .bind(&symbol)
    .fetch_optional(pool)
    .await?;
    let row = sqlx::query("SELECT * FROM earnings_stock_tracking WHERE date=? AND symbol=?")
        .bind(date.to_string())
        .bind(&symbol)
        .fetch_optional(pool)
        .await?;
    let mut snapshots = Vec::new();
    let mut expected = 0;
    let mut window_ended = false;
    let tracking = if let Some(row) = row {
        let id: i64 = row.try_get("id")?;
        let detected: i64 = row.try_get("detected_at")?;
        let ends: i64 = row.try_get("ends_at")?;
        window_ended = now.timestamp() >= ends;
        let through = now.timestamp().min(ends.saturating_sub(1));
        if through >= detected {
            expected = (through.div_euclid(60) - detected.div_euclid(60) + 1) as usize;
        }
        for row in sqlx::query(
            "SELECT * FROM earnings_stock_snapshot WHERE tracking_id=? ORDER BY timestamp",
        )
        .bind(id)
        .fetch_all(pool)
        .await?
        {
            snapshots.push(StockSnapshot {
                timestamp: datetime(row.try_get("timestamp")?)?,
                first_quote_at: datetime(row.try_get("first_quote_at")?)?,
                last_quote_at: datetime(row.try_get("last_quote_at")?)?,
                price: row.try_get("close")?,
                open: row.try_get("open")?,
                high: row.try_get("high")?,
                low: row.try_get("low")?,
                close: row.try_get("close")?,
                provider: "yahoo",
                timestamp_basis: row.try_get("timestamp_basis")?,
            });
        }
        Some(StockTracking {
            id,
            company_name: row.try_get("company_name")?,
            fiscal_quarter_ending: row.try_get("fiscal_quarter_ending")?,
            actual_eps: row.try_get("actual_eps")?,
            detected_at: datetime(detected)?,
            ends_at: datetime(ends)?,
            // Reads remain truthful even if a busy collector has not finalized this row yet.
            status: if window_ended {
                "completed".into()
            } else {
                row.try_get("status")?
            },
            currency: row.try_get("currency")?,
            last_attempt_at: row
                .try_get::<Option<i64>, _>("last_attempt_at")?
                .map(datetime)
                .transpose()?,
            last_success_at: row
                .try_get::<Option<i64>, _>("last_success_at")?
                .map(datetime)
                .transpose()?,
            last_error: row.try_get("last_error")?,
        })
    } else {
        None
    };
    let count = snapshots.len();
    Ok(EarningsStockResponse {
        date,
        symbol,
        time_basis: "first_detected_actual_eps",
        release_time_exact: false,
        observation_status: observation_status.unwrap_or_else(|| "not_observed".into()),
        tracking,
        snapshots,
        coverage: StockCoverage {
            minute_count: count,
            expected_minute_count: expected,
            missing_minute_count: expected.saturating_sub(count),
            window_ended,
        },
    })
}

pub struct EarningsMarketCollector {
    pool: SqlitePool,
    provider: Arc<dyn StockQuoteProvider>,
    clock: Arc<dyn Fn() -> DateTime<Utc> + Send + Sync>,
}

impl EarningsMarketCollector {
    pub fn new(pool: SqlitePool, provider: Arc<dyn StockQuoteProvider>) -> Self {
        Self {
            pool,
            provider,
            clock: Arc::new(Utc::now),
        }
    }
    pub fn with_clock(mut self, clock: impl Fn() -> DateTime<Utc> + Send + Sync + 'static) -> Self {
        self.clock = Arc::new(clock);
        self
    }
    pub async fn collect_loop(self: Arc<Self>, poll_seconds: u64) {
        let mut interval = tokio::time::interval(StdDuration::from_secs(poll_seconds.max(5)));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        loop {
            interval.tick().await;
            if let Err(error) = self.collect_once().await {
                tracing::error!(%error, "earnings stock collection iteration failed");
            }
        }
    }
    pub async fn collect_once(&self) -> Result<usize, AppError> {
        let now = (self.clock)();
        sqlx::query("UPDATE earnings_stock_tracking SET status='completed' WHERE status='collecting' AND ends_at<=?")
            .bind(now.timestamp()).execute(&self.pool).await?;
        let cutoff = (now.with_timezone(&New_York).date_naive() - Duration::days(120)).to_string();
        let mut tx = self.pool.begin().await?;
        sqlx::query("DELETE FROM earnings_release_watch WHERE date<?")
            .bind(&cutoff)
            .execute(&mut *tx)
            .await?;
        sqlx::query("DELETE FROM earnings_stock_tracking WHERE date<? AND status='completed'")
            .bind(&cutoff)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        let jobs = sqlx::query("SELECT id,symbol,detected_at,ends_at,currency FROM earnings_stock_tracking WHERE status='collecting' AND detected_at<=? AND ends_at>? ORDER BY last_attempt_at ASC,id ASC LIMIT ?")
            .bind(now.timestamp()).bind(now.timestamp()).bind(MAX_BATCH).fetch_all(&self.pool).await?;
        let mut sampled: HashMap<String, Result<StockQuote, String>> = HashMap::new();
        let mut saved = 0;
        for job in jobs {
            let id: i64 = job.try_get("id")?;
            let symbol: String = job.try_get("symbol")?;
            let detected: i64 = job.try_get("detected_at")?;
            let ends: i64 = job.try_get("ends_at")?;
            let currency: Option<String> = job.try_get("currency")?;
            let current = (self.clock)();
            if current.timestamp() >= ends {
                continue;
            }
            sqlx::query("UPDATE earnings_stock_tracking SET last_attempt_at=? WHERE id=?")
                .bind(current.timestamp())
                .bind(id)
                .execute(&self.pool)
                .await?;
            if let std::collections::hash_map::Entry::Vacant(entry) = sampled.entry(symbol.clone())
            {
                entry.insert(self.provider.stock_quote(&symbol).await.map_err(safe_error));
            }
            let observed = (self.clock)();
            let result = sampled
                .get(&symbol)
                .unwrap()
                .as_ref()
                .map_err(String::as_str)
                .and_then(|quote| {
                    if quote.symbol != symbol {
                        Err("stock_symbol_mismatch")
                    } else if !quote.price.is_finite() || quote.price <= 0.0 {
                        Err("stock_invalid_price")
                    } else if quote.currency.as_ref().is_some_and(|currency| {
                        currency.len() != 3 || !currency.bytes().all(|c| c.is_ascii_uppercase())
                    }) {
                        Err("stock_invalid_currency")
                    } else if quote.timestamp > observed
                        || observed
                            .signed_duration_since(quote.timestamp)
                            .num_seconds()
                            > 90
                    {
                        Err("stock_stale_or_future_quote")
                    } else if quote.timestamp.timestamp() < detected
                        || quote.timestamp.timestamp() >= ends
                    {
                        Err("stock_quote_outside_collection_window")
                    } else if currency
                        .as_ref()
                        .zip(quote.currency.as_ref())
                        .is_some_and(|(a, b)| a != b)
                    {
                        Err("stock_currency_mismatch")
                    } else {
                        Ok(quote)
                    }
                });
            match result {
                Ok(quote) => {
                    save_quote(&self.pool, id, quote, observed).await?;
                    saved += 1;
                }
                Err(error) => {
                    sqlx::query("UPDATE earnings_stock_tracking SET last_error=? WHERE id=?")
                        .bind(error)
                        .bind(id)
                        .execute(&self.pool)
                        .await?;
                    // Missing closed-market ticks are per-occurrence gaps, not proof the whole
                    // Yahoo source is down. Never extend a window or stamp an old price as now.
                    tracing::debug!(id, %symbol, error, "earnings stock sample unavailable");
                }
            }
        }
        Ok(saved)
    }
}

fn safe_error(error: AppError) -> String {
    match error {
        AppError::Http(error) => error
            .status()
            .map(|status| format!("yahoo_http_{}", status.as_u16()))
            .unwrap_or_else(|| {
                if error.is_timeout() {
                    "yahoo_timeout"
                } else {
                    "yahoo_transport_error"
                }
                .into()
            }),
        AppError::Provider(message)
            if message.len() <= 80
                && message.starts_with("stock_")
                && message.bytes().all(|c| c.is_ascii_lowercase() || c == b'_') =>
        {
            message
        }
        AppError::Provider(message) if message.contains("rate limited") => {
            "yahoo_rate_limited".into()
        }
        _ => "stock_quote_unavailable".into(),
    }
}

async fn save_quote(
    pool: &SqlitePool,
    id: i64,
    quote: &StockQuote,
    observed: DateTime<Utc>,
) -> Result<(), AppError> {
    let time = quote.timestamp.timestamp();
    let minute = time.div_euclid(60) * 60;
    let mut tx = pool.begin().await?;
    sqlx::query("INSERT INTO earnings_stock_snapshot(tracking_id,timestamp,first_quote_at,last_quote_at,open,high,low,close,timestamp_basis) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(tracking_id,timestamp) DO UPDATE SET open=CASE WHEN excluded.first_quote_at<earnings_stock_snapshot.first_quote_at THEN excluded.open ELSE earnings_stock_snapshot.open END, close=CASE WHEN excluded.last_quote_at>=earnings_stock_snapshot.last_quote_at THEN excluded.close ELSE earnings_stock_snapshot.close END, high=MAX(earnings_stock_snapshot.high,excluded.high), low=MIN(earnings_stock_snapshot.low,excluded.low), first_quote_at=MIN(earnings_stock_snapshot.first_quote_at,excluded.first_quote_at), last_quote_at=MAX(earnings_stock_snapshot.last_quote_at,excluded.last_quote_at), timestamp_basis=CASE WHEN excluded.timestamp_basis=earnings_stock_snapshot.timestamp_basis THEN earnings_stock_snapshot.timestamp_basis ELSE 'mixed' END")
        .bind(id).bind(minute).bind(time).bind(time).bind(quote.price).bind(quote.price).bind(quote.price).bind(quote.price)
        .bind(quote.timestamp_basis.as_str()).execute(&mut *tx).await?;
    sqlx::query("UPDATE earnings_stock_tracking SET currency=COALESCE(currency,?),last_success_at=?,last_error=NULL WHERE id=?")
        .bind(&quote.currency).bind(observed.timestamp()).bind(id).execute(&mut *tx).await?;
    tx.commit().await?;
    Ok(())
}
