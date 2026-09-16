use chrono::{Duration, Utc};
use market_event_analyzer::{
    model::{EconomicEvent, EventStatus},
    repository::EventRepository,
};
use rust_decimal::Decimal;
use sqlx::sqlite::SqlitePoolOptions;

#[tokio::test]
async fn event_upsert_preserves_state_and_records_revisions() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let repository = EventRepository::new(pool);
    let mut event = EconomicEvent {
        id: 0,
        provider: "fixture".into(),
        provider_id: "us-cpi".into(),
        release_group_id: None,
        country: "United States".into(),
        currency: Some("USD".into()),
        category: "inflation".into(),
        event: "Inflation Rate YoY".into(),
        event_zh_cn: None,
        event_zh_tw: None,
        event_time: Utc::now() + Duration::minutes(5),
        importance: 3,
        actual: None,
        previous: Some(Decimal::new(28, 1)),
        consensus: Some(Decimal::new(29, 1)),
        forecast: None,
        unit: Some("%".into()),
        status: EventStatus::Scheduled,
        time_exact: true,
    };

    let id = repository.save_events(&[event.clone()]).await.unwrap()[0];
    repository
        .set_status(id, EventStatus::Watching)
        .await
        .unwrap();
    event.actual = Some(Decimal::new(32, 1));
    event.previous = Some(Decimal::new(27, 1));
    repository.save_events(&[event]).await.unwrap();

    let stored = repository.get(id).await.unwrap();
    assert_eq!(stored.status, EventStatus::Watching);
    assert_eq!(stored.actual, Some(Decimal::new(32, 1)));
    assert_eq!(stored.previous, Some(Decimal::new(27, 1)));
    assert!(stored.release_group_id.is_some());
    let observations = repository.observations(id).await.unwrap();
    assert_eq!(observations.len(), 2);
    assert_eq!(observations[0].actual, None);
    assert_eq!(observations[1].actual, Some(Decimal::new(32, 1)));
}
