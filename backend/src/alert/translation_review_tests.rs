use crate::alert::translation_review::*;
use crate::{
    alert::{
        bot::tests::fixture,
        test_support::{TelegramMock, test_pool},
    },
    translation::{EventNameTranslation, TranslationVerdict},
    translation_review::ReviewAudit,
};
use serde_json::{Value, json};

fn candidate() -> EventNameTranslation {
    EventNameTranslation {
        source: "CPI <test>".into(),
        zh_cn: "消费者价格指数".into(),
        zh_tw: "消費者價格指數".into(),
    }
}
fn verdict(approved: bool) -> TranslationVerdict {
    TranslationVerdict {
        source: candidate().source,
        approved,
        confidence: Some(if approved { 0.95 } else { 0.4 }),
        threshold: Some(0.85),
        reason: None,
    }
}
fn cb(id: i64, admin: i64, action: &str) -> Value {
    json!({"id":"fixture","from":{"id":admin},"message":{"message_id":77,"chat":{"id":admin,"type":"private"}},"data":format!("trreview:{action}:{id}")})
}

#[tokio::test]
async fn pending_delivery_survives_restart_and_is_deduplicated_per_admin() {
    let mock = TelegramMock::start().await;
    let pool = test_pool().await;
    let audit = ReviewAudit::new(pool.clone());
    let id = audit
        .record(&candidate(), Some(&verdict(false)), "typesafe", true)
        .await
        .unwrap();
    assert_eq!(
        id,
        audit
            .record(&candidate(), Some(&verdict(false)), "typesafe", true)
            .await
            .unwrap()
    );
    deliver_pending(&pool, &mock.client, &[42]).await.unwrap();
    deliver_pending(&pool, &mock.client, &[42]).await.unwrap();
    let requests = mock.requests.lock().await;
    assert_eq!(requests.len(), 1);
    let text = requests[0].1["text"].as_str().unwrap();
    assert!(text.contains("CPI &lt;test&gt;"));
    assert!(text.contains("40.00%"));
    assert!(text.contains("85.00%"));
    assert!(text.contains("消费者价格指数"));
    assert_eq!(
        requests[0].1["reply_markup"]["inline_keyboard"][0][0]["callback_data"],
        format!("trreview:yes:{id}")
    );
    drop(requests);
    assert!(
        ReviewAudit::new(pool.clone())
            .blocked(&candidate().source)
            .await
            .unwrap()
    );
}

#[tokio::test]
async fn callbacks_are_private_admin_only_atomic_and_do_not_overwrite_existing_names() {
    let mock = TelegramMock::start().await;
    let state = fixture(&mock).await;
    let audit = ReviewAudit::new(state.pool.clone());
    let id = audit
        .record(&candidate(), Some(&verdict(false)), "typesafe", true)
        .await
        .unwrap();
    for mut callback_data in [cb(id, 99, "yes"), cb(id, 42, "yes")] {
        if callback_data["from"]["id"] == 42 {
            callback_data["from"]["id"] = json!(99);
        }
        callback(&state, &callback_data).await.unwrap();
    }
    let mut group = cb(id, 42, "yes");
    group["message"]["chat"]["type"] = json!("group");
    callback(&state, &group).await.unwrap();
    assert!(mock.requests.lock().await.is_empty());
    sqlx::query("INSERT INTO economic_event(provider,provider_id,country,category,event,event_time,importance,actual) VALUES('test','review','US','inflation',?,'2026-11-13T00:00:00Z',3,'2.5')").bind(&candidate().source).execute(&state.pool).await.unwrap();
    callback(&state, &cb(id, 42, "yes")).await.unwrap();
    callback(&state, &cb(id, 42, "no")).await.unwrap();
    let status: String =
        sqlx::query_scalar("SELECT status FROM translation_review_audit WHERE id=?")
            .bind(id)
            .fetch_one(&state.pool)
            .await
            .unwrap();
    assert_eq!(status, "accepted");
    let actual: String =
        sqlx::query_scalar("SELECT actual FROM economic_event WHERE provider_id='review'")
            .fetch_one(&state.pool)
            .await
            .unwrap();
    assert_eq!(actual, "2.5");
    let mut newer = candidate();
    newer.zh_cn = "不能覆盖".into();
    let second = audit
        .record(&newer, Some(&verdict(false)), "typesafe", true)
        .await
        .unwrap();
    audit.decide(second, 42, true).await.unwrap();
    let saved: String =
        sqlx::query_scalar("SELECT zh_cn FROM event_name_translation WHERE source_text=?")
            .bind(&candidate().source)
            .fetch_one(&state.pool)
            .await
            .unwrap();
    assert_eq!(saved, candidate().zh_cn);
    let status: String =
        sqlx::query_scalar("SELECT status FROM translation_review_audit WHERE id=?")
            .bind(second)
            .fetch_one(&state.pool)
            .await
            .unwrap();
    assert_eq!(status, "superseded");
}

#[tokio::test]
async fn audit_queries_show_model_success_and_unknown_confidence_without_model_calls() {
    let mock = TelegramMock::start().await;
    let state = fixture(&mock).await;
    let audit = ReviewAudit::new(state.pool.clone());
    audit
        .record(&candidate(), Some(&verdict(true)), "typesafe", false)
        .await
        .unwrap();
    audit
        .record(&candidate(), None, "typesafe", true)
        .await
        .unwrap();
    for command in [
        "/translation_audit approved",
        "/translation_audit unavailable",
    ] {
        message(
            &state,
            &json!({"text":command,"chat":{"id":42,"type":"private"},"from":{"id":42}}),
        )
        .await
        .unwrap();
    }
    let requests = mock.requests.lock().await;
    assert!(
        requests
            .iter()
            .any(|(_, b)| b["text"].as_str().unwrap_or("").contains("95.00%"))
    );
    assert!(requests.iter().any(|(_, b)| {
        b["text"]
            .as_str()
            .unwrap_or("")
            .contains("不可用（未返回有效评分）")
    }));
}

#[tokio::test]
async fn concurrent_decisions_allow_only_one_winner() {
    let pool = test_pool().await;
    let audit = ReviewAudit::new(pool.clone());
    let id = audit
        .record(&candidate(), None, "typesafe", true)
        .await
        .unwrap();
    let (a, b) = tokio::join!(audit.decide(id, 42, true), audit.decide(id, 43, true));
    let replies = [a.unwrap(), b.unwrap()];
    assert_eq!(
        replies.iter().filter(|r| r.contains("已人工采纳")).count(),
        1
    );
    assert_eq!(replies.iter().filter(|r| r.contains("已处理")).count(), 1);
}

#[tokio::test]
async fn delivery_failure_is_persistent_bounded_and_manual_query_remains_available() {
    use axum::{Json, Router, routing::post};
    let pool = test_pool().await;
    let id = ReviewAudit::new(pool.clone())
        .record(&candidate(), None, "typesafe", true)
        .await
        .unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let app = Router::new().fallback(post(|| async {
        Json(json!({"ok":false,"description":"synthetic delivery failure"}))
    }));
    let task = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let client = crate::alert::TelegramClient::new(reqwest::Client::new(), base, "synthetic");
    deliver_pending(&pool, &client, &[42]).await.unwrap();
    deliver_pending(&pool, &client, &[42]).await.unwrap();
    let attempts: i64 =
        sqlx::query_scalar("SELECT attempts FROM translation_review_delivery WHERE review_id=?")
            .bind(id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(attempts, 1);
    for _ in 0..4 {
        sqlx::query("UPDATE translation_review_delivery SET attempted_at=unixepoch()-3601")
            .execute(&pool)
            .await
            .unwrap();
        deliver_pending(&pool, &client, &[42]).await.unwrap();
    }
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT attempts FROM translation_review_delivery WHERE review_id=?"
        )
        .bind(id)
        .fetch_one(&pool)
        .await
        .unwrap(),
        3
    );
    assert!(
        ReviewAudit::new(pool.clone())
            .blocked(&candidate().source)
            .await
            .unwrap()
    );
    task.abort();
}

#[tokio::test]
async fn rejected_or_pending_names_cannot_be_published_by_a_late_automatic_writer() {
    let pool = test_pool().await;
    let audit = ReviewAudit::new(pool.clone());
    let id = audit
        .record(&candidate(), None, "typesafe", true)
        .await
        .unwrap();
    let repo = crate::repository::EventRepository::new(pool.clone());
    for rejected in [false, true] {
        if rejected {
            audit.decide(id, 42, false).await.unwrap();
        }
        repo.save_event_name_translations(&[(
            candidate().source,
            candidate().zh_cn,
            candidate().zh_tw,
        )])
        .await
        .unwrap();
        assert!(
            repo.cached_event_name_translations(&[candidate().source])
                .await
                .unwrap()
                .is_empty()
        );
    }
}

#[tokio::test]
async fn migration_preserves_existing_translations_and_missing_scores_are_not_invented() {
    let pool = sqlx::sqlite::SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    let mut previous = sqlx::migrate!("./migrations");
    previous.migrations = std::borrow::Cow::Owned(
        previous
            .migrations
            .iter()
            .filter(|m| m.version < 32)
            .cloned()
            .collect(),
    );
    previous.run(&pool).await.unwrap();
    sqlx::query("INSERT INTO event_name_translation(source_text,zh_cn,zh_tw) VALUES('legacy','旧译名','舊譯名')").execute(&pool).await.unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    assert_eq!(
        sqlx::query_scalar::<_, String>(
            "SELECT zh_cn FROM event_name_translation WHERE source_text='legacy'"
        )
        .fetch_one(&pool)
        .await
        .unwrap(),
        "旧译名"
    );
    let id = ReviewAudit::new(pool.clone())
        .record(&candidate(), None, "typesafe", true)
        .await
        .unwrap();
    assert!(
        sqlx::query_scalar::<_, Option<f64>>(
            "SELECT confidence FROM translation_review_audit WHERE id=?"
        )
        .bind(id)
        .fetch_one(&pool)
        .await
        .unwrap()
        .is_none()
    );
}
