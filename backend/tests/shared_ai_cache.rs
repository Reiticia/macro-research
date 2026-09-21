use chrono::{Duration, Utc};
use market_event_analyzer::{
    model::{EconomicEvent, EventStatus},
    repository::EventRepository,
    shared_ai::{decide, submit_feedback},
};
use rust_decimal::Decimal;
use sqlx::{Row, sqlite::SqlitePoolOptions};

#[tokio::test]
async fn administrator_regeneration_queues_only_the_reviewed_shape() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let events = EventRepository::new(pool.clone());
    let event = EconomicEvent {
        id: 0,
        provider: "test".into(),
        provider_id: "shared-ai-lazy".into(),
        release_group_id: None,
        country: "United States".into(),
        currency: Some("USD".into()),
        category: "inflation".into(),
        event: "CPI YoY".into(),
        event_zh_cn: Some("消费者价格指数同比".into()),
        event_zh_tw: Some("消費者價格指數同比".into()),
        event_time: Utc::now() - Duration::hours(2),
        importance: 3,
        actual: Some(Decimal::new(32, 1)),
        previous: Some(Decimal::new(30, 1)),
        consensus: Some(Decimal::new(31, 1)),
        forecast: Some(Decimal::new(31, 1)),
        unit: Some("%".into()),
        status: EventStatus::Completed,
        time_exact: true,
    };
    let event_id = events.save_events(&[event]).await.unwrap()[0];
    sqlx::query(
        "INSERT INTO ai_analysis (event_id, language, method, timezone, revision, chain_json, data_analysis, market_outlook, model, generated_at) VALUES (?, 'zh-CN', 2, 'UTC', 1, '[]', 'data', 'outlook', 'test-model', ?)",
    )
    .bind(event_id)
    .bind(Utc::now().to_rfc3339())
    .execute(&pool)
    .await
    .unwrap();

    let feedback = submit_feedback(
        &pool,
        event_id,
        2,
        "zh-CN",
        1,
        "alice",
        "the outlook needs review",
    )
    .await
    .unwrap();
    decide(&pool, feedback, true).await.unwrap();

    let row = sqlx::query(
        "SELECT event_id, language, method, target_revision, status FROM shared_ai_job",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(row.get::<i64, _>("event_id"), event_id);
    assert_eq!(row.get::<String, _>("language"), "zh-CN");
    assert_eq!(row.get::<i64, _>("method"), 2);
    assert_eq!(row.get::<i64, _>("target_revision"), 2);
    assert_eq!(row.get::<String, _>("status"), "pending");
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM shared_ai_job")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(count, 1);
}
