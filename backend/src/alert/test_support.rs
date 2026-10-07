use std::sync::Arc;

use axum::{Json, Router, extract::Path, routing::post};
use serde_json::{Value, json};
use sqlx::SqlitePool;
use tokio::{sync::Mutex, task::JoinHandle};

use super::TelegramClient;

pub struct TelegramMock {
    pub client: Arc<TelegramClient>,
    pub requests: Arc<Mutex<Vec<(String, Value)>>>,
    task: JoinHandle<()>,
}

impl TelegramMock {
    pub async fn start() -> Self {
        let requests = Arc::new(Mutex::new(Vec::<(String, Value)>::new()));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let captured = requests.clone();
        let router = Router::new().route(
            "/botbottoken/{method}",
            post(move |Path(method): Path<String>, Json(body): Json<Value>| {
                let captured = captured.clone();
                async move {
                    let mut requests = captured.lock().await;
                    let repeated_edit = method == "editMessageText"
                        && requests.iter().any(|(previous_method, previous_body)| {
                            previous_method == &method && previous_body == &body
                        });
                    requests.push((method, body));
                    if repeated_edit {
                        Json(json!({"ok": false, "description": "Bad Request: message is not modified: specified new message content and reply markup are exactly the same as a current content and reply markup of the message"}))
                    } else {
                        Json(json!({"ok": true, "result": {"message_id": 77}}))
                    }
                }
            }),
        );
        let task = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
        Self {
            client: Arc::new(TelegramClient::new(
                reqwest::Client::new(),
                base,
                "bottoken",
            )),
            requests,
            task,
        }
    }
}

impl Drop for TelegramMock {
    fn drop(&mut self) {
        self.task.abort();
    }
}

pub async fn test_pool() -> SqlitePool {
    let pool = sqlx::sqlite::SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    pool
}
