use super::*;
use serde_json::{Value, json};

fn fixture(now: DateTime<Utc>) -> Value {
    json!({"meta":{"symbol":"AAPL","instrumentType":"EQUITY","currency":"USD",
        "regularMarketPrice":101.5,"regularMarketTime":now.timestamp()-5},
        "timestamp":[],"indicators":{"quote":[{"close":[]}]}})
}
fn parse(root: Value, symbol: &str, now: DateTime<Utc>) -> Result<StockQuote, AppError> {
    stock_quote_from_chart(serde_json::from_value(root).unwrap(), symbol, now)
}
fn now() -> DateTime<Utc> {
    Utc.with_ymd_and_hms(2026, 11, 2, 22, 30, 30).unwrap()
}

#[test]
fn regular_equity_quotes_keep_their_real_timestamp_and_currency() {
    let time = now();
    let quote = parse(fixture(time), "AAPL", time).unwrap();
    assert_eq!(quote.price, 101.5);
    assert_eq!(quote.timestamp, time - chrono::Duration::seconds(5));
    assert_eq!(quote.currency.as_deref(), Some("USD"));
    assert!(matches!(
        quote.timestamp_basis,
        StockTimestampBasis::ProviderQuote
    ));
}

#[test]
fn extended_hours_use_only_closed_minute_bars_not_stale_regular_or_partial_bars() {
    let time = now();
    let mut root = fixture(time);
    root["meta"]["regularMarketTime"] = json!(time.timestamp() - 86400);
    root["timestamp"] = json!([time.timestamp() - 80, time.timestamp() - 20]);
    root["indicators"]["quote"][0]["close"] = json!([105.0, 999.0]);
    let quote = parse(root, "AAPL", time).unwrap();
    assert_eq!(quote.price, 105.0);
    assert_eq!(quote.timestamp, time - chrono::Duration::seconds(20));
    assert!(matches!(
        quote.timestamp_basis,
        StockTimestampBasis::MinuteBarClose
    ));
}

#[test]
fn explicit_pre_and_post_market_quotes_win_when_they_are_newer() {
    for prefix in ["pre", "post"] {
        let time = now();
        let mut root = fixture(time);
        root["meta"]["regularMarketTime"] = json!(time.timestamp() - 3600);
        root["meta"][format!("{prefix}MarketTime")] = json!(time.timestamp() - 2);
        root["meta"][format!("{prefix}MarketPrice")] = json!(103.0);
        let quote = parse(root, "AAPL", time).unwrap();
        assert_eq!(quote.price, 103.0);
        assert_eq!(quote.timestamp, time - chrono::Duration::seconds(2));
    }
}

#[test]
fn stale_future_invalid_and_missing_prices_do_not_get_stamped_as_current() {
    let time = now();
    for (timestamp, price) in [
        (time.timestamp() - 91, json!(100.0)),
        (time.timestamp() + 1, json!(100.0)),
        (time.timestamp(), json!(-1.0)),
        (time.timestamp(), Value::Null),
    ] {
        let mut root = fixture(time);
        root["meta"]["regularMarketTime"] = json!(timestamp);
        root["meta"]["regularMarketPrice"] = price;
        assert!(parse(root, "AAPL", time).is_err());
    }
}

#[test]
fn different_symbols_share_classes_or_non_equities_are_not_guessed() {
    let time = now();
    for (requested, actual, instrument) in [
        ("AAPL", "MSFT", "EQUITY"),
        ("BRK.B", "BRK-B", "EQUITY"),
        ("AAPL", "AAPL", "INDEX"),
    ] {
        let mut root = fixture(time);
        root["meta"]["symbol"] = json!(actual);
        root["meta"]["instrumentType"] = json!(instrument);
        assert!(parse(root, requested, time).is_err());
    }
}

#[tokio::test]
async fn stock_requests_include_extended_hours_and_share_rate_limit_with_macro_quotes() {
    use axum::{Router, http::StatusCode, routing::get};
    use std::sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    };
    let calls = Arc::new(AtomicUsize::new(0));
    let count = calls.clone();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let app = Router::new().fallback(get(move |uri: axum::http::Uri| {
        let count = count.clone();
        async move {
            assert_eq!(uri.path(), "/AAPL");
            let query = uri.query().unwrap();
            assert!(query.contains("includePrePost=true"));
            assert!(query.contains("interval=1m"));
            count.fetch_add(1, Ordering::SeqCst);
            (
                StatusCode::TOO_MANY_REQUESTS,
                [("retry-after", "60")],
                "limited",
            )
        }
    }));
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let provider = YahooProvider::new(reqwest::Client::builder().no_proxy().build().unwrap(), base);
    assert!(provider.stock_quote("../AAPL").await.is_err());
    assert_eq!(calls.load(Ordering::SeqCst), 0);
    assert!(provider.stock_quote("AAPL").await.is_err());
    assert!(provider.quote(MarketSymbol::Sp500).await.is_err());
    assert!(provider.stock_quote("MSFT").await.is_err());
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    task.abort();
}
