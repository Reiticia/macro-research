use super::*;
use crate::{
    alert::test_support::TelegramMock,
    fcm::tests::{FcmMock, event},
    repository::EventRepository,
};
use axum::http::StatusCode;
use serde_json::json;

fn message(chat_id: i64, text: &str) -> Value {
    json!({"message": {"chat": {"id": chat_id}, "text": text}})
}

#[tokio::test]
async fn fcm_commands_validate_then_deliver_only_to_explicit_existing_event() {
    let telegram = TelegramMock::start().await;
    let fcm = FcmMock::start(StatusCode::OK, json!({"name": "projects/test/messages/1"})).await;
    let mut state = super::tests::fixture(&telegram).await;
    state.fcm = Some(fcm.notifier.clone());
    let events = EventRepository::new(state.pool.clone());
    let id = events.save_events(&[event()]).await.unwrap()[0];
    let before = serde_json::to_value(events.get(id).await.unwrap()).unwrap();

    handle_update(&state, &message(42, "/test_fcm@our_bot"))
        .await
        .unwrap();
    handle_update(&state, &message(42, &format!("/test_fcm {id}")))
        .await
        .unwrap();
    let requests = fcm.requests.lock().await;
    assert_eq!(requests.len(), 2);
    assert_eq!(requests[0]["validate_only"], true);
    assert!(requests[1].get("validate_only").is_none());
    assert_eq!(requests[1]["message"]["topic"], crate::fcm::topic_name(id));
    assert_eq!(requests[1]["message"]["data"]["eventId"], id.to_string());
    assert_eq!(requests[1]["message"]["data"]["actual"], "");
    assert_eq!(
        serde_json::to_value(events.get(id).await.unwrap()).unwrap(),
        before
    );
    let replies = telegram.requests.lock().await;
    assert_eq!(replies.len(), 2);
    assert!(
        replies[0].1["text"]
            .as_str()
            .unwrap()
            .contains("未向设备推送")
    );
    assert!(
        replies[1].1["text"]
            .as_str()
            .unwrap()
            .contains("所有关注该事件")
    );
    assert!(
        replies[1].1["text"]
            .as_str()
            .unwrap()
            .contains("不代表设备已收到")
    );
}

#[tokio::test]
async fn fcm_commands_require_admin_and_valid_arguments_and_event() {
    let telegram = TelegramMock::start().await;
    let fcm = FcmMock::start(StatusCode::OK, json!({})).await;
    let mut state = super::tests::fixture(&telegram).await;
    state.fcm = Some(fcm.notifier.clone());
    for text in ["/test_fcm", "/test_fcm 143"] {
        handle_update(&state, &message(99, text)).await.unwrap();
    }
    assert!(telegram.requests.lock().await.is_empty());
    for text in [
        "/test_fcm 0",
        "/test_fcm -1",
        "/test_fcm abc",
        "/test_fcm 999999999999999999999",
        "/test_fcm 1 2",
        "/test_fcm 143",
    ] {
        handle_update(&state, &message(42, text)).await.unwrap();
    }
    assert!(fcm.requests.lock().await.is_empty());
    let replies = telegram.requests.lock().await;
    assert_eq!(replies.len(), 6);
    assert!(
        replies[5].1["text"]
            .as_str()
            .unwrap()
            .contains("未找到事件 143")
    );
}

#[tokio::test]
async fn fcm_commands_explain_disabled_or_unloaded_sender() {
    let telegram = TelegramMock::start().await;
    let mut state = super::tests::fixture(&telegram).await;
    state.config.fcm.enabled = false;
    handle_update(&state, &message(42, "/test_fcm"))
        .await
        .unwrap();
    state.config.fcm.enabled = true;
    handle_update(&state, &message(42, "/test_fcm"))
        .await
        .unwrap();
    let replies = telegram.requests.lock().await;
    assert!(
        replies[0].1["text"]
            .as_str()
            .unwrap()
            .contains("FCM 未启用")
    );
    assert!(
        replies[1].1["text"]
            .as_str()
            .unwrap()
            .contains("发送器未加载")
    );
}

#[tokio::test]
async fn fcm_failure_is_returned_to_admin_and_command_is_discoverable() {
    let telegram = TelegramMock::start().await;
    let fcm = FcmMock::start(
        StatusCode::FORBIDDEN,
        json!({"error": {
            "status": "PERMISSION_DENIED", "message": "do-not-echo"
        }}),
    )
    .await;
    let mut state = super::tests::fixture(&telegram).await;
    state.fcm = Some(fcm.notifier.clone());
    handle_update(&state, &message(42, "/test_fcm"))
        .await
        .unwrap();
    handle_update(&state, &message(42, "/help")).await.unwrap();
    telegram.client.set_admin_commands(&[42]).await.unwrap();
    let replies = telegram.requests.lock().await;
    let failure = replies[0].1["text"].as_str().unwrap();
    assert!(failure.contains("FCM send failed with HTTP 403"));
    assert!(failure.contains("PERMISSION_DENIED"));
    assert!(!failure.contains("do-not-echo"));
    assert!(replies[1].1["text"].as_str().unwrap().contains("/test_fcm"));
    assert!(
        replies[2].1["commands"]
            .as_array()
            .unwrap()
            .iter()
            .any(|c| c["command"] == "test_fcm")
    );
    assert!(
        !replies[3].1["commands"]
            .as_array()
            .unwrap()
            .iter()
            .any(|c| c["command"] == "test_fcm")
    );
}
