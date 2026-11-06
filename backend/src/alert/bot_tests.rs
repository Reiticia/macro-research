use super::*;
use crate::alert::test_support::{TelegramMock, test_pool};
use serde_json::json;

pub(super) async fn fixture(mock: &TelegramMock) -> BotState {
    let pool = test_pool().await;
    BotState {
        started_at: chrono::Utc::now(),
        started_instant: std::time::Instant::now(),
        telegram: mock.client.clone(),
        chat_ids: vec![42],
        health: Arc::new(HealthRegistry::new(pool.clone(), None, 3, 3600)),
        earnings: Arc::new(crate::earnings::EarningsService::new(
            pool.clone(),
            reqwest::Client::new(),
            reqwest::Client::new(),
        )),
        llm_usage: None,
        pool,
        config: AppConfig::from_path("config.example.toml").unwrap(),
        translation: None,
        fcm: None,
        direct_http: reqwest::Client::new(),
        proxied_http: reqwest::Client::new(),
        poll_timeout_seconds: 30,
    }
}

fn callback(chat_id: i64, data: &str) -> Value {
    json!({"callback_query": {
        "id": "callback-1",
        "message": {"chat": {"id": chat_id}, "message_id": 77},
        "data": data,
    }})
}

#[tokio::test]
async fn notification_buttons_edit_original_remove_keyboard_and_show_both_states() {
    let mock = TelegramMock::start().await;
    let state = fixture(&mock).await;
    handle_update(
        &state,
        &json!({"message": {"chat": {"id": 42}, "text": "/notifications@our_bot"}}),
    )
    .await
    .unwrap();
    {
        let requests = mock.requests.lock().await;
        assert_eq!(requests.len(), 1);
        assert_eq!(requests[0].0, "sendMessage");
        let body = &requests[0].1;
        assert!(
            body["text"]
                .as_str()
                .unwrap()
                .contains("公布值缺失通知：<b>开启</b>")
        );
        assert!(
            body["text"]
                .as_str()
                .unwrap()
                .contains("数据源异常/恢复通知：<b>开启</b>")
        );
        assert_eq!(
            body["reply_markup"]["inline_keyboard"][0][1]["callback_data"],
            "notify:data_missing:off"
        );
        assert_eq!(
            body["reply_markup"]["inline_keyboard"][1][1]["callback_data"],
            "notify:data_sources:off"
        );
    }
    handle_update(&state, &callback(42, "notify:data_missing:off"))
        .await
        .unwrap();
    {
        let requests = mock.requests.lock().await;
        assert_eq!(requests.len(), 3);
        assert_eq!(requests[1].0, "answerCallbackQuery");
        assert_eq!(requests[2].0, "editMessageText");
        let edit = &requests[2].1;
        assert_eq!(edit["chat_id"], 42);
        assert_eq!(edit["message_id"], 77);
        assert!(
            edit["text"]
                .as_str()
                .unwrap()
                .contains("公布值缺失通知：<b>关闭</b>")
        );
        assert!(
            edit["text"]
                .as_str()
                .unwrap()
                .contains("数据源异常/恢复通知：<b>开启</b>")
        );
        assert_eq!(edit["reply_markup"]["inline_keyboard"], json!([]));
    }
    assert!(
        !preferences::enabled(&state.pool, NotificationKind::DataMissing)
            .await
            .unwrap()
    );
    assert!(
        preferences::enabled(&state.pool, NotificationKind::DataSources)
            .await
            .unwrap()
    );

    // A double click is safe even when Telegram returns "message is not modified".
    handle_update(&state, &callback(42, "notify:data_missing:off"))
        .await
        .unwrap();
    for (data, missing, sources) in [
        ("notify:data_sources:off", false, false),
        ("notify:data_missing:on", true, false),
        ("notify:data_sources:on", true, true),
    ] {
        handle_update(&state, &callback(42, data)).await.unwrap();
        assert_eq!(
            preferences::enabled(&state.pool, NotificationKind::DataMissing)
                .await
                .unwrap(),
            missing
        );
        assert_eq!(
            preferences::enabled(&state.pool, NotificationKind::DataSources)
                .await
                .unwrap(),
            sources
        );
    }
    let requests = mock.requests.lock().await;
    // No callback sends a fresh message; all only answer and edit the original.
    assert_eq!(
        requests
            .iter()
            .filter(|request| request.0 == "sendMessage")
            .count(),
        1
    );
    for (_, edit) in requests
        .iter()
        .filter(|request| request.0 == "editMessageText")
    {
        assert_eq!(edit["message_id"], 77);
        assert_eq!(edit["reply_markup"]["inline_keyboard"], json!([]));
    }
}

#[tokio::test]
async fn notification_preferences_require_admin_and_valid_callback() {
    let mock = TelegramMock::start().await;
    let state = fixture(&mock).await;
    handle_update(
        &state,
        &json!({"message": {"chat": {"id": 99}, "text": "/notifications"}}),
    )
    .await
    .unwrap();
    handle_update(&state, &callback(99, "notify:data_missing:off"))
        .await
        .unwrap();
    for data in [
        "notify:unknown:off",
        "notify:data_missing:invalid",
        "notify:data_sources",
        "notify:data_sources:off:extra",
    ] {
        handle_update(&state, &callback(42, data)).await.unwrap();
    }
    assert!(mock.requests.lock().await.is_empty());
    for kind in NotificationKind::ALL {
        assert!(preferences::enabled(&state.pool, kind).await.unwrap());
    }
}

#[tokio::test]
async fn admin_command_menu_includes_notification_panel_and_version() {
    let mock = TelegramMock::start().await;
    mock.client.set_admin_commands(&[42]).await.unwrap();
    let requests = mock.requests.lock().await;
    assert_eq!(requests[0].0, "setMyCommands");
    assert_eq!(requests[0].1["scope"]["chat_id"], 42);
    assert!(
        requests[0].1["commands"]
            .as_array()
            .unwrap()
            .iter()
            .any(|command| command["command"] == "notifications")
    );
    assert!(
        requests[0].1["commands"]
            .as_array()
            .unwrap()
            .iter()
            .any(|command| command["command"] == "version")
    );
    let public_menu = requests
        .iter()
        .find(|(_, body)| body["scope"]["type"] == "default")
        .unwrap();
    assert!(
        !public_menu.1["commands"]
            .as_array()
            .unwrap()
            .iter()
            .any(|command| command["command"] == "version")
    );
}

#[test]
fn uptime_formats_seconds_minutes_hours_and_days() {
    for (seconds, expected) in [
        (0, "0 天 0 小时 0 分钟 0 秒"),
        (59, "0 天 0 小时 0 分钟 59 秒"),
        (60, "0 天 0 小时 1 分钟 0 秒"),
        (3_600, "0 天 1 小时 0 分钟 0 秒"),
        (86_400, "1 天 0 小时 0 分钟 0 秒"),
        (176_461, "2 天 1 小时 1 分钟 1 秒"),
    ] {
        assert_eq!(format_uptime(seconds), expected);
    }
}

#[tokio::test]
async fn version_reports_embedded_commit_fixed_start_time_and_monotonic_uptime() {
    let mock = TelegramMock::start().await;
    let mut state = fixture(&mock).await;
    // A future wall-clock timestamp must not produce a negative/reset uptime.
    state.started_at = "2099-01-02T03:04:05Z".parse().unwrap();
    state.started_instant = std::time::Instant::now() - std::time::Duration::from_secs(90_061);
    for command in ["/version", "/version@our_bot"] {
        handle_update(
            &state,
            &json!({"message": {
                "chat": {"id": 42}, "text": command
            }}),
        )
        .await
        .unwrap();
    }
    let requests = mock.requests.lock().await;
    assert_eq!(requests.len(), 2);
    for (method, body) in requests.iter() {
        assert_eq!(method, "sendMessage");
        assert_eq!(body["chat_id"], 42);
        let text = body["text"].as_str().unwrap();
        assert!(text.contains(&format!(
            "Git 提交：<code>{}</code>",
            env!("BACKEND_GIT_COMMIT")
        )));
        assert!(text.contains("启动时间：2099-01-02 03:04:05 UTC"));
        assert!(text.contains("运行时间：1 天 1 小时 1 分钟"));
    }
}

#[tokio::test]
async fn version_requires_admin_and_is_listed_in_admin_help() {
    let mock = TelegramMock::start().await;
    let state = fixture(&mock).await;
    for command in ["/version", "/version@our_bot"] {
        handle_update(
            &state,
            &json!({"message": {
                "chat": {"id": 99}, "text": command
            }}),
        )
        .await
        .unwrap();
    }
    assert!(mock.requests.lock().await.is_empty());
    handle_update(
        &state,
        &json!({"message": {
            "chat": {"id": 42}, "text": "/help"
        }}),
    )
    .await
    .unwrap();
    let requests = mock.requests.lock().await;
    assert!(
        requests[0].1["text"]
            .as_str()
            .unwrap()
            .contains("/version — 查看部署提交、启动时间和运行时长")
    );
}
