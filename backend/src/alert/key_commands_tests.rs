use super::*;
use crate::alert::{
    HealthRegistry,
    test_support::{TelegramMock, test_pool},
};
use serde_json::json;
use std::sync::Arc;

async fn fixture(mock: &TelegramMock) -> BotState {
    let pool = test_pool().await;
    BotState {
        telegram: mock.client.clone(),
        chat_ids: vec![42],
        health: Arc::new(HealthRegistry::new(pool.clone(), None, 3, 3600)),
        llm_usage: None,
        pool,
        config: crate::config::AppConfig::from_path("config.example.toml").unwrap(),
        translation: None,
        direct_http: reqwest::Client::new(),
        proxied_http: reqwest::Client::new(),
        poll_timeout_seconds: 30,
    }
}
fn message_from(user: i64, text: &str, id: i64) -> Value {
    json!({"message_id":id, "from":{"id":user}, "chat":{"id":user,"type":"private"}, "text":text})
}
fn button_from(user: i64, chat: i64, data: &str) -> Value {
    json!({"id":"test-callback", "from":{"id":user}, "message":{"message_id":77,"chat":{"id":chat,"type":"private"}},"data":data})
}

#[tokio::test]
async fn applications_are_private_admin_approved_idempotent_and_lists_are_masked() {
    let mock = TelegramMock::start().await;
    let state = fixture(&mock).await;
    let ask = message_from(99, "/request_key device", 1);
    assert!(message(&state, &ask).await.unwrap());
    assert!(message(&state, &ask).await.unwrap());
    let keys = AccessKeys::new(state.pool.clone());
    let pending = keys.pending(0).await.unwrap();
    assert_eq!(pending.len(), 1);
    let data = format!("key_approve:device:{}", pending[0].id);
    callback(&state, &button_from(99, 42, &data)).await.unwrap();
    assert!(keys.list(None, 0).await.unwrap().is_empty());
    callback(&state, &button_from(42, 99, &data)).await.unwrap();
    assert!(keys.list(None, 0).await.unwrap().is_empty());
    callback(&state, &button_from(42, 42, &data)).await.unwrap();
    callback(&state, &button_from(42, 42, &data)).await.unwrap();
    message(&state, &message_from(42, "/keys", 3))
        .await
        .unwrap();
    message(&state, &message_from(99, "/my_keys", 4))
        .await
        .unwrap();
    let requests = mock.requests.lock().await;
    let deliveries: Vec<_> = requests
        .iter()
        .filter(|(method, body)| {
            method == "sendMessage"
                && body["text"]
                    .as_str()
                    .unwrap_or("")
                    .contains("<b>API Key 已批准</b>")
        })
        .collect();
    assert_eq!(deliveries.len(), 1);
    assert_eq!(deliveries[0].1["chat_id"], 99);
    let body = deliveries[0].1["text"].as_str().unwrap();
    let secret = body
        .split("<code>")
        .nth(1)
        .unwrap()
        .split("</code>")
        .next()
        .unwrap();
    for (_, body) in requests
        .iter()
        .filter(|(_, body)| body["text"].as_str().unwrap_or("").contains("分发记录"))
    {
        assert!(!body["text"].as_str().unwrap().contains(secret));
    }
    assert_eq!(keys.list(None, 0).await.unwrap().len(), 1);
    assert_eq!(keys.list(None, 0).await.unwrap()[0].status, "active");
}

#[tokio::test]
async fn group_requests_non_admin_revocation_and_wrong_senders_are_rejected() {
    let mock = TelegramMock::start().await;
    let state = fixture(&mock).await;
    let mut group = message_from(99, "/request_key general", 1);
    group["chat"] = json!({"id":-123,"type":"group"});
    message(&state, &group).await.unwrap();
    let mut spoof = message_from(99, "/request_key general", 1);
    spoof["from"]["id"] = json!(98);
    message(&state, &spoof).await.unwrap();
    assert!(
        AccessKeys::new(state.pool.clone())
            .pending(0)
            .await
            .unwrap()
            .is_empty()
    );
    message(&state, &message_from(99, "/keys", 2))
        .await
        .unwrap();
    message(&state, &message_from(99, "/revoke_key 1", 3))
        .await
        .unwrap();
    assert!(
        mock.requests
            .lock()
            .await
            .iter()
            .all(|(_, body)| body["text"] == "该操作仅限管理员。")
    );
}

#[tokio::test]
async fn failed_private_delivery_stays_unusable_and_can_be_reapproved() {
    let mock = TelegramMock::start().await;
    let state = fixture(&mock).await;
    message(&state, &message_from(99, "/request_key general", 1))
        .await
        .unwrap();
    let keys = AccessKeys::new(state.pool.clone());
    let request = keys.pending(0).await.unwrap().remove(0);
    let callback_data = format!("key_approve:general:{}", request.id);
    mock.fail_key_delivery
        .store(true, std::sync::atomic::Ordering::SeqCst);
    callback(&state, &button_from(42, 42, &callback_data))
        .await
        .unwrap();
    assert_eq!(keys.list(None, 0).await.unwrap()[0].status, "revoked");
    assert_eq!(keys.pending(0).await.unwrap().len(), 1);
    mock.fail_key_delivery
        .store(false, std::sync::atomic::Ordering::SeqCst);
    callback(&state, &button_from(42, 42, &callback_data))
        .await
        .unwrap();
    let rows = keys.list(None, 0).await.unwrap();
    assert_eq!(rows.iter().filter(|key| key.status == "active").count(), 1);
    message(
        &state,
        &message_from(42, &format!("/revoke_key {}", rows[0].id), 3),
    )
    .await
    .unwrap();
    assert_eq!(keys.list(None, 0).await.unwrap()[0].status, "revoked");
}
