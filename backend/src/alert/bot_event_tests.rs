use super::*;
use crate::{
    alert::test_support::TelegramMock,
    fcm::tests::{FcmMock, event},
    repository::EventRepository,
};
use axum::http::StatusCode;
use chrono::{Duration, Utc};
use serde_json::json;

fn message(chat_id: i64, text: &str) -> Value {
    json!({"message": {"chat": {"id": chat_id}, "text": text}})
}

#[tokio::test]
async fn events_lists_nearby_ids_times_and_test_commands_without_sending_or_writing() {
    let telegram = TelegramMock::start().await;
    let fcm = FcmMock::start(StatusCode::OK, json!({})).await;
    let mut state = super::tests::fixture(&telegram).await;
    state.fcm = Some(fcm.notifier.clone());
    let repo = EventRepository::new(state.pool.clone());
    let now = Utc::now();
    let mut upcoming = event();
    upcoming.event_time = now + Duration::minutes(5);
    let mut previous = upcoming.clone();
    previous.provider_id = "previous".into();
    previous.event_time = now - Duration::minutes(10);
    previous.time_exact = false;
    let mut distant = upcoming.clone();
    distant.provider_id = "distant".into();
    distant.event = "far-away-event".into();
    distant.event_time = now + Duration::days(20);
    let ids = repo
        .save_events(&[upcoming.clone(), previous, distant])
        .await
        .unwrap();
    let before = serde_json::to_value(repo.get(ids[0]).await.unwrap()).unwrap();

    handle_update(&state, &message(42, "/events@our_bot"))
        .await
        .unwrap();
    let replies = telegram.requests.lock().await;
    assert_eq!(replies.len(), 1);
    let text = replies[0].1["text"].as_str().unwrap();
    assert!(text.contains("非农就业人数 / Nonfarm Payrolls"));
    assert!(text.contains("United States"));
    assert!(text.contains(&upcoming.event_time.format("%Y-%m-%d %H:%M").to_string()));
    assert!(text.contains("UTC"));
    assert!(text.contains("具体时间待定"));
    assert!(text.contains(&format!("/test_fcm {}", ids[0])));
    assert!(
        text.find(&format!("ID：<code>{}", ids[0])).unwrap()
            < text.find(&format!("ID：<code>{}", ids[1])).unwrap()
    );
    assert!(!text.contains("far-away-event"));
    assert!(fcm.requests.lock().await.is_empty());
    assert_eq!(
        serde_json::to_value(repo.get(ids[0]).await.unwrap()).unwrap(),
        before
    );
}

#[tokio::test]
async fn events_searches_retained_english_chinese_country_and_currency() {
    let telegram = TelegramMock::start().await;
    let state = super::tests::fixture(&telegram).await;
    let repo = EventRepository::new(state.pool.clone());
    let mut old = event();
    old.event_time = Utc::now() - Duration::days(30);
    let id = repo.save_events(&[old]).await.unwrap()[0];
    for keyword in ["nonfarm payrolls", "非农", "非農", "united states", "usd"] {
        handle_update(&state, &message(42, &format!("/events@our_bot {keyword}")))
            .await
            .unwrap();
    }
    let replies = telegram.requests.lock().await;
    assert_eq!(replies.len(), 5);
    for (_, body) in replies.iter() {
        let text = body["text"].as_str().unwrap();
        assert!(text.contains(&format!("ID：<code>{id}</code>")), "{text}");
        assert!(text.contains("全部已缓存事件"));
    }
}

#[tokio::test]
async fn events_search_is_literal_parameterized_and_html_escaped() {
    let telegram = TelegramMock::start().await;
    let state = super::tests::fixture(&telegram).await;
    let repo = EventRepository::new(state.pool.clone());
    let mut special = event();
    special.event = "Rate %_ '<tag>&".into();
    special.event_zh_cn = Some("<测试>&".into());
    special.country = "<country>&".into();
    let mut other = event();
    other.provider_id = "unmatched".into();
    other.event = "unmatched-event".into();
    repo.save_events(&[special, other]).await.unwrap();
    handle_update(&state, &message(42, "/events %_"))
        .await
        .unwrap();
    handle_update(&state, &message(42, "/events ' OR 1=1 --"))
        .await
        .unwrap();
    let replies = telegram.requests.lock().await;
    let text = replies[0].1["text"].as_str().unwrap();
    assert!(text.contains("&lt;测试&gt;&amp;"));
    assert!(text.contains("&lt;country&gt;&amp;"));
    assert!(text.contains("&lt;tag&gt;&amp;"));
    assert!(!text.contains("unmatched-event"));
    assert!(
        replies[1].1["text"]
            .as_str()
            .unwrap()
            .contains("未找到匹配")
    );
}

#[tokio::test]
async fn events_requires_admin_handles_empty_cache_and_rejects_long_keywords() {
    let telegram = TelegramMock::start().await;
    let state = super::tests::fixture(&telegram).await;
    handle_update(&state, &message(99, "/events"))
        .await
        .unwrap();
    handle_update(&state, &message(99, "/events CPI"))
        .await
        .unwrap();
    assert!(telegram.requests.lock().await.is_empty());
    handle_update(&state, &message(42, "/events"))
        .await
        .unwrap();
    handle_update(
        &state,
        &message(42, &format!("/events {}", "字".repeat(101))),
    )
    .await
    .unwrap();
    let replies = telegram.requests.lock().await;
    assert!(
        replies[0].1["text"]
            .as_str()
            .unwrap()
            .contains("暂无已缓存事件")
    );
    assert!(replies[1].1["text"].as_str().unwrap().contains("最多 100"));
}

#[tokio::test]
async fn events_bounds_results_and_message_length_and_uses_stable_tie_order() {
    let telegram = TelegramMock::start().await;
    let state = super::tests::fixture(&telegram).await;
    let repo = EventRepository::new(state.pool.clone());
    let now = Utc::now();
    let mut events = Vec::new();
    for i in 0..15 {
        let mut item = event();
        item.provider_id = format!("event-{i}");
        item.event_time = now;
        item.event = "long<&😀".repeat(100);
        item.event_zh_cn = Some("中文<&😀".repeat(100));
        item.country = "country<&😀".repeat(100);
        events.push(item);
    }
    let ids = repo.save_events(&events).await.unwrap();
    let matches = repo.search_for_admin("long", now).await.unwrap();
    assert_eq!(matches.len(), 11);
    assert_eq!(matches.iter().map(|e| e.id).collect::<Vec<_>>(), ids[..11]);
    handle_update(&state, &message(42, "/events long"))
        .await
        .unwrap();
    let replies = telegram.requests.lock().await;
    let text = replies[0].1["text"].as_str().unwrap();
    assert!(text.encode_utf16().count() <= 4096);
    assert!((1..=10).contains(&text.matches("ID：<code>").count()));
    assert!(text.contains("结果较多"));
    assert!(text.contains("&lt;&amp;"));
    assert!(!text.contains(&"中文".repeat(100)));
}

#[tokio::test]
async fn events_is_in_admin_help_and_menu_only() {
    let telegram = TelegramMock::start().await;
    let state = super::tests::fixture(&telegram).await;
    handle_update(&state, &message(42, "/help")).await.unwrap();
    telegram.client.set_admin_commands(&[42]).await.unwrap();
    let replies = telegram.requests.lock().await;
    assert!(
        replies[0].1["text"]
            .as_str()
            .unwrap()
            .contains("/events [关键词]")
    );
    assert!(
        replies[1].1["commands"]
            .as_array()
            .unwrap()
            .iter()
            .any(|c| c["command"] == "events")
    );
    assert!(
        !replies[2].1["commands"]
            .as_array()
            .unwrap()
            .iter()
            .any(|c| c["command"] == "events")
    );
}
