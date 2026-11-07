use super::*;
use crate::alert::test_support::TelegramMock;
use axum::{Router, body::Body, extract::State, http::Response, routing::any};
use chrono::{Duration, Utc};
use chrono_tz::America::New_York;
use serde_json::{Value, json};
use std::sync::Arc;
use tokio::sync::Mutex;

fn message(user: i64, text: &str) -> Value {
    json!({"message":{"from":{"id":user},"chat":{"id":user,"type":"private"},"text":text}})
}
fn callback(user: i64, data: &str) -> Value {
    json!({"callback_query":{"id":"api-callback","from":{"id":user},"message":{"chat":{"id":user,"type":"private"},"message_id":77},"data":data}})
}
async fn sent(mock: &TelegramMock) -> Vec<(String, Value)> {
    mock.requests.lock().await.clone()
}
fn keyboard(body: &Value) -> String {
    body["reply_markup"]["inline_keyboard"].to_string()
}

#[derive(Clone)]
struct Upstream(Arc<Mutex<Vec<String>>>);
async fn ticker(
    State(state): State<Upstream>,
    request: axum::http::Request<Body>,
) -> Response<Body> {
    let path = request.uri().path().to_owned();
    let symbol = request
        .uri()
        .query()
        .unwrap_or_default()
        .split('&')
        .find_map(|pair| pair.strip_prefix("symbol="))
        .unwrap_or_default()
        .to_owned();
    state.0.lock().await.push(format!("{path}?{symbol}"));
    let price = match symbol.as_str() {
        "BTCUSDT" => "64000.50",
        "ETHUSDT" => "3210.25",
        _ => return Response::builder().status(404).body(Body::empty()).unwrap(),
    };
    Response::new(Body::from(format!(
        r#"{{"symbol":"{symbol}","price":"{price}"}}"#
    )))
}
async fn fixture(mock: &TelegramMock) -> (BotState, Upstream) {
    let mut state = super::tests::fixture(mock).await;
    let upstream = Upstream(Arc::new(Mutex::new(Vec::new())));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let router = Router::new()
        .fallback(any(ticker))
        .with_state(upstream.clone());
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    state.config.market.binance_base_url = base;
    state.config.network.proxy_url.clear();
    (state, upstream)
}

#[tokio::test]
async fn one_command_opens_grouped_menus_without_any_upstream_request() {
    let mock = TelegramMock::start().await;
    let (state, upstream) = fixture(&mock).await;
    let date = Utc::now().with_timezone(&New_York).date_naive();
    handle_update(&state, &message(42, &format!("/test_api {date} brk.b")))
        .await
        .unwrap();
    let requests = sent(&mock).await;
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].0, "sendMessage");
    assert!(
        requests[0].1["text"]
            .as_str()
            .unwrap()
            .contains("API 连通性测试")
    );
    let buttons = keyboard(&requests[0].1);
    for expected in [
        "apitest:yahoo",
        "apitest:biquote",
        "apitest:binance",
        "apitest:cnbc",
        "apitest:tradingview",
        "apitest:biquote_calendar",
        "apitest:forexfactory",
        "apitest:tradingeconomics",
        "apitest:te_api",
        &format!("earnprobe:n:{date}:BRK.B"),
        &format!("earnprobe:l:{date}:BRK.B"),
    ] {
        assert!(buttons.contains(expected), "missing {expected}");
    }
    assert!(upstream.0.lock().await.is_empty());
}

#[tokio::test]
async fn bare_command_defaults_to_new_york_today_and_aapl() {
    let mock = TelegramMock::start().await;
    let (state, upstream) = fixture(&mock).await;
    let date = Utc::now().with_timezone(&New_York).date_naive();
    handle_update(&state, &message(42, "/test_api"))
        .await
        .unwrap();
    let requests = sent(&mock).await;
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].0, "sendMessage");
    let buttons = keyboard(&requests[0].1);
    for expected in [
        "apitest:yahoo",
        "apitest:binance",
        &format!("earnprobe:n:{date}:AAPL"),
        &format!("earnprobe:l:{date}:AAPL"),
    ] {
        assert!(buttons.contains(expected), "missing {expected}");
    }
    assert!(upstream.0.lock().await.is_empty());
}

#[tokio::test]
async fn market_buttons_only_accept_the_rendered_pairs_and_offer_a_back_button() {
    let mock = TelegramMock::start().await;
    let (state, upstream) = fixture(&mock).await;
    for command in [
        "/test_market_api",
        "/test_calendar_api",
        "/test_earnings_api",
    ] {
        handle_update(&state, &message(42, command)).await.unwrap();
    }
    let requests = sent(&mock).await;
    assert_eq!(requests.len(), 3);
    for (_, body) in &requests {
        assert!(keyboard(body).contains("earnprobe:"));
    }
    handle_update(&state, &callback(42, "apitest:binance"))
        .await
        .unwrap();
    let requests = sent(&mock).await;
    let (method, body) = requests.last().unwrap();
    assert_eq!(method, "editMessageText");
    assert!(
        body["text"]
            .as_str()
            .unwrap()
            .contains("请选择用于连通性测试的标的")
    );
    assert!(keyboard(body).contains("apiprobe:binance:bitcoin"));
    assert!(keyboard(body).contains("api_menu"));

    handle_update(&state, &callback(42, "apiprobe:binance:bitcoin"))
        .await
        .unwrap();
    let requests = sent(&mock).await;
    let (method, body) = requests.last().unwrap();
    assert_eq!(method, "editMessageText");
    assert_eq!(
        body["text"],
        "✅ Binance 连通正常\n标的：bitcoin\n当前价格：<code>64000.5</code>"
    );
    assert!(keyboard(body).contains("api_menu"));

    let calls = upstream.0.lock().await.len();
    for data in [
        "apiprobe:binance:gold",
        "apiprobe:unknown:bitcoin",
        "apiprobe:binance",
        "apiprobe:binance:bitcoin:extra",
        "apitest:unknown",
        "apitest:yahoo:extra",
        "apitest",
    ] {
        handle_update(&state, &callback(42, data)).await.unwrap();
    }
    assert_eq!(
        upstream.0.lock().await.len(),
        calls,
        "an invalid probe reached upstream"
    );
}

#[tokio::test]
async fn menu_navigation_validates_targets_and_never_probes_upstreams() {
    let mock = TelegramMock::start().await;
    let (state, upstream) = fixture(&mock).await;
    let date = Utc::now().with_timezone(&New_York).date_naive();
    handle_update(&state, &callback(42, "api_menu"))
        .await
        .unwrap();
    handle_update(&state, &callback(42, &format!("api_menu:{date}:AAPL")))
        .await
        .unwrap();
    handle_update(&state, &callback(42, &format!("api_menu:{date}:brk.b")))
        .await
        .unwrap();
    {
        let requests = sent(&mock).await;
        for (_, body) in requests
            .iter()
            .filter(|(method, _)| method == "editMessageText")
        {
            assert!(keyboard(body).contains("apitest:yahoo"));
        }
        let edits: Vec<_> = requests
            .iter()
            .filter(|(method, _)| method == "editMessageText")
            .collect();
        assert_eq!(edits.len(), 3);
        assert!(
            edits[0].1["reply_markup"]
                .to_string()
                .contains("earnprobe:n:")
        );
        assert!(edits[2].1["reply_markup"].to_string().contains("BRK.B"));
    }
    let before = sent(&mock).await.len();
    for data in [
        "api_menu:bad:AAPL".to_owned(),
        format!("api_menu:{}:AAPL:extra", date - Duration::days(200)),
        "api_menu:../AAPL".to_owned(),
        "api_menu:".to_owned(),
        format!("api_menu:{date}:A12345678901234567890123456789012"),
    ] {
        handle_update(&state, &callback(42, &data)).await.unwrap();
    }
    let requests = sent(&mock).await;
    assert!(
        requests[before..]
            .iter()
            .all(|(method, _)| method == "answerCallbackQuery")
    );
    assert!(upstream.0.lock().await.is_empty());
}

#[tokio::test]
async fn non_admin_spoofed_group_and_malformed_requests_never_touch_upstreams() {
    let mock = TelegramMock::start().await;
    let (state, upstream) = fixture(&mock).await;
    handle_update(&state, &message(99, "/test_api"))
        .await
        .unwrap();
    handle_update(&state, &callback(99, "apitest:binance"))
        .await
        .unwrap();
    handle_update(&state, &callback(99, "apiprobe:binance:bitcoin"))
        .await
        .unwrap();
    let mut spoofed = callback(42, "apiprobe:binance:bitcoin");
    spoofed["callback_query"]["from"]["id"] = json!(99);
    handle_update(&state, &spoofed).await.unwrap();
    spoofed["callback_query"]["from"]["id"] = json!(42);
    spoofed["callback_query"]["message"]["chat"]["type"] = json!("group");
    handle_update(&state, &spoofed).await.unwrap();
    for command in [
        format!("/test_api 2026-11-01 {}", "A".repeat(40)),
        "/test_api 2026-11-01 AAPL extra".into(),
        "/test_api 2026-11-01 ../AAPL".into(),
        format!(
            "/test_api {}",
            Utc::now().with_timezone(&New_York).date_naive() + Duration::days(61)
        ),
    ] {
        handle_update(&state, &message(42, &command)).await.unwrap();
    }
    assert!(
        sent(&mock)
            .await
            .iter()
            .all(|(method, body)| { method == "sendMessage" && body["reply_markup"].is_null() })
    );
    assert!(upstream.0.lock().await.is_empty());
}
