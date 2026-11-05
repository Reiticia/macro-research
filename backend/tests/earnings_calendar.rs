use axum::{
    Json, Router,
    extract::State,
    http::StatusCode,
    routing::{get, post},
};
use chrono::{Duration, Utc};
use chrono_tz::America::New_York;
use market_event_analyzer::earnings::{
    EarningsService, merge_tradingview, parse_nasdaq, parse_tradingview,
};
use serde_json::{Value, json};
use sqlx::sqlite::SqlitePoolOptions;
use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};
use tokio::sync::Mutex;

#[derive(Clone)]
struct Fixture {
    nasdaq: Arc<Mutex<(StatusCode, Value)>>,
    tv: Arc<Mutex<(StatusCode, Value)>>,
    nasdaq_calls: Arc<AtomicUsize>,
    tv_calls: Arc<AtomicUsize>,
}
struct Server {
    base: String,
    task: tokio::task::JoinHandle<()>,
    fixture: Fixture,
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
    }
}
async fn nasdaq(State(f): State<Fixture>) -> (StatusCode, Json<Value>) {
    f.nasdaq_calls.fetch_add(1, Ordering::SeqCst);
    let (status, body) = f.nasdaq.lock().await.clone();
    (status, Json(body))
}
async fn tv(State(f): State<Fixture>) -> (StatusCode, Json<Value>) {
    f.tv_calls.fetch_add(1, Ordering::SeqCst);
    let (status, body) = f.tv.lock().await.clone();
    (status, Json(body))
}
async fn setup() -> (Server, sqlx::SqlitePool, chrono::NaiveDate) {
    let date = Utc::now().with_timezone(&New_York).date_naive() + Duration::days(1);
    let ts = New_York
        .from_local_datetime(&date.and_hms_opt(16, 30, 0).unwrap())
        .single()
        .unwrap()
        .timestamp();
    let fixture = Fixture {
        nasdaq: Arc::new(Mutex::new((
            StatusCode::OK,
            json!({"status":{"rCode":200},"data":{"asOf":date.format("%a, %b %-d, %Y").to_string(),"rows":[{"symbol":"AAPL","name":"Apple","time":"time-after-hours","epsForecast":"$0"}]}}),
        ))),
        tv: Arc::new(Mutex::new((
            StatusCode::OK,
            json!({"totalCount":1,"data":[{"s":"NASDAQ:AAPL","d":["AAPL","Apple",ts,1.9,1000,"USD"]}]}),
        ))),
        nasdaq_calls: Arc::new(AtomicUsize::new(0)),
        tv_calls: Arc::new(AtomicUsize::new(0)),
    };
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let app = Router::new()
        .route("/nasdaq", get(nasdaq))
        .route("/tv", post(tv))
        .with_state(fixture.clone());
    let task = tokio::spawn(async move {
        axum::serve(listener, app).await.unwrap();
    });
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    (
        Server {
            base,
            task,
            fixture,
        },
        pool,
        date,
    )
}
use chrono::TimeZone;
fn service(server: &Server, pool: &sqlx::SqlitePool) -> EarningsService {
    let http = reqwest::Client::builder().no_proxy().build().unwrap();
    EarningsService::new(pool.clone(), http.clone(), http).with_urls(
        format!("{}/nasdaq", server.base),
        format!("{}/tv", server.base),
    )
}

#[tokio::test]
async fn calendar_eps_updates_do_not_create_price_jobs_or_modify_retired_history() {
    let (server, pool, _) = setup().await;
    let date = Utc::now().with_timezone(&New_York).date_naive();
    let last_seen = Utc::now().timestamp() - 65;
    // Simulate an installation upgraded while release/opening jobs and observations existed.
    sqlx::query("INSERT INTO earnings_release_watch(date,symbol,fiscal_quarter_ending,last_seen_at,observation_status) VALUES(?,'AAPL','Sep/2026',?,'watching')")
        .bind(date.to_string()).bind(last_seen).execute(&pool).await.unwrap();
    sqlx::query("INSERT INTO earnings_stock_tracking(id,date,symbol,company_name,actual_eps,detected_at,ends_at) VALUES(1,'2020-01-01','OLD','Old company','1',1000,4600)")
        .execute(&pool).await.unwrap();
    sqlx::query("INSERT INTO earnings_stock_opening(id,starts_at,ends_at) VALUES(1,10000,13600)")
        .execute(&pool)
        .await
        .unwrap();
    for (table, timestamp) in [
        ("earnings_stock_snapshot", 1000),
        ("earnings_stock_opening_snapshot", 10000),
    ] {
        sqlx::query(&format!("INSERT INTO {table}(tracking_id,timestamp,first_quote_at,last_quote_at,open,high,low,close,timestamp_basis) VALUES(1,?, ?,?,100,101,99,100,'provider_quote')"))
            .bind(timestamp).bind(timestamp).bind(timestamp + 30).execute(&pool).await.unwrap();
    }
    server.fixture.nasdaq.lock().await.1 = json!({"status":{"rCode":200},"data":{"asOf":date.format("%a, %b %-d, %Y").to_string(),"rows":[{"symbol":"AAPL","name":"Apple","time":"time-after-hours","fiscalQuarterEnding":"Sep/2026","eps":"$0"}]}});
    let earnings = service(&server, &pool);
    let result = earnings.calendar(date, false).await.unwrap();
    assert_eq!(result.events[0].actual_eps.as_deref(), Some("0"));
    assert_eq!(server.fixture.nasdaq_calls.load(Ordering::SeqCst), 1);
    assert_eq!(server.fixture.tv_calls.load(Ordering::SeqCst), 0);
    assert_eq!(
        earnings.cached(date).await.unwrap().unwrap().events[0]
            .actual_eps
            .as_deref(),
        Some("0")
    );

    let new_jobs: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM earnings_stock_tracking WHERE symbol='AAPL'")
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(new_jobs, 0);
    let observed: (i64, String, Option<String>) = sqlx::query_as("SELECT last_seen_at, observation_status, actual_eps FROM earnings_release_watch WHERE symbol='AAPL'")
        .fetch_one(&pool).await.unwrap();
    assert_eq!(observed, (last_seen, "watching".into(), None));

    // Even records older than the old retention window remain untouched, including both snapshots.
    for table in [
        "earnings_stock_tracking",
        "earnings_stock_opening",
        "earnings_stock_snapshot",
        "earnings_stock_opening_snapshot",
    ] {
        let count: i64 = sqlx::query_scalar(&format!("SELECT COUNT(*) FROM {table}"))
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(count, 1, "{table}");
    }
    sqlx::migrate!("./migrations").run(&pool).await.unwrap(); // Published migrations remain valid.
    assert_eq!(
        service(&server, &pool)
            .calendar(date, false)
            .await
            .unwrap()
            .events[0]
            .actual_eps
            .as_deref(),
        Some("0")
    );
}

#[test]
fn shared_fixtures_preserve_precision_and_source_separation() {
    let day = chrono::NaiveDate::from_ymd_opt(2026, 11, 2).unwrap();
    let mut events = parse_nasdaq(
        &serde_json::from_str(include_str!(
            "../../android/app/src/test/resources/earnings/nasdaq.json"
        ))
        .unwrap(),
        day,
    )
    .unwrap();
    let secondary = parse_tradingview(
        &serde_json::from_str(include_str!(
            "../../android/app/src/test/resources/earnings/tradingview.json"
        ))
        .unwrap(),
        day,
    )
    .unwrap();
    merge_tradingview(&mut events, secondary);
    let loss = events.iter().find(|e| e.symbol == "LOSS").unwrap();
    assert_eq!(loss.actual_eps.as_deref(), Some("0"));
    assert_eq!(loss.eps_forecast.as_deref(), Some("-0.25"));
    assert_eq!(loss.session, "unknown");
    assert!(loss.trading_view.is_none()); // never attach next-period forecasts to reported EPS
    let apple = events.iter().find(|e| e.symbol == "AAPL").unwrap();
    assert_eq!(apple.eps_forecast.as_deref(), Some("1.98"));
    assert_eq!(
        apple
            .trading_view
            .as_ref()
            .unwrap()
            .next_eps_forecast
            .as_deref(),
        Some("1.989719")
    );
}

#[tokio::test]
async fn concurrent_callers_share_persistent_cache_and_cooldown() {
    let (server, pool, date) = setup().await;
    let service = Arc::new(service(&server, &pool));
    let (a, b) = tokio::join!(service.calendar(date, false), service.calendar(date, false));
    assert_eq!(a.unwrap().events.len(), 1);
    assert_eq!(b.unwrap().events.len(), 1);
    assert_eq!(server.fixture.nasdaq_calls.load(Ordering::SeqCst), 1);
    assert_eq!(server.fixture.tv_calls.load(Ordering::SeqCst), 1);
    let cooldown = service.calendar(date, true).await.unwrap();
    assert!(cooldown.warnings.iter().any(|w| w == "refresh_cooldown"));
    assert_eq!(
        service.cached(date).await.unwrap().unwrap().events[0]
            .eps_forecast
            .as_deref(),
        Some("0")
    );
}

#[tokio::test]
async fn malformed_primary_cannot_erase_a_cached_calendar() {
    let (server, pool, date) = setup().await;
    let original = service(&server, &pool).calendar(date, false).await.unwrap();
    *server.fixture.nasdaq.lock().await = (StatusCode::OK, json!({"data":{"rows":[]}}));
    let restored = service(&server, &pool).calendar(date, true).await.unwrap();
    assert!(restored.stale);
    assert_eq!(restored.updated_at, original.updated_at);
    assert_eq!(restored.events.len(), 1);
    assert!(restored.warnings.iter().any(|w| w == "nasdaq_unavailable"));
}

#[tokio::test]
async fn tradingview_fallback_never_invents_a_session_or_fiscal_quarter() {
    let (server, pool, date) = setup().await;
    *server.fixture.nasdaq.lock().await = (StatusCode::SERVICE_UNAVAILABLE, json!({}));
    let result = service(&server, &pool).calendar(date, false).await.unwrap();
    assert_eq!(result.events[0].source, "tradingview");
    assert_eq!(result.events[0].session, "unknown");
    assert!(result.events[0].fiscal_quarter_ending.is_none());
    assert!(result.events[0].eps_forecast.is_none());
    assert!(result.nasdaq_updated_at.is_none());
}

#[tokio::test]
async fn valid_empty_day_replaces_cache_but_total_failure_is_not_empty_success() {
    let (server, pool, date) = setup().await;
    let original = service(&server, &pool).calendar(date, false).await.unwrap();
    server.fixture.nasdaq.lock().await.1["data"]["rows"] = Value::Null;
    let empty = service(&server, &pool).calendar(date, true).await.unwrap();
    assert!(empty.events.is_empty());
    assert!(!empty.stale);
    assert_eq!(original.events.len(), 1);
    *server.fixture.nasdaq.lock().await = (StatusCode::BAD_GATEWAY, json!({}));
    *server.fixture.tv.lock().await = (StatusCode::BAD_GATEWAY, json!({}));
    let fresh_pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations")
        .run(&fresh_pool)
        .await
        .unwrap();
    assert!(
        service(&server, &fresh_pool)
            .calendar(date, false)
            .await
            .is_err()
    );
}

#[tokio::test]
async fn truncated_secondary_is_explicit_and_invalid_dates_do_not_fetch() {
    let (server, pool, date) = setup().await;
    server.fixture.tv.lock().await.1["totalCount"] = json!(2001);
    let service = service(&server, &pool);
    let result = service.calendar(date, false).await.unwrap();
    assert!(
        result
            .warnings
            .iter()
            .any(|w| w == "tradingview_unavailable")
    );
    assert!(result.events[0].trading_view.is_none());
    assert!(
        service
            .calendar(date + Duration::days(100), false)
            .await
            .is_err()
    );
    assert_eq!(server.fixture.nasdaq_calls.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn wrong_date_fallback_is_an_error_not_an_empty_day() {
    let (server, pool, date) = setup().await;
    *server.fixture.nasdaq.lock().await = (StatusCode::SERVICE_UNAVAILABLE, json!({}));
    let next = date + Duration::days(1);
    server.fixture.tv.lock().await.1["data"][0]["d"][2] = json!(
        New_York
            .from_local_datetime(&next.and_hms_opt(16, 30, 0).unwrap())
            .single()
            .unwrap()
            .timestamp()
    );
    assert!(service(&server, &pool).calendar(date, false).await.is_err());
}

#[tokio::test]
async fn fallback_only_cache_can_refresh_from_its_own_healthy_source() {
    let (server, pool, date) = setup().await;
    *server.fixture.nasdaq.lock().await = (StatusCode::SERVICE_UNAVAILABLE, json!({}));
    let original = service(&server, &pool).calendar(date, false).await.unwrap();
    assert!(original.nasdaq_updated_at.is_none());
    server.fixture.tv.lock().await.1["data"][0]["d"][3] = json!(2.5);
    let refreshed = service(&server, &pool).calendar(date, true).await.unwrap();
    assert!(!refreshed.stale);
    assert_eq!(
        refreshed.events[0]
            .trading_view
            .as_ref()
            .unwrap()
            .next_eps_forecast
            .as_deref(),
        Some("2.5")
    );
}

#[tokio::test]
async fn historical_or_reported_occurrences_do_not_use_next_period_forecasts() {
    for reported in [false, true] {
        let (server, pool, _) = setup().await;
        let today = Utc::now().with_timezone(&New_York).date_naive();
        let date = today - Duration::days(if reported { 0 } else { 1 });
        {
            let mut response = server.fixture.nasdaq.lock().await;
            response.1["data"]["asOf"] = json!(date.format("%a, %b %-d, %Y").to_string());
            if reported {
                response.1["data"]["rows"][0]["eps"] = json!("$1.50");
            }
        }
        let result = service(&server, &pool).calendar(date, false).await.unwrap();
        assert_eq!(server.fixture.tv_calls.load(Ordering::SeqCst), 0);
        assert!(result.trading_view_updated_at.is_none());
        assert!(result.events[0].trading_view.is_none());
        assert!(!result.warnings.iter().any(|w| w == "date_conflict"));
    }
}

#[tokio::test]
async fn blocked_provider_has_cross_date_backoff() {
    let (server, pool, date) = setup().await;
    *server.fixture.nasdaq.lock().await = (StatusCode::TOO_MANY_REQUESTS, json!({}));
    let service = service(&server, &pool);
    assert!(service.calendar(date, false).await.is_ok());
    // The next date uses the secondary, but must not hit the rate-limited Nasdaq again.
    let next = date + Duration::days(1);
    server.fixture.tv.lock().await.1["data"][0]["d"][2] = json!(
        New_York
            .from_local_datetime(&next.and_hms_opt(16, 30, 0).unwrap())
            .single()
            .unwrap()
            .timestamp()
    );
    service
        .calendar(date + Duration::days(1), false)
        .await
        .unwrap();
    assert_eq!(server.fixture.nasdaq_calls.load(Ordering::SeqCst), 1);
}
