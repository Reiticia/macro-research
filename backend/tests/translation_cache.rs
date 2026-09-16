use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};

use async_trait::async_trait;
use axum::{Json, Router, http::HeaderMap, routing::post};
use chrono::{Duration, Utc};
use market_event_analyzer::{
    error::AppError,
    model::{EconomicEvent, EventStatus},
    repository::EventRepository,
    translation::{
        EventNameTranslation, EventNameTranslator, OpenAiEventNameTranslator, TranslationService,
    },
};
use serde_json::Value;
use sqlx::sqlite::SqlitePoolOptions;

struct CountingTranslator {
    calls: AtomicUsize,
}

#[async_trait]
impl EventNameTranslator for CountingTranslator {
    async fn translate(&self, names: &[String]) -> Result<Vec<EventNameTranslation>, AppError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        Ok(names
            .iter()
            .map(|source| EventNameTranslation {
                source: source.clone(),
                zh_cn: "消费者价格指数同比".into(),
                zh_tw: "消費者價格指數同比".into(),
            })
            .collect())
    }
}

fn event(provider_id: &str) -> EconomicEvent {
    EconomicEvent {
        id: 0,
        provider: "fixture".into(),
        provider_id: provider_id.into(),
        release_group_id: None,
        country: "United States".into(),
        currency: Some("USD".into()),
        category: "inflation".into(),
        event: "CPI YoY".into(),
        event_zh_cn: None,
        event_zh_tw: None,
        event_time: Utc::now() + Duration::days(1),
        importance: 3,
        actual: None,
        previous: None,
        consensus: None,
        forecast: None,
        unit: Some("%".into()),
        status: EventStatus::Scheduled,
        time_exact: true,
    }
}

#[tokio::test]
async fn persisted_translation_is_reused_for_the_same_event_name() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let repository = EventRepository::new(pool);
    let translator = Arc::new(CountingTranslator {
        calls: AtomicUsize::new(0),
    });
    let service = TranslationService::new(repository.clone(), translator.clone(), 20);

    let mut first = vec![event("first")];
    service.enrich(&mut first).await.unwrap();
    repository.save_events(&first).await.unwrap();

    let mut second = vec![event("second")];
    service.enrich(&mut second).await.unwrap();
    repository.save_events(&second).await.unwrap();

    assert_eq!(translator.calls.load(Ordering::SeqCst), 1);
    assert_eq!(second[0].event_zh_cn.as_deref(), Some("消费者价格指数同比"));
    assert_eq!(second[0].event_zh_tw.as_deref(), Some("消費者價格指數同比"));

    let third_id = repository
        .save_events(&[event("saved-without-enrichment")])
        .await
        .unwrap()[0];
    let stored = repository.get(third_id).await.unwrap();
    assert_eq!(stored.event_zh_cn.as_deref(), Some("消费者价格指数同比"));
    assert_eq!(translator.calls.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn startup_backfill_updates_existing_untranslated_rows() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let repository = EventRepository::new(pool);
    let id = repository.save_events(&[event("existing")]).await.unwrap()[0];
    let translator = Arc::new(CountingTranslator {
        calls: AtomicUsize::new(0),
    });
    let service = TranslationService::new(repository.clone(), translator.clone(), 20);

    assert_eq!(service.backfill_existing().await.unwrap(), 1);
    let stored = repository.get(id).await.unwrap();

    assert_eq!(translator.calls.load(Ordering::SeqCst), 1);
    assert_eq!(stored.event_zh_cn.as_deref(), Some("消费者价格指数同比"));
    assert_eq!(stored.event_zh_tw.as_deref(), Some("消費者價格指數同比"));
}

#[tokio::test]
async fn openai_compatible_client_sends_a_batch_and_parses_json() {
    async fn translate(headers: HeaderMap, Json(body): Json<Value>) -> Json<Value> {
        assert_eq!(headers["authorization"], "Bearer test-key");
        assert_eq!(body["model"], "test-model");
        assert_eq!(body["response_format"]["type"], "json_object");
        let input: Value =
            serde_json::from_str(body["messages"][1]["content"].as_str().unwrap()).unwrap();
        assert_eq!(input["events"][0]["name"], "CPI YoY");
        Json(serde_json::json!({
            "choices": [{
                "message": {
                    "content": "{\"translations\":[{\"id\":0,\"zhCn\":\"消费者价格指数同比\",\"zhTw\":\"消費者價格指數同比\"}]}"
                }
            }]
        }))
    }

    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move {
        axum::serve(
            listener,
            Router::new().route("/chat/completions", post(translate)),
        )
        .await
        .unwrap();
    });
    let translator =
        OpenAiEventNameTranslator::new(reqwest::Client::new(), &url, "test-model", "test-key")
            .unwrap();

    let translations = translator.translate(&["CPI YoY".into()]).await.unwrap();

    assert_eq!(translations[0].zh_cn, "消费者价格指数同比");
    assert_eq!(translations[0].zh_tw, "消費者價格指數同比");
    task.abort();
}

// ---------------------------------------------------------------------------
// 中转站 (OpenAI-compatible relay) 的常见差异
// ---------------------------------------------------------------------------

/// Captures every request the relay received so a test can assert on the payload and headers.
#[derive(Clone, Default)]
struct RelayCapture {
    bodies: Arc<tokio::sync::Mutex<Vec<Value>>>,
    headers: Arc<tokio::sync::Mutex<Vec<HeaderMap>>>,
}

impl RelayCapture {
    async fn requests(&self) -> usize {
        self.bodies.lock().await.len()
    }

    async fn body(&self, index: usize) -> Value {
        self.bodies.lock().await[index].clone()
    }

    async fn header(&self, index: usize, name: &str) -> Option<String> {
        self.headers
            .lock()
            .await
            .get(index)
            .and_then(|headers| headers.get(name))
            .and_then(|value| value.to_str().ok())
            .map(str::to_owned)
    }
}

async fn spawn_relay(
    state: RelayCapture,
    handler: impl Fn(&Value) -> Option<Value> + Send + Sync + 'static,
) -> (String, tokio::task::JoinHandle<()>) {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let handler = Arc::new(handler);
    let task = tokio::spawn(async move {
        let router = Router::new().route(
            "/v1/chat/completions",
            post(move |headers: HeaderMap, Json(body): Json<Value>| {
                let state = state.clone();
                let handler = handler.clone();
                async move {
                    state.headers.lock().await.push(headers);
                    state.bodies.lock().await.push(body.clone());
                    match handler(&body) {
                        Some(payload) => (axum::http::StatusCode::OK, Json(payload)),
                        None => (
                            axum::http::StatusCode::BAD_REQUEST,
                            Json(serde_json::json!({
                                "error": {"message": "response_format is not supported"}
                            })),
                        ),
                    }
                }
            }),
        );
        axum::serve(listener, router).await.unwrap();
    });
    (base, task)
}

/// A relay that rejects `response_format` must be retried in plain mode, and the rejection must
/// be remembered so later batches skip the failed round trip.
#[tokio::test]
async fn relay_rejecting_response_format_is_retried_and_remembered() {
    let capture = RelayCapture::default();
    let (base, task) = spawn_relay(capture.clone(), |body: &Value| {
        // Accept only requests without response_format.
        body.get("response_format")
            .is_none()
            .then(relay_response_body)
    })
    .await;

    let translator = OpenAiEventNameTranslator::new(
        reqwest::Client::new(),
        &format!("{base}/v1"),
        "test-model",
        "test-key",
    )
    .unwrap();

    let first = translator
        .translate(&["Nonfarm Payrolls".into()])
        .await
        .unwrap();
    assert_eq!(first[0].zh_cn, "非农就业");
    assert_eq!(
        capture.requests().await,
        2,
        "one rejection plus one plain retry"
    );
    assert!(capture.body(0).await.get("response_format").is_some());
    assert!(capture.body(1).await.get("response_format").is_none());

    let second = translator
        .translate(&["Nonfarm Payrolls".into()])
        .await
        .unwrap();
    assert_eq!(second[0].zh_tw, "非農就業");
    // The plain mode sticks: no second doomed attempt.
    assert_eq!(capture.requests().await, 3);
    task.abort();
}

/// A relay error must carry its own message; "HTTP 401" alone sends the operator hunting.
#[tokio::test]
async fn relay_error_body_is_surfaced_to_the_operator() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move {
        axum::serve(
            listener,
            Router::new().route(
                "/v1/chat/completions",
                post(|| async {
                    (
                        axum::http::StatusCode::UNAUTHORIZED,
                        Json(serde_json::json!({"error": {"message": "invalid api key"}})),
                    )
                }),
            ),
        )
        .await
        .unwrap();
    });

    let translator = OpenAiEventNameTranslator::new(
        reqwest::Client::new(),
        &format!("{base}/v1"),
        "test-model",
        "wrong-key",
    )
    .unwrap();
    let error = translator
        .translate(&["CPI YoY".into()])
        .await
        .unwrap_err()
        .to_string();
    assert!(error.contains("401"), "{error}");
    assert!(error.contains("invalid api key"), "{error}");
    task.abort();
}

/// The relay docs usually show the full endpoint URL; pasting it must work as-is.
#[tokio::test]
async fn a_full_chat_completions_url_is_accepted_as_base_url() {
    let capture = RelayCapture::default();
    let (base, task) = spawn_relay(capture.clone(), |_: &Value| Some(relay_response_body())).await;
    let translator = OpenAiEventNameTranslator::new(
        reqwest::Client::new(),
        &format!("{base}/v1/chat/completions"),
        "test-model",
        "test-key",
    )
    .unwrap();
    assert!(translator.translate(&["CPI YoY".into()]).await.is_ok());
    assert_eq!(capture.requests().await, 1);
    task.abort();
}

/// `extra_headers` covers OpenRouter-style (`X-Title`) and Azure-style (`api-key`) relays; an
/// explicit Authorization entry replaces the bearer header instead of duplicating it.
#[tokio::test]
async fn extra_headers_are_sent_and_can_replace_the_bearer_header() {
    use market_event_analyzer::openai_compat::ExtraHeaders;
    use std::collections::BTreeMap;

    let capture = RelayCapture::default();
    let (base, task) = spawn_relay(capture.clone(), |_: &Value| Some(relay_response_body())).await;

    let mut map = BTreeMap::new();
    map.insert("X-Title".to_owned(), "macro-research".to_owned());
    let translator = OpenAiEventNameTranslator::with_headers(
        reqwest::Client::new(),
        &format!("{base}/v1"),
        "test-model",
        "test-key",
        ExtraHeaders::from_map(&map),
    )
    .unwrap();
    translator.translate(&["CPI YoY".into()]).await.unwrap();
    assert_eq!(
        capture.header(0, "x-title").await.as_deref(),
        Some("macro-research")
    );
    assert_eq!(
        capture.header(0, "authorization").await.as_deref(),
        Some("Bearer test-key")
    );

    let mut override_map = BTreeMap::new();
    override_map.insert("api-key".to_owned(), "azure-key".to_owned());
    override_map.insert("Authorization".to_owned(), "Basic abc".to_owned());
    let translator = OpenAiEventNameTranslator::with_headers(
        reqwest::Client::new(),
        &format!("{base}/v1"),
        "test-model",
        "test-key",
        ExtraHeaders::from_map(&override_map),
    )
    .unwrap();
    translator.translate(&["CPI YoY".into()]).await.unwrap();
    assert_eq!(
        capture.header(1, "api-key").await.as_deref(),
        Some("azure-key")
    );
    assert_eq!(
        capture.header(1, "authorization").await.as_deref(),
        Some("Basic abc"),
        "the explicit Authorization entry must win over the bearer header"
    );
    task.abort();
}

fn relay_response_body() -> Value {
    serde_json::json!({
        "choices": [{
            "message": {
                "content": "{\"translations\":[{\"id\":0,\"zhCn\":\"非农就业\",\"zhTw\":\"非農就業\"}]}"
            }
        }]
    })
}
