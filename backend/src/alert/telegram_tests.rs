use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};

use axum::{Json, Router, routing::post};
use serde_json::{Value, json};

use super::TelegramClient;

async fn mock(
    body: Value,
) -> (
    TelegramClient,
    Arc<AtomicUsize>,
    tokio::task::JoinHandle<()>,
) {
    let calls = Arc::new(AtomicUsize::new(0));
    let captured = calls.clone();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let router = Router::new().route(
        "/botfake-token/getMe",
        post(move || {
            let calls = captured.clone();
            let body = body.clone();
            async move {
                calls.fetch_add(1, Ordering::SeqCst);
                Json(body)
            }
        }),
    );
    let task = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    (
        TelegramClient::new(reqwest::Client::new(), base, "fake-token"),
        calls,
        task,
    )
}

#[tokio::test]
async fn discovery_returns_only_public_username_and_coalesces_concurrent_requests() {
    let (client, calls, task) = mock(json!({
        "ok": true, "result": {"is_bot": true, "username": "example_bot", "id": 123}
    }))
    .await;
    let clone = client.clone();
    let (a, b) = tokio::join!(client.public_bot_url(), clone.public_bot_url());
    assert_eq!(a.as_deref(), Some("https://t.me/example_bot"));
    assert_eq!(a, b);
    assert_eq!(client.public_bot_url().await, a);
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    task.abort();
}

#[tokio::test]
async fn invalid_or_failed_discovery_is_also_cached_and_never_exposes_the_response() {
    for body in [
        json!({"ok": false, "description": "fake-token"}),
        json!({"ok": true, "result": {"is_bot": false, "username": "example_bot"}}),
        json!({"ok": true, "result": {"is_bot": true}}),
        json!({"ok": true, "result": {"is_bot": true, "username": "../evil"}}),
    ] {
        let (client, calls, task) = mock(body).await;
        assert_eq!(client.public_bot_url().await, None);
        assert_eq!(client.public_bot_url().await, None);
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        task.abort();
    }
}
