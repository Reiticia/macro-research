use super::*;
use crate::{
    alert::test_support::TelegramMock, earnings::EarningsService,
    earnings_logo::CompanyLogoService, error::AppError,
};
use axum::{
    Router,
    body::{Body, to_bytes},
    extract::State,
    http::{Request, Response, StatusCode},
    routing::any,
};
use chrono::{Duration, NaiveDate, Utc};
use chrono_tz::America::New_York;
use serde_json::{Value, json};
use std::collections::HashMap;
use tokio::sync::Mutex;

use crate::alert::test_support::temporary as logo_cache;
use logo_cache::CacheDir;
const PNG: &[u8] = include_bytes!("../../../android/app/src/test/resources/earnings/logo.png");

#[derive(Clone)]
struct Reply {
    status: StatusCode,
    mime: &'static str,
    body: Vec<u8>,
}
type CapturedRequest = (String, String, Vec<u8>);

#[derive(Clone)]
struct UpstreamState {
    replies: Arc<Mutex<HashMap<String, Reply>>>,
    requests: Arc<Mutex<Vec<CapturedRequest>>>,
}
struct Upstream {
    base: String,
    state: UpstreamState,
    task: tokio::task::JoinHandle<()>,
}
impl Drop for Upstream {
    fn drop(&mut self) {
        self.task.abort();
    }
}
async fn asset(State(state): State<UpstreamState>, request: Request<Body>) -> Response<Body> {
    let path = request.uri().path().to_owned();
    let query = request.uri().query().unwrap_or_default().to_owned();
    assert!(request.headers().get("authorization").is_none());
    assert!(request.headers().get("x-device-id").is_none());
    let body = to_bytes(request.into_body(), 65536).await.unwrap().to_vec();
    state
        .requests
        .lock()
        .await
        .push((path.clone(), query, body));
    let key = if path.starts_with("/symbol/") {
        "logo"
    } else {
        path.as_str()
    };
    let reply = state.replies.lock().await.get(key).unwrap().clone();
    Response::builder()
        .status(reply.status)
        .header("content-type", reply.mime)
        .header("retry-after", "900")
        .body(Body::from(reply.body))
        .unwrap()
}
async fn setup() -> (TelegramMock, BotState, Upstream, CacheDir, NaiveDate) {
    let telegram = TelegramMock::start().await;
    let mut state = super::tests::fixture(&telegram).await;
    let date = Utc::now().with_timezone(&New_York).date_naive();
    let upstream_state = UpstreamState {
        replies: Arc::new(Mutex::new(HashMap::from([
            ("/nasdaq".into(), Reply { status: StatusCode::OK, mime: "application/json", body: json!({"status":{"rCode":200},"data":{"asOf":date.format("%a, %b %-d, %Y").to_string(),"rows":[{"symbol":"AAPL","name":"Apple","time":"time-after-hours"}]}}).to_string().into_bytes() }),
            ("/tv".into(), Reply { status: StatusCode::OK, mime: "application/json", body: br#"{"totalCount":0,"data":[]}"#.to_vec() }),
            ("logo".into(), Reply { status: StatusCode::OK, mime: "image/png", body: PNG.to_vec() }),
        ]))),
        requests: Arc::new(Mutex::new(Vec::new())),
    };
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let router = Router::new()
        .fallback(any(asset))
        .with_state(upstream_state.clone());
    let task = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    let upstream = Upstream {
        base,
        state: upstream_state,
        task,
    };
    let cache = CacheDir::new();
    let http = reqwest::Client::builder().no_proxy().build().unwrap();
    state.earnings = Arc::new(
        EarningsService::new(state.pool.clone(), http.clone(), http.clone())
            .with_urls(
                format!("{}/nasdaq", upstream.base),
                format!("{}/tv", upstream.base),
            )
            .with_health(state.health.clone())
            .with_logos(
                CompanyLogoService::new(http)
                    .with_base(format!("{}/symbol", upstream.base))
                    .with_cache_dir(cache.path()),
            ),
    );
    (telegram, state, upstream, cache, date)
}
fn message(user: i64, text: &str) -> Value {
    json!({"message":{"from":{"id":user},"chat":{"id":user,"type":"private"},"text":text}})
}
fn callback(user: i64, data: &str) -> Value {
    json!({"callback_query":{"id":"probe-callback","from":{"id":user},"message":{"chat":{"id":user,"type":"private"},"message_id":77},"data":data}})
}
async fn probe(state: &BotState, source: &str, date: NaiveDate, symbol: &str) {
    handle_update(
        state,
        &callback(42, &format!("earnprobe:{source}:{date}:{symbol}")),
    )
    .await
    .unwrap();
}
async fn last_reply(telegram: &TelegramMock) -> String {
    telegram
        .requests
        .lock()
        .await
        .iter()
        .rev()
        .find(|(method, _)| method == "editMessageText" || method == "sendMessage")
        .unwrap()
        .1["text"]
        .as_str()
        .unwrap()
        .to_owned()
}
async fn set_reply(
    upstream: &Upstream,
    key: &str,
    status: StatusCode,
    mime: &'static str,
    body: Vec<u8>,
) {
    upstream
        .state
        .replies
        .lock()
        .await
        .insert(key.into(), Reply { status, mime, body });
}

#[tokio::test]
async fn earnings_tests_are_in_unified_menu_help_and_admin_command_menu() {
    let (telegram, state, upstream, _cache, date) = setup().await;
    for command in ["/help", "/test_api", "/test_earnings_api@our_bot"] {
        handle_update(&state, &message(42, command)).await.unwrap();
    }
    state.telegram.set_admin_commands(&[42]).await.unwrap();
    let requests = telegram.requests.lock().await;
    assert!(
        requests[0].1["text"]
            .as_str()
            .unwrap()
            .contains("/test_api")
    );
    for index in [1, 2] {
        let keyboard = requests[index].1["reply_markup"]["inline_keyboard"].to_string();
        assert!(keyboard.contains(&format!("earnprobe:n:{date}:AAPL")));
        assert!(keyboard.contains("earnprobe:t:"));
        assert!(keyboard.contains("earnprobe:l:"));
    }
    assert!(
        requests[3].1["commands"]
            .as_array()
            .unwrap()
            .iter()
            .any(|c| c["command"] == "test_api")
    );
    assert!(upstream.state.requests.lock().await.is_empty());
}

#[tokio::test]
async fn commands_normalize_symbols_and_bound_callback_lengths() {
    let (telegram, state, upstream, _cache, date) = setup().await;
    for symbol in ["brk.b", "A1234567890123456789012345678901"] {
        handle_update(&state, &message(42, &format!("/test_api {date} {symbol}")))
            .await
            .unwrap();
        let requests = telegram.requests.lock().await;
        let keyboard = requests.last().unwrap().1["reply_markup"]["inline_keyboard"]
            .as_array()
            .unwrap();
        for row in keyboard {
            for button in row.as_array().unwrap() {
                let data = button["callback_data"].as_str().unwrap();
                assert!(data.len() <= 64);
                if data.starts_with("earnprobe:") {
                    assert!(data.ends_with(&symbol.to_ascii_uppercase()));
                }
            }
        }
    }
    assert!(upstream.state.requests.lock().await.is_empty());
}

#[tokio::test]
async fn non_admin_group_spoofed_and_malformed_requests_never_fetch_upstreams() {
    let (telegram, state, upstream, _cache, date) = setup().await;
    handle_update(&state, &message(99, "/test_earnings_api"))
        .await
        .unwrap();
    handle_update(&state, &callback(99, &format!("earnprobe:n:{date}:AAPL")))
        .await
        .unwrap();
    let mut spoofed = callback(42, &format!("earnprobe:n:{date}:AAPL"));
    spoofed["callback_query"]["from"]["id"] = json!(99);
    handle_update(&state, &spoofed).await.unwrap();
    spoofed["callback_query"]["from"]["id"] = json!(42);
    spoofed["callback_query"]["message"]["chat"]["type"] = json!("group");
    handle_update(&state, &spoofed).await.unwrap();
    assert!(telegram.requests.lock().await.is_empty());
    for command in [
        format!("/test_earnings_api {date} https://secret.invalid"),
        format!("/test_earnings_api {date} ../AAPL"),
        format!("/test_earnings_api {}", date + Duration::days(61)),
        format!("/test_earnings_api {}", date - Duration::days(121)),
        format!("/test_earnings_api {date} AAPL extra"),
        "/test_earnings_api invalid".into(),
    ] {
        handle_update(&state, &message(42, &command)).await.unwrap();
    }
    for data in [
        format!("earnprobe:unknown:{date}:AAPL"),
        format!("earnprobe:n:{date}:AAPL:extra"),
        format!("earnprobe:n:{date}:../AAPL"),
        "earnprobe:n:bad:AAPL".into(),
    ] {
        handle_update(&state, &callback(42, &data)).await.unwrap();
    }
    assert!(upstream.state.requests.lock().await.is_empty());
    assert!(!last_reply(&telegram).await.contains("secret.invalid"));
}

#[tokio::test]
async fn nasdaq_probe_uses_shared_service_and_leaves_calendar_cache_and_health_untouched() {
    let (telegram, mut state, upstream, _cache, date) = setup().await;
    // The bot must not construct a fresh service using these unrelated diagnostic clients.
    let broken = reqwest::Client::builder()
        .proxy(reqwest::Proxy::all("http://127.0.0.1:1").unwrap())
        .build()
        .unwrap();
    state.direct_http = broken.clone();
    state.proxied_http = broken;
    state.config.network.proxy_url = "http://private-user:PRIVATE_TOKEN@proxy.invalid".into();
    state.config.network.proxied = vec!["nasdaq".into()];
    state
        .health
        .record_failure("earnings_nasdaq", "previous failure")
        .await;
    probe(&state, "n", date, "AAPL").await;
    let reply = last_reply(&telegram).await;
    assert!(reply.contains("✅"));
    assert!(reply.contains("HTTP=200"));
    assert!(reply.contains("validated_rows=1"));
    assert!(reply.contains("出网：代理（nasdaq）"));
    assert!(reply.contains(&format!("asOf={date}")));
    assert!(!reply.contains("PRIVATE_TOKEN"));
    let requests = upstream.state.requests.lock().await;
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].0, "/nasdaq");
    assert_eq!(requests[0].1, format!("date={date}"));
    drop(requests);
    assert!(state.earnings.cached(date).await.unwrap().is_none());
    assert_eq!(
        state.health.snapshot().await.unwrap()[0].consecutive_failures,
        1
    );
    let telegram_requests = telegram.requests.lock().await;
    assert_eq!(telegram_requests[0].0, "answerCallbackQuery");
    assert_eq!(telegram_requests[1].0, "editMessageText");
    assert_eq!(telegram_requests[1].1["message_id"], 77);
    drop(telegram_requests);
    probe(&state, "n", date, "AAPL").await;
    assert!(last_reply(&telegram).await.contains("HTTP=not_requested"));
    assert_eq!(upstream.state.requests.lock().await.len(), 1);
}

#[tokio::test]
async fn null_data_is_a_diagnostic_failure_even_with_http_200_and_a_good_cached_day() {
    let (telegram, state, upstream, _cache, date) = setup().await;
    let cached = state.earnings.calendar(date, false).await.unwrap();
    upstream.state.requests.lock().await.clear();
    set_reply(
        &upstream,
        "/nasdaq",
        StatusCode::OK,
        "application/json",
        br#"{"status":{"rCode":200},"data":null,"message":"TOP_SECRET_RAW_BODY"}"#.to_vec(),
    )
    .await;
    probe(&state, "n", date, "AAPL").await;
    let reply = last_reply(&telegram).await;
    assert!(reply.contains("❌"));
    assert!(reply.contains("Nasdaq data"));
    assert!(reply.contains("HTTP=200"));
    assert!(reply.contains("rCode=200"));
    assert!(reply.contains("data=null"));
    assert!(!reply.contains("TOP_SECRET_RAW_BODY"));
    assert_eq!(
        state
            .earnings
            .cached(date)
            .await
            .unwrap()
            .unwrap()
            .updated_at,
        cached.updated_at
    );
    assert_eq!(upstream.state.requests.lock().await.len(), 1); // no TradingView fallback
}

#[tokio::test]
async fn normal_health_alert_errors_include_request_date_and_response_shape() {
    let (_telegram, state, upstream, _cache, date) = setup().await;
    set_reply(
        &upstream,
        "/nasdaq",
        StatusCode::OK,
        "application/json",
        br#"{"status":{"rCode":200},"data":null}"#.to_vec(),
    )
    .await;
    state.earnings.calendar(date, false).await.unwrap();
    let sources = state.health.snapshot().await.unwrap();
    let error = sources
        .iter()
        .find(|h| h.key == "earnings_nasdaq")
        .unwrap()
        .last_error
        .as_deref()
        .unwrap();
    assert!(error.contains(&format!("date={date}")));
    assert!(error.contains("HTTP=200"));
    assert!(error.contains("data=null"));
}

#[tokio::test]
async fn tradingview_is_independent_checks_truncation_and_rejects_historical_probes() {
    let (telegram, state, upstream, _cache, date) = setup().await;
    set_reply(
        &upstream,
        "/tv",
        StatusCode::OK,
        "application/json",
        br#"{"totalCount":2001,"data":[]}"#.to_vec(),
    )
    .await;
    probe(&state, "t", date, "AAPL").await;
    let reply = last_reply(&telegram).await;
    assert!(reply.contains("❌"));
    assert!(reply.contains("incomplete TradingView response"));
    assert!(reply.contains("totalCount=2001"));
    let requests = upstream.state.requests.lock().await;
    assert_eq!(requests.len(), 1);
    assert_eq!(requests[0].0, "/tv");
    let body: Value = serde_json::from_slice(&requests[0].2).unwrap();
    assert_eq!(body["columns"], json!(crate::earnings::TV_COLUMNS));
    drop(requests);
    probe(&state, "t", date - Duration::days(1), "AAPL").await;
    assert!(
        last_reply(&telegram)
            .await
            .contains("not historical actuals")
    );
    assert_eq!(upstream.state.requests.lock().await.len(), 1);
}

#[tokio::test]
async fn validated_empty_response_is_not_confused_with_null_data() {
    let (telegram, state, upstream, _cache, date) = setup().await;
    probe(&state, "t", date, "AAPL").await;
    let reply = last_reply(&telegram).await;
    assert!(reply.contains("✅"));
    assert!(reply.contains("validated_rows=0"));
    assert_eq!(upstream.state.requests.lock().await.len(), 1);
}

#[tokio::test]
async fn http_429_probe_sets_the_same_provider_cooldown_used_by_calendar_requests() {
    let (telegram, state, upstream, _cache, date) = setup().await;
    set_reply(
        &upstream,
        "/nasdaq",
        StatusCode::TOO_MANY_REQUESTS,
        "text/html",
        b"SECRET_BODY".to_vec(),
    )
    .await;
    probe(&state, "n", date, "AAPL").await;
    let reply = last_reply(&telegram).await;
    assert!(reply.contains("HTTP=429"));
    assert!(reply.contains("retry_after_seconds=900"));
    assert!(!reply.contains("SECRET_BODY"));
    probe(&state, "n", date + Duration::days(1), "AAPL").await;
    assert!(last_reply(&telegram).await.contains("provider cooldown"));
    let _ = state
        .earnings
        .calendar(date + Duration::days(2), false)
        .await;
    assert_eq!(
        upstream
            .state
            .requests
            .lock()
            .await
            .iter()
            .filter(|(path, _, _)| path == "/nasdaq")
            .count(),
        1
    );
}

#[tokio::test]
async fn html_and_wrong_date_responses_are_bounded_and_do_not_echo_raw_bodies() {
    for wrong_date in [false, true] {
        let (telegram, state, upstream, _cache, date) = setup().await;
        if wrong_date {
            let other = date + Duration::days(1);
            set_reply(&upstream, "/nasdaq", StatusCode::OK, "application/json", json!({"status":{"rCode":200},"data":{"asOf":other.format("%a, %b %-d, %Y").to_string(),"rows":[]},"debug":"SECRET_BODY"}).to_string().into_bytes()).await;
        } else {
            set_reply(
                &upstream,
                "/nasdaq",
                StatusCode::OK,
                "text/html",
                b"<html>SECRET_BODY</html>".to_vec(),
            )
            .await;
        }
        probe(&state, "n", date, "AAPL").await;
        let reply = last_reply(&telegram).await;
        assert!(reply.contains("❌"));
        assert!(reply.contains("HTTP=200"));
        assert!(reply.contains(if wrong_date {
            "date mismatch"
        } else {
            "JSON=invalid"
        }));
        assert!(!reply.contains("SECRET_BODY"));
    }
}

#[tokio::test]
async fn logo_probe_is_live_bounded_and_does_not_use_or_overwrite_cached_png() {
    let (telegram, state, upstream, cache, date) = setup().await;
    state.earnings.logos.image("AAPL").await.unwrap().unwrap();
    set_reply(
        &upstream,
        "logo",
        StatusCode::NOT_FOUND,
        "text/html",
        b"SECRET_BODY".to_vec(),
    )
    .await;
    probe(&state, "l", date, "AAPL").await;
    let reply = last_reply(&telegram).await;
    assert!(reply.contains("❌"));
    assert!(reply.contains("HTTP=404"));
    assert!(!reply.contains("SECRET_BODY"));
    assert_eq!(
        std::fs::read(cache.path().join("logo-AAPL.png")).unwrap(),
        PNG
    );
    assert_eq!(
        state.earnings.logos.image("AAPL").await.unwrap().unwrap(),
        PNG
    );
    probe(&state, "l", date, "MSFT").await;
    assert!(last_reply(&telegram).await.contains("HTTP=not_requested"));
    assert_eq!(upstream.state.requests.lock().await.len(), 2);
}

#[tokio::test]
async fn oversized_json_and_network_failures_are_safe_and_not_reported_as_empty_success() {
    let (telegram, state, upstream, _cache, date) = setup().await;
    set_reply(
        &upstream,
        "/nasdaq",
        StatusCode::OK,
        "application/json",
        vec![b'x'; 2 * 1024 * 1024 + 1],
    )
    .await;
    probe(&state, "n", date, "AAPL").await;
    let reply = last_reply(&telegram).await;
    assert!(reply.contains("response=too_large"));
    assert!(reply.starts_with("❌"));
    let evidence = reply
        .split("<code>")
        .nth(1)
        .unwrap()
        .split("</code>")
        .next()
        .unwrap();
    assert!(!evidence.contains("validated_rows=0"));
    upstream.task.abort();
    tokio::task::yield_now().await;
    probe(&state, "n", date + Duration::days(1), "AAPL").await;
    let reply = last_reply(&telegram).await;
    assert!(reply.contains("network="));
    assert!(reply.contains("HTTP=not_received"));
    assert!(!reply.contains(&upstream.base));
}

#[tokio::test]
async fn invalid_or_oversized_pngs_never_pass_the_live_probe() {
    for (mime, bytes, expected) in [
        ("text/html", PNG.to_vec(), "PNG=content_type_mismatch"),
        ("image/png", vec![0; 24], "PNG=invalid"),
        ("image/png", vec![0; 256 * 1024 + 1], "PNG=too_large"),
    ] {
        let (telegram, state, upstream, cache, date) = setup().await;
        set_reply(&upstream, "logo", StatusCode::OK, mime, bytes).await;
        probe(&state, "l", date, "AAPL").await;
        let reply = last_reply(&telegram).await;
        assert!(reply.contains("❌"));
        assert!(reply.contains(expected));
        assert!(!cache.path().join("logo-AAPL.png").exists());
    }
}

#[tokio::test]
async fn logo_probe_and_calendar_image_share_persisted_retry_after() {
    let (telegram, state, upstream, _cache, date) = setup().await;
    set_reply(
        &upstream,
        "logo",
        StatusCode::FORBIDDEN,
        "text/plain",
        b"SECRET_BODY".to_vec(),
    )
    .await;
    probe(&state, "l", date, "AAPL").await;
    assert!(last_reply(&telegram).await.contains("HTTP=403"));
    assert!(
        last_reply(&telegram)
            .await
            .contains("retry_after_seconds=900")
    );
    assert!(state.earnings.logos.image("MSFT").await.unwrap().is_none());
    probe(&state, "l", date, "MSFT").await;
    assert!(last_reply(&telegram).await.contains("HTTP=not_requested"));
    assert_eq!(upstream.state.requests.lock().await.len(), 1);
}

#[tokio::test]
async fn logo_probe_checks_png_and_reuses_persisted_gate_after_service_restart() {
    let (telegram, state, upstream, cache, date) = setup().await;
    probe(&state, "l", date, "AAPL").await;
    let reply = last_reply(&telegram).await;
    assert!(reply.contains("✅"));
    assert!(reply.contains("PNG=valid"));
    assert!(reply.contains("dimensions=1x1"));
    assert!(!cache.path().join("logo-AAPL.png").exists());
    let restarted = CompanyLogoService::new(reqwest::Client::new())
        .with_base(format!("{}/symbol", upstream.base))
        .with_cache_dir(cache.path());
    assert!(matches!(
        restarted.probe("MSFT").await,
        Err(AppError::QuotaExceeded { .. })
    ));
    assert_eq!(upstream.state.requests.lock().await.len(), 1);
}
