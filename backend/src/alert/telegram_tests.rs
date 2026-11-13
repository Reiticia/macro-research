use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};

use axum::{Json, Router, routing::post};
use serde_json::{Value, json};

use super::TelegramClient;
use crate::error::AppError;

const SYNTHETIC_TOKEN: &str = "synthetic-telegram-token-for-error-tests";

#[derive(Clone, Copy, Debug)]
enum Operation {
    Commands,
    PrivateKey,
    Send,
    Edit,
    CloseKeyboard,
    AnswerCallback,
    Updates,
}

impl Operation {
    const ALL: [Self; 7] = [
        Self::Commands,
        Self::PrivateKey,
        Self::Send,
        Self::Edit,
        Self::CloseKeyboard,
        Self::AnswerCallback,
        Self::Updates,
    ];

    async fn call(self, client: &TelegramClient) -> Result<(), AppError> {
        match self {
            Self::Commands => client.set_admin_commands(&[42]).await,
            Self::PrivateKey => client.send_private_key(42, "synthetic message").await,
            Self::Send => client
                .send_message(42, "synthetic message", None)
                .await
                .map(|_| ()),
            Self::Edit => client.edit_message(42, 1, "synthetic message", None).await,
            Self::CloseKeyboard => client.close_keyboard(42, 1, "synthetic message").await,
            Self::AnswerCallback => client.answer_callback("synthetic-callback", "test").await,
            Self::Updates => client.get_updates(0, 1).await.map(|_| ()),
        }
    }
}

fn assert_safe_http_error(error: AppError) {
    assert!(!error.to_string().contains(SYNTHETIC_TOKEN));
    assert!(!format!("{error:?}").contains(SYNTHETIC_TOKEN));
    let AppError::Http(http) = error else {
        panic!("expected an HTTP error");
    };
    assert!(http.url().is_none());
    // Keep useful diagnostics rather than replacing every failure with an opaque string.
    assert!(http.is_connect() || http.is_timeout() || http.is_decode() || http.is_body());
    let mut cause = std::error::Error::source(&http);
    while let Some(error) = cause {
        assert!(!error.to_string().contains(SYNTHETIC_TOKEN));
        assert!(!format!("{error:?}").contains(SYNTHETIC_TOKEN));
        cause = error.source();
    }
}

#[tokio::test]
async fn all_telegram_operations_strip_credential_urls_from_connection_errors() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    drop(listener);
    let client = TelegramClient::new(
        reqwest::Client::builder()
            .no_proxy()
            .timeout(std::time::Duration::from_millis(200))
            .build()
            .unwrap(),
        base,
        SYNTHETIC_TOKEN,
    );
    for operation in Operation::ALL {
        assert_safe_http_error(
            operation
                .call(&client)
                .await
                .expect_err("connection must fail"),
        );
    }
}

#[tokio::test]
async fn telegram_json_errors_and_public_command_registration_are_sanitized() {
    // Fail both the first (admin) and the second (public) command-registration response.
    for fail_after in [0, 1] {
        let calls = Arc::new(AtomicUsize::new(0));
        let captured = calls.clone();
        let router = Router::new().route(
            "/{token}/{method}",
            post(move || {
                let calls = captured.clone();
                async move {
                    if calls.fetch_add(1, Ordering::SeqCst) < fail_after {
                        r#"{"ok":true}"#
                    } else {
                        "not JSON"
                    }
                }
            }),
        );
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let task = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
        let client = TelegramClient::new(
            reqwest::Client::builder().no_proxy().build().unwrap(),
            base,
            SYNTHETIC_TOKEN,
        );
        for operation in Operation::ALL {
            if matches!(
                operation,
                Operation::CloseKeyboard | Operation::AnswerCallback
            ) {
                continue; // These operations intentionally do not decode response JSON.
            }
            calls.store(0, Ordering::SeqCst);
            if fail_after == 0 || matches!(operation, Operation::Commands) {
                assert_safe_http_error(operation.call(&client).await.expect_err("JSON must fail"));
            }
        }
        task.abort();
    }
}

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
