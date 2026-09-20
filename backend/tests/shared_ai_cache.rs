use chrono::{Duration, Utc};
use market_event_analyzer::{
    model::{EconomicEvent, EventStatus},
    repository::EventRepository,
    shared_ai::{enqueue_event_bundle, prioritize_event_bundle},
};
use rust_decimal::Decimal;
use sqlx::{Row, sqlite::SqlitePoolOptions};

#[tokio::test]
async fn one_event_enqueues_exactly_three_languages_by_three_methods() {
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
        provider_id: "shared-ai-nine".into(),
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

    enqueue_event_bundle(&pool, event_id).await.unwrap();
    enqueue_event_bundle(&pool, event_id).await.unwrap();

    let rows = sqlx::query(
        "SELECT language, method, status FROM shared_ai_job WHERE event_id=? ORDER BY language,method",
    )
    .bind(event_id)
    .fetch_all(&pool)
    .await
    .unwrap();
    assert_eq!(rows.len(), 9);
    let shapes: Vec<(String, i64)> = rows
        .iter()
        .map(|row| (row.get("language"), row.get("method")))
        .collect();
    for language in ["en", "zh-CN", "zh-TW"] {
        for method in 1..=3 {
            assert!(shapes.contains(&(language.to_owned(), method)));
        }
    }
    assert!(
        rows.iter()
            .all(|row| row.get::<String, _>("status") == "pending")
    );

    prioritize_event_bundle(&pool, event_id).await.unwrap();
    let priorities: Vec<i64> =
        sqlx::query_scalar("SELECT priority FROM shared_ai_job WHERE event_id=?")
            .bind(event_id)
            .fetch_all(&pool)
            .await
            .unwrap();
    assert_eq!(priorities, vec![1; 9]);
}
