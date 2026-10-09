use chrono::Utc;
use market_event_analyzer::{
    model::{EconomicEvent, EventStatus},
    repository::EventRepository,
};
use rust_decimal::Decimal;
use sqlx::sqlite::SqlitePoolOptions;

#[tokio::test]
async fn archived_unit_recovery_is_metadata_only_and_requires_matching_values() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let repository = EventRepository::new(pool);
    let mut fresh = EconomicEvent {
        id: 0,
        provider: "trading_view".into(),
        provider_id: "unit-recovery".into(),
        release_group_id: None,
        country: "United States".into(),
        currency: Some("USD".into()),
        category: "Initial Jobless Claims".into(),
        event: "Initial Jobless Claims".into(),
        event_zh_cn: None,
        event_zh_tw: None,
        event_time: Utc::now() - chrono::Duration::days(10),
        importance: 3,
        actual: Some(Decimal::from(197)),
        previous: Some(Decimal::from(198)),
        consensus: Some(Decimal::from(201)),
        forecast: Some(Decimal::from(201)),
        unit: Some("K".into()),
        status: EventStatus::Released,
        time_exact: true,
    };
    let mut old = fresh.clone();
    old.unit = None;
    old.status = EventStatus::Historical;
    let id = repository.save_events(&[old]).await.unwrap()[0];
    let observations = repository.observations(id).await.unwrap().len();
    fresh.status = EventStatus::Released;
    assert_eq!(
        repository.save_events(&[fresh.clone()]).await.unwrap(),
        vec![id]
    );
    let recovered = repository.get(id).await.unwrap();
    assert_eq!(recovered.unit.as_deref(), Some("K"));
    assert_eq!(recovered.actual, Some(Decimal::from(197)));
    assert_eq!(recovered.status, EventStatus::Historical);
    assert_eq!(
        repository.observations(id).await.unwrap().len(),
        observations
    );

    // Changed values must not be accompanied by a new magnitude label on the old record.
    let mut different = fresh.clone();
    different.unit = Some("M".into());
    different.actual = Some(Decimal::from(1));
    repository.save_events(&[different]).await.unwrap();
    assert_eq!(repository.get(id).await.unwrap().unit.as_deref(), Some("K"));
    fresh.unit = None;
    repository.save_events(&[fresh]).await.unwrap();
    assert_eq!(repository.get(id).await.unwrap().unit.as_deref(), Some("K"));
    assert_eq!(
        repository.observations(id).await.unwrap().len(),
        observations
    );
}
