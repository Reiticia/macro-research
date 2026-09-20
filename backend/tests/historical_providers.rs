use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};

use axum::{
    Json, Router,
    extract::{Query, State},
    http::StatusCode,
    routing::get,
};
use chrono::{Duration, TimeZone, Utc};
use market_event_analyzer::{
    calendar::{CalendarProvider, TradingEconomicsApiProvider},
    market::{BinanceProvider, BiquoteProvider, MarketDataProvider, YahooProvider},
    model::{Interval, MarketSymbol},
};
use serde::Deserialize;

async fn serve(router: Router) -> (String, tokio::task::JoinHandle<()>) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let handle = tokio::spawn(async move {
        axum::serve(listener, router).await.unwrap();
    });
    (url, handle)
}

#[tokio::test]
async fn calendar_errors_never_disclose_credentials() {
    let router = Router::new().route(
        "/calendar/country/All/{from}/{to}",
        get(|| async { StatusCode::FORBIDDEN }),
    );
    let (url, task) = serve(router).await;
    let provider =
        TradingEconomicsApiProvider::new(reqwest::Client::new(), &url, "secret-api-key".into())
            .unwrap();
    let start = Utc.with_ymd_and_hms(2026, 6, 8, 0, 0, 0).unwrap();
    let error = provider
        .fetch_events(start, start + Duration::days(1))
        .await
        .unwrap_err();
    assert!(!format!("{error:?}").contains("secret-api-key"));
    assert!(!error.to_string().contains("secret-api-key"));
    task.abort();
}

#[tokio::test]
async fn historical_api_rejects_out_of_range_response() {
    let router = Router::new().route("/calendar/country/All/{from}/{to}", get(|| async { Json(serde_json::json!([
        {"CalendarId": 1,"Date":"2026-09-08T12:30:00Z","Country":"United States","Category":"Inflation","Event":"CPI","DateSpan":0}
    ])) }));
    let (url, task) = serve(router).await;
    let provider =
        TradingEconomicsApiProvider::new(reqwest::Client::new(), &url, "test-key".into()).unwrap();
    let start = Utc.with_ymd_and_hms(2026, 6, 8, 0, 0, 0).unwrap();
    assert!(
        provider
            .fetch_events(start, start + Duration::days(1))
            .await
            .is_err()
    );
    task.abort();
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct KlineQuery {
    start_time: i64,
    end_time: i64,
}

#[tokio::test]
async fn binance_paginates_beyond_one_thousand_candles() {
    let count = Arc::new(AtomicUsize::new(0));
    let start = Utc.with_ymd_and_hms(2026, 6, 8, 0, 0, 0).unwrap();
    let epoch = start.timestamp_millis();
    let router = Router::new().route("/klines", get(move |State(calls): State<Arc<AtomicUsize>>, Query(q): Query<KlineQuery>| async move {
        calls.fetch_add(1, Ordering::SeqCst);
        let first = ((q.start_time - epoch) + 59999) / 60000;
        let rows: Vec<_> = (first..1002).take(1000).filter(|i| epoch + i * 60000 <= q.end_time)
            .map(|i| serde_json::json!([epoch+i*60000,"100","101","99","100","1"])) .collect();
        Json(rows)
    })).with_state(count.clone());
    let (url, task) = serve(router).await;
    let provider = BinanceProvider::new(reqwest::Client::new(), url);
    let candles = provider
        .candles(
            MarketSymbol::Bitcoin,
            start,
            start + Duration::minutes(1002),
            Interval::OneMinute,
        )
        .await
        .unwrap();
    assert_eq!(candles.len(), 1002);
    assert_eq!(count.load(Ordering::SeqCst), 2);
    assert_eq!(
        candles.last().unwrap().timestamp,
        start + Duration::minutes(1001)
    );
    task.abort();
}

#[tokio::test]
async fn biquote_parses_quote_freshness_and_closed_candles() {
    let router = Router::new()
        .route(
            "/api/EURUSD",
            get(|| async {
                Json(serde_json::json!({
                    "symbol": "EURUSD",
                    "mid": 1.10525,
                    "high": 1.11,
                    "low": 1.10,
                    "dayDiffPercent": 0.25,
                    "timestamp": "2026-09-09T08:30:00Z",
                    "marketState": "open",
                    "stale": false
                }))
            }),
        )
        .route(
            "/api/XAUUSD/ohlc",
            get(|| async {
                Json(serde_json::json!({
                    "symbol": "XAUUSD",
                    "interval": "1m",
                    "bars": [
                        {"openTime":"2026-09-09T08:29:00Z","open":2500.0,"high":2501.0,"low":2499.0,"close":2500.5,"volume":0,"tickVolume":42,"isOpen":false},
                        {"openTime":"2026-09-09T08:30:00Z","open":2500.5,"high":2502.0,"low":2500.0,"close":2501.0,"volume":0,"tickVolume":10,"isOpen":true}
                    ]
                }))
            }),
        );
    let (url, task) = serve(router).await;
    let provider = BiquoteProvider::new(reqwest::Client::new(), url);

    let quote = provider.live_quote(MarketSymbol::EurUsd).await.unwrap();
    assert_eq!(quote.price, 1.10525);
    assert_eq!(quote.change_percent, Some(0.25));
    assert_eq!(quote.market_state.as_deref(), Some("open"));
    assert!(!quote.stale);

    let start = Utc.with_ymd_and_hms(2026, 9, 9, 8, 29, 0).unwrap();
    let candles = provider
        .candles(
            MarketSymbol::Gold,
            start,
            start + Duration::minutes(2),
            Interval::OneMinute,
        )
        .await
        .unwrap();
    assert_eq!(candles.len(), 1);
    assert_eq!(candles[0].close, 2500.5);
    assert_eq!(candles[0].volume, Some(0.0));
    task.abort();
}

#[tokio::test]
async fn yahoo_rate_limit_is_shared_across_symbols() {
    let calls = Arc::new(AtomicUsize::new(0));
    let router = Router::new()
        .route(
            "/{symbol}",
            get(|State(calls): State<Arc<AtomicUsize>>| async move {
                calls.fetch_add(1, Ordering::SeqCst);
                (
                    StatusCode::TOO_MANY_REQUESTS,
                    [("retry-after", "60")],
                    "rate limited",
                )
            }),
        )
        .with_state(calls.clone());
    let (url, task) = serve(router).await;
    let provider = YahooProvider::new(reqwest::Client::new(), url);

    assert!(provider.live_quote(MarketSymbol::Nasdaq100).await.is_err());
    assert!(provider.live_quote(MarketSymbol::Sp500).await.is_err());
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    task.abort();
}
