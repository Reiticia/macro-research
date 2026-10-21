use super::*;
use crate::{alert::test_support::test_pool, model::EventStatus};
use axum::{Json, Router, http::StatusCode, routing::post};
use rust_decimal::Decimal;

pub(crate) struct FcmMock {
    pub notifier: Arc<FcmNotifier>,
    pub requests: Arc<Mutex<Vec<Value>>>,
    task: tokio::task::JoinHandle<()>,
}

impl FcmMock {
    pub async fn start(status: StatusCode, body: Value) -> Self {
        let requests = Arc::new(Mutex::new(Vec::new()));
        let captured = requests.clone();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let token_body = body.clone();
        let router = Router::new()
            .route(
                "/messages:send",
                post(move |Json(payload): Json<Value>| {
                    let captured = captured.clone();
                    let body = body.clone();
                    async move {
                        captured.lock().await.push(payload);
                        (status, Json(body))
                    }
                }),
            )
            .route(
                "/token",
                post(move |form: String| {
                    let body = token_body.clone();
                    async move {
                        assert!(form.contains("assertion=test-assertion"));
                        assert!(form.contains("grant_type="));
                        (status, Json(body))
                    }
                }),
            );
        let task = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
        Self {
            notifier: Arc::new(FcmNotifier::for_test(&base)),
            requests,
            task,
        }
    }
}

impl Drop for FcmMock {
    fn drop(&mut self) {
        self.task.abort();
    }
}

pub(crate) fn event() -> EconomicEvent {
    EconomicEvent {
        id: 143,
        provider: "test".into(),
        provider_id: "test-143".into(),
        release_group_id: None,
        country: "United States".into(),
        currency: Some("USD".into()),
        category: "employment".into(),
        event: "Nonfarm Payrolls".into(),
        event_zh_cn: Some("非农就业人数".into()),
        event_zh_tw: Some("非農就業人數".into()),
        event_time: chrono::Utc::now(),
        importance: 3,
        actual: Some(Decimal::new(100, 0)),
        previous: None,
        consensus: Some(Decimal::new(90, 0)),
        forecast: None,
        unit: None,
        status: EventStatus::Released,
        time_exact: true,
    }
}

#[test]
fn topic_names_are_stable_and_scoped() {
    assert_eq!(topic_name(143), "macro_event_143");
}

#[test]
fn release_payload_is_data_only_and_localizable() {
    let payload = release_payload(&event());
    assert_eq!(payload["message"]["topic"], "macro_event_143");
    assert_eq!(
        payload["message"]["data"]["type"],
        "economic_event_released"
    );
    assert_eq!(payload["message"]["data"]["eventZhCn"], "非农就业人数");
    assert_eq!(payload["message"]["data"]["actual"], "100");
    assert_eq!(payload["message"]["android"]["priority"], "HIGH");
    assert!(payload["message"].get("notification").is_none());
    assert!(payload.get("validate_only").is_none());
}

#[tokio::test]
async fn test_and_release_share_send_path_and_record_health() {
    let mut mock =
        FcmMock::start(StatusCode::OK, json!({"name": "projects/test/messages/1"})).await;
    let health = Arc::new(HealthRegistry::new(test_pool().await, None, 3, 3600));
    Arc::get_mut(&mut mock.notifier).unwrap().health = Some(health.clone());
    mock.notifier.send_test(None).await.unwrap();
    mock.notifier.send_test(Some(&event())).await.unwrap();
    mock.notifier.send_release(&event()).await.unwrap();
    let requests = mock.requests.lock().await;
    assert_eq!(requests.len(), 3);
    assert_eq!(requests[0]["validate_only"], true);
    assert_eq!(requests[1]["message"]["topic"], "macro_event_143");
    assert!(requests[1].get("validate_only").is_none());
    for payload in &requests[..2] {
        let data = &payload["message"]["data"];
        assert!(data["event"].as_str().unwrap().contains("FCM TEST"));
        assert!(data["eventZhCn"].as_str().unwrap().contains("FCM 测试"));
        assert!(data["eventZhTw"].as_str().unwrap().contains("FCM 測試"));
        assert_eq!(data["actual"], "");
        assert_eq!(data["consensus"], "");
        assert_eq!(data["type"], "economic_event_released");
        assert!(data.as_object().unwrap().values().all(Value::is_string));
        assert_eq!(payload["message"]["android"]["priority"], "HIGH");
        assert!(payload["message"].get("notification").is_none());
    }
    assert_eq!(requests[2], release_payload(&event()));
    let snapshot = health.snapshot().await.unwrap();
    assert_eq!(snapshot[0].key, HEALTH_KEY);
    assert_eq!(snapshot[0].status, "healthy");
}

#[tokio::test]
async fn send_failure_reports_http_and_codes_without_echoing_body() {
    let mut mock = FcmMock::start(
        StatusCode::FORBIDDEN,
        json!({"error": {
            "status": "PERMISSION_DENIED", "message": "do-not-echo-request-data",
            "details": [{"reason": "SERVICE_DISABLED"}]
        }}),
    )
    .await;
    let health = Arc::new(HealthRegistry::new(test_pool().await, None, 3, 3600));
    Arc::get_mut(&mut mock.notifier).unwrap().health = Some(health.clone());
    let error = mock.notifier.send_test(None).await.unwrap_err().to_string();
    assert!(error.contains("FCM send failed with HTTP 403"));
    assert!(error.contains("PERMISSION_DENIED, SERVICE_DISABLED"));
    assert!(!error.contains("do-not-echo"));
    let snapshot = health.snapshot().await.unwrap();
    assert_eq!(snapshot[0].key, HEALTH_KEY);
    assert_eq!(snapshot[0].last_error.as_deref(), Some(error.as_str()));
}

#[tokio::test]
async fn oauth_exchange_reports_safe_errors_and_rejects_invalid_tokens() {
    for (status, body, expected) in [
        (
            StatusCode::BAD_REQUEST,
            json!({"error": "invalid_grant", "error_description": "do-not-echo"}),
            "FCM OAuth token request failed with HTTP 400",
        ),
        (
            StatusCode::OK,
            json!({"access_token": "", "expires_in": 3600}),
            "FCM OAuth token response was empty",
        ),
        (
            StatusCode::OK,
            json!({"unexpected": "do-not-echo"}),
            "FCM OAuth token response was invalid",
        ),
    ] {
        let mock = FcmMock::start(status, body).await;
        let error = mock
            .notifier
            .request_access_token("test-assertion")
            .await
            .unwrap_err()
            .to_string();
        assert!(error.contains(expected), "{error}");
        assert!(!error.contains("do-not-echo"));
        assert!(mock.requests.lock().await.is_empty());
    }
    let mock = FcmMock::start(
        StatusCode::OK,
        json!({"access_token": "test-token", "expires_in": 3600}),
    )
    .await;
    let token = mock
        .notifier
        .request_access_token("test-assertion")
        .await
        .unwrap();
    assert_eq!(token.expires_in, 3600);
    assert_eq!(token.access_token, "test-token");
}

#[tokio::test]
async fn transport_failure_identifies_stage_without_exposing_url() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    drop(listener);
    let notifier = FcmNotifier::for_test(&base);
    let error = notifier.send_test(None).await.unwrap_err().to_string();
    assert!(
        error.contains("FCM send failed: connection failed"),
        "{error}"
    );
    assert!(!error.contains(&base));
}

#[tokio::test]
async fn signing_failure_stops_before_sending_and_records_health() {
    let mut mock = FcmMock::start(StatusCode::OK, json!({})).await;
    let health = Arc::new(HealthRegistry::new(test_pool().await, None, 3, 3600));
    Arc::get_mut(&mut mock.notifier).unwrap().health = Some(health.clone());
    *mock.notifier.access_token.lock().await = None;
    // The deliberately non-RSA test key must fail locally, without contacting OAuth or FCM.
    let error = mock.notifier.send_test(None).await.unwrap_err().to_string();
    assert!(error.contains("FCM service account JWT failed"));
    assert!(mock.requests.lock().await.is_empty());
    assert_eq!(
        health.snapshot().await.unwrap()[0].last_error.as_deref(),
        Some(error.as_str())
    );
}

#[test]
fn unexpected_errors_do_not_echo_raw_bodies() {
    assert_eq!(
        response_error_codes("<html>do-not-echo</html>"),
        "unrecognized error response (body omitted)"
    );
    assert_eq!(
        response_error_codes(
            r#"{"error":{"status":"INVALID_ARGUMENT","details":[{"errorCode":"SENDER_ID_MISMATCH"}]}}"#
        ),
        "INVALID_ARGUMENT, SENDER_ID_MISMATCH"
    );
}
