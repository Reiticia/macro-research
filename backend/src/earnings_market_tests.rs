use super::*;
use crate::market::StockTimestampBasis;
use async_trait::async_trait;
use serde_json::json;
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};
use std::sync::Mutex;

use crate::alert::test_support::temporary;

fn clock_time(time: &Mutex<DateTime<Utc>>) -> DateTime<Utc> {
    *time.lock().unwrap()
}

fn now() -> DateTime<Utc> {
    Utc.with_ymd_and_hms(2026, 11, 2, 15, 30, 0).unwrap()
}
fn event(symbol: &str, actual: Option<&str>) -> EarningsEvent {
    serde_json::from_value(json!({"symbol":symbol,"name":"Apple","source":"nasdaq","session":"after_hours","actualEps":actual,"fiscalQuarterEnding":"Sep/2026"})).unwrap()
}
async fn pool() -> SqlitePool {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    pool
}
async fn observe(
    pool: &SqlitePool,
    date: NaiveDate,
    rows: &[EarningsEvent],
    time: DateTime<Utc>,
) -> u64 {
    let mut tx = pool.begin().await.unwrap();
    let created = observe_calendar(&mut tx, date, rows, time).await.unwrap();
    tx.commit().await.unwrap();
    created
}
async fn trigger(pool: &SqlitePool, symbol: &str, time: DateTime<Utc>) {
    let date = time.with_timezone(&New_York).date_naive();
    assert_eq!(
        observe(
            pool,
            date,
            &[event(symbol, None)],
            time - Duration::seconds(65)
        )
        .await,
        0
    );
    assert_eq!(
        observe(pool, date, &[event(symbol, Some("0"))], time).await,
        1
    );
}
fn quote(symbol: &str, time: DateTime<Utc>, price: f64) -> StockQuote {
    StockQuote {
        symbol: symbol.into(),
        timestamp: time,
        price,
        currency: Some("USD".into()),
        timestamp_basis: StockTimestampBasis::ProviderQuote,
    }
}
#[derive(Default)]
struct Provider {
    reply: Mutex<Option<StockQuote>>,
    overrides: Mutex<HashMap<String, StockQuote>>,
    calls: Mutex<Vec<String>>,
}
#[async_trait]
impl StockQuoteProvider for Provider {
    async fn stock_quote(&self, symbol: &str) -> Result<StockQuote, AppError> {
        self.calls.lock().unwrap().push(symbol.into());
        self.overrides
            .lock()
            .unwrap()
            .get(symbol)
            .cloned()
            .or_else(|| self.reply.lock().unwrap().clone())
            .ok_or_else(|| {
                AppError::Provider("fixture URL=https://secret.invalid?token=SECRET".into())
            })
    }
}
fn collector(
    pool: &SqlitePool,
    provider: &Arc<Provider>,
    time: &Arc<Mutex<DateTime<Utc>>>,
) -> EarningsMarketCollector {
    let clock = time.clone();
    EarningsMarketCollector::new(pool.clone(), provider.clone())
        .with_clock(move || *clock.lock().unwrap())
}
async fn response(pool: &SqlitePool, symbol: &str, time: DateTime<Utc>) -> EarningsStockResponse {
    market_response(
        pool,
        now().with_timezone(&New_York).date_naive(),
        symbol,
        time,
    )
    .await
    .unwrap()
}

#[tokio::test]
async fn actual_eps_zero_transition_starts_exactly_one_60_minute_job_and_corrections_never_restart_it()
 {
    let pool = pool().await;
    let time = now();
    trigger(&pool, "AAPL", time).await;
    let initial = response(&pool, "aapl", time).await;
    let track = initial.tracking.unwrap();
    assert_eq!(track.actual_eps, "0");
    assert_eq!(track.ends_at, time + Duration::minutes(60));
    assert!(!initial.release_time_exact);
    assert_eq!(initial.time_basis, "first_detected_actual_eps");
    let date = time.date_naive();
    for (offset, actual) in [(65, Some("0")), (130, None), (195, Some("1.2"))] {
        assert_eq!(
            observe(
                &pool,
                date,
                &[event("AAPL", actual)],
                time + Duration::seconds(offset)
            )
            .await,
            0
        );
    }
    let latest = response(&pool, "AAPL", time).await.tracking.unwrap();
    assert_eq!(latest.id, track.id);
    assert_eq!(latest.detected_at, time);
    assert_eq!(latest.ends_at, time + Duration::minutes(60));
}

#[tokio::test]
async fn cold_start_reported_rows_historical_rows_and_fallbacks_do_not_replay_releases() {
    let pool = pool().await;
    let time = now();
    let date = time.date_naive();
    assert_eq!(
        observe(&pool, date, &[event("AAPL", Some("1.2"))], time).await,
        0
    );
    let result = response(&pool, "AAPL", time).await;
    assert_eq!(result.observation_status, "already_reported");
    assert!(result.tracking.is_none());
    let historical = date - Duration::days(2);
    observe(&pool, historical, &[event("OLD", None)], time).await;
    assert_eq!(
        observe(&pool, historical, &[event("OLD", Some("1"))], time).await,
        0
    );
    let mut fallback = event("TV", None);
    fallback.source = "tradingview".into();
    observe(&pool, date, &[fallback.clone()], time).await;
    fallback.actual_eps = Some("2".into());
    assert_eq!(
        observe(&pool, date, &[fallback], time + Duration::seconds(65)).await,
        0
    );
    assert!(response(&pool, "TV", time).await.tracking.is_none());
}

#[tokio::test]
async fn stale_pending_observations_and_quarter_changes_are_explicit_not_new_releases() {
    let pool = pool().await;
    let time = now();
    let date = time.date_naive();
    observe(
        &pool,
        date,
        &[event("GAP", None), event("QUARTER", None)],
        time - Duration::seconds(301),
    )
    .await;
    assert_eq!(
        observe(&pool, date, &[event("GAP", Some("1"))], time).await,
        0
    );
    assert_eq!(
        response(&pool, "GAP", time).await.observation_status,
        "missed_after_gap"
    );
    observe(
        &pool,
        date,
        &[event("QUARTER", None)],
        time - Duration::seconds(1),
    )
    .await;
    let mut changed = event("QUARTER", Some("1"));
    changed.fiscal_quarter_ending = Some("Dec/2026".into());
    assert_eq!(observe(&pool, date, &[changed], time).await, 0);
    assert_eq!(
        response(&pool, "QUARTER", time).await.observation_status,
        "quarter_changed"
    );
}

#[tokio::test]
async fn valid_gap_boundary_and_previous_market_date_are_supported_but_future_dates_are_not_releases()
 {
    let pool = pool().await;
    let time = now();
    let yesterday = time.date_naive() - Duration::days(1);
    observe(
        &pool,
        yesterday,
        &[event("LATE", None)],
        time - Duration::seconds(300),
    )
    .await;
    assert_eq!(
        observe(&pool, yesterday, &[event("LATE", Some("1"))], time).await,
        1
    );
    let tomorrow = time.date_naive() + Duration::days(1);
    observe(&pool, tomorrow, &[event("FUTURE", None)], time).await;
    assert_eq!(
        observe(&pool, tomorrow, &[event("FUTURE", Some("1"))], time).await,
        0
    );
}

#[tokio::test]
async fn ambiguous_symbols_and_out_of_order_observations_do_not_create_false_releases() {
    let pool = pool().await;
    let time = now();
    let date = time.date_naive();
    observe(&pool, date, &[event("AAPL", None)], time).await;
    assert_eq!(
        observe(
            &pool,
            date,
            &[event("aapl", Some("1")), event("AAPL", None)],
            time + Duration::seconds(10)
        )
        .await,
        0
    );
    assert_eq!(
        observe(
            &pool,
            date,
            &[event("AAPL", Some("1"))],
            time - Duration::seconds(1)
        )
        .await,
        0
    );
    assert!(response(&pool, "AAPL", time).await.tracking.is_none());
}

#[tokio::test]
async fn detection_and_snapshot_transaction_rollback_does_not_leave_orphan_jobs() {
    let pool = pool().await;
    let time = now();
    let date = time.date_naive();
    observe(
        &pool,
        date,
        &[event("AAPL", None)],
        time - Duration::seconds(65),
    )
    .await;
    let mut tx = pool.begin().await.unwrap();
    assert_eq!(
        observe_calendar(&mut tx, date, &[event("AAPL", Some("1"))], time)
            .await
            .unwrap(),
        1
    );
    tx.rollback().await.unwrap();
    assert!(response(&pool, "AAPL", time).await.tracking.is_none());
    assert_eq!(
        observe(&pool, date, &[event("AAPL", Some("1"))], time).await,
        1
    );
}

#[tokio::test]
async fn samples_form_minute_ohlc_using_provider_times_even_when_quotes_arrive_out_of_order() {
    let pool = pool().await;
    let start = now();
    trigger(&pool, "AAPL", start).await;
    let provider = Arc::new(Provider::default());
    let time = Arc::new(Mutex::new(start + Duration::seconds(50)));
    let service = collector(&pool, &provider, &time);
    for (second, price) in [
        (40, 104.0),
        (10, 101.0),
        (30, 99.0),
        (50, 103.0),
        (50, 103.0),
    ] {
        *provider.reply.lock().unwrap() =
            Some(quote("AAPL", start + Duration::seconds(second), price));
        assert_eq!(service.collect_once().await.unwrap(), 1);
    }
    let result = response(&pool, "AAPL", clock_time(&time)).await;
    assert_eq!(result.snapshots.len(), 1);
    let bar = &result.snapshots[0];
    assert_eq!(
        (bar.open, bar.high, bar.low, bar.close),
        (101.0, 104.0, 99.0, 103.0)
    );
    assert_eq!(bar.first_quote_at, start + Duration::seconds(10));
    assert_eq!(bar.last_quote_at, start + Duration::seconds(50));
    assert_eq!(bar.timestamp_basis, "provider_quote");
    assert_eq!(result.tracking.unwrap().currency.as_deref(), Some("USD"));
}

#[tokio::test]
async fn invalid_stale_future_wrong_symbol_and_pre_detection_quotes_remain_gaps() {
    let pool = pool().await;
    let start = now();
    trigger(&pool, "AAPL", start).await;
    let provider = Arc::new(Provider::default());
    let time = Arc::new(Mutex::new(start + Duration::seconds(20)));
    let service = collector(&pool, &provider, &time);
    for bad in [
        quote("AAPL", start - Duration::seconds(1), 100.0),
        quote("AAPL", start + Duration::seconds(21), 100.0),
        quote("MSFT", start + Duration::seconds(5), 100.0),
        quote("AAPL", start + Duration::seconds(5), f64::NAN),
        quote("AAPL", start + Duration::seconds(5), f64::INFINITY),
        quote("AAPL", start + Duration::seconds(5), -1.0),
    ] {
        *provider.reply.lock().unwrap() = Some(bad);
        assert_eq!(service.collect_once().await.unwrap(), 0);
    }
    *time.lock().unwrap() = start + Duration::minutes(2);
    *provider.reply.lock().unwrap() = Some(quote("AAPL", start + Duration::seconds(1), 100.0));
    assert_eq!(service.collect_once().await.unwrap(), 0);
    let result = response(&pool, "AAPL", clock_time(&time)).await;
    assert!(result.snapshots.is_empty());
    assert!(result.coverage.missing_minute_count > 0);
    assert_eq!(
        result.tracking.unwrap().last_error.as_deref(),
        Some("stock_stale_or_future_quote")
    );
}

#[tokio::test]
async fn expired_jobs_never_request_current_prices_or_extend_the_end_time() {
    let pool = pool().await;
    let start = now();
    trigger(&pool, "AAPL", start).await;
    let provider = Arc::new(Provider::default());
    let time = Arc::new(Mutex::new(start + Duration::minutes(60)));
    let service = collector(&pool, &provider, &time);
    assert_eq!(service.collect_once().await.unwrap(), 0);
    assert!(provider.calls.lock().unwrap().is_empty());
    let result = response(&pool, "AAPL", clock_time(&time)).await;
    assert!(result.coverage.window_ended);
    let tracking = result.tracking.unwrap();
    assert_eq!(tracking.status, "completed");
    assert_eq!(tracking.ends_at, start + Duration::minutes(60));
}

#[tokio::test]
async fn restart_reopens_persistent_jobs_without_resetting_their_window() {
    let temporary = temporary::CacheDir::new();
    std::fs::create_dir_all(temporary.path()).unwrap();
    let options = SqliteConnectOptions::new()
        .filename(temporary.path().join("tracking.db"))
        .create_if_missing(true)
        .foreign_keys(true);
    let first = SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(options.clone())
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&first).await.unwrap();
    let start = now();
    trigger(&first, "AAPL", start).await;
    first.close().await;
    let reopened = SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(options)
        .await
        .unwrap();
    let provider = Arc::new(Provider::default());
    let time = Arc::new(Mutex::new(start + Duration::minutes(10)));
    *provider.reply.lock().unwrap() = Some(quote("AAPL", *time.lock().unwrap(), 110.0));
    let service = collector(&reopened, &provider, &time);
    assert_eq!(service.collect_once().await.unwrap(), 1);
    assert_eq!(
        response(&reopened, "AAPL", clock_time(&time))
            .await
            .tracking
            .unwrap()
            .ends_at,
        start + Duration::minutes(60)
    );
    *time.lock().unwrap() = start + Duration::minutes(61);
    assert_eq!(service.collect_once().await.unwrap(), 0);
    assert_eq!(provider.calls.lock().unwrap().len(), 1);
    drop(service);
    reopened.close().await;
}

#[tokio::test]
async fn provider_failure_does_not_stop_other_tickers_or_leak_credentials() {
    let pool = pool().await;
    let start = now();
    trigger(&pool, "AAPL", start).await;
    trigger(&pool, "MSFT", start).await;
    let provider = Arc::new(Provider::default());
    let time = Arc::new(Mutex::new(start + Duration::seconds(10)));
    provider
        .overrides
        .lock()
        .unwrap()
        .insert("MSFT".into(), quote("MSFT", clock_time(&time), 200.0));
    let service = collector(&pool, &provider, &time);
    assert_eq!(service.collect_once().await.unwrap(), 1);
    let result = response(&pool, "AAPL", clock_time(&time)).await;
    assert_eq!(
        result.tracking.unwrap().last_error.as_deref(),
        Some("stock_quote_unavailable")
    );
    assert_eq!(
        response(&pool, "MSFT", clock_time(&time))
            .await
            .snapshots
            .len(),
        1
    );
    assert_eq!(provider.calls.lock().unwrap().len(), 2);
}

#[tokio::test]
async fn bounded_batches_are_fair_and_shared_tickers_are_requested_once_per_iteration() {
    let pool = pool().await;
    let start = now();
    for index in 0..35 {
        trigger(&pool, &format!("S{index}"), start).await;
    }
    let provider = Arc::new(Provider::default());
    let time = Arc::new(Mutex::new(start + Duration::seconds(10)));
    let service = collector(&pool, &provider, &time);
    service.collect_once().await.unwrap();
    assert_eq!(provider.calls.lock().unwrap().len(), 32);
    *time.lock().unwrap() += Duration::seconds(15);
    service.collect_once().await.unwrap();
    let distinct: std::collections::HashSet<_> =
        provider.calls.lock().unwrap().iter().cloned().collect();
    assert_eq!(distinct.len(), 35);
    let pool = self::pool().await;
    trigger(&pool, "AAPL", start).await;
    let yesterday = start.date_naive() - Duration::days(1);
    observe(
        &pool,
        yesterday,
        &[event("AAPL", None)],
        start - Duration::seconds(65),
    )
    .await;
    assert_eq!(
        observe(&pool, yesterday, &[event("AAPL", Some("1"))], start).await,
        1
    );
    provider.calls.lock().unwrap().clear();
    *provider.reply.lock().unwrap() = Some(quote("AAPL", *time.lock().unwrap(), 101.0));
    assert_eq!(
        collector(&pool, &provider, &time)
            .collect_once()
            .await
            .unwrap(),
        2
    );
    assert_eq!(provider.calls.lock().unwrap().len(), 1);
}

#[tokio::test]
async fn retention_prunes_old_observations_and_cascades_their_stock_samples() {
    let pool = pool().await;
    let start = now();
    trigger(&pool, "AAPL", start).await;
    let id = response(&pool, "AAPL", start).await.tracking.unwrap().id;
    save_quote(
        &pool,
        id,
        &quote("AAPL", start + Duration::seconds(10), 100.0),
        start + Duration::seconds(10),
    )
    .await
    .unwrap();
    let provider = Arc::new(Provider::default());
    let time = Arc::new(Mutex::new(start + Duration::days(121)));
    collector(&pool, &provider, &time)
        .collect_once()
        .await
        .unwrap();
    for table in [
        "earnings_release_watch",
        "earnings_stock_tracking",
        "earnings_stock_snapshot",
    ] {
        let count: i64 = sqlx::query_scalar(&format!("SELECT COUNT(*) FROM {table}"))
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(count, 0);
    }
    assert!(provider.calls.lock().unwrap().is_empty());
}

#[tokio::test]
async fn ticker_currency_change_is_not_merged_with_existing_prices() {
    let pool = pool().await;
    let start = now();
    trigger(&pool, "AAPL", start).await;
    let provider = Arc::new(Provider::default());
    let time = Arc::new(Mutex::new(start + Duration::seconds(10)));
    let service = collector(&pool, &provider, &time);
    *provider.reply.lock().unwrap() = Some(quote("AAPL", *time.lock().unwrap(), 101.0));
    assert_eq!(service.collect_once().await.unwrap(), 1);
    *time.lock().unwrap() = start + Duration::seconds(20);
    let mut changed = quote("AAPL", *time.lock().unwrap(), 500.0);
    changed.currency = Some("EUR".into());
    *provider.reply.lock().unwrap() = Some(changed);
    assert_eq!(service.collect_once().await.unwrap(), 0);
    let result = response(&pool, "AAPL", clock_time(&time)).await;
    assert_eq!(result.snapshots[0].high, 101.0);
    assert_eq!(
        result.tracking.unwrap().last_error.as_deref(),
        Some("stock_currency_mismatch")
    );
}
