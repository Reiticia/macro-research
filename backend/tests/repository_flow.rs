use chrono::{Duration, Utc};
use market_event_analyzer::{
    market_selection::MarketSelection,
    model::MarketSymbol,
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
    repository.save_events(&[event.clone()]).await.unwrap();
    repository.save_events(&[event]).await.unwrap();

    let stored = repository.get(id).await.unwrap();
    assert_eq!(stored.status, EventStatus::Watching);
    assert_eq!(stored.actual, Some(Decimal::new(32, 1)));
    assert_eq!(stored.previous, Some(Decimal::new(27, 1)));
    assert!(stored.release_group_id.is_none());
    let observations = repository.observations(id).await.unwrap();
    assert_eq!(observations.len(), 2);
    assert_eq!(observations[0].actual, None);
    assert_eq!(observations[1].actual, Some(Decimal::new(32, 1)));
}

#[tokio::test]
async fn market_selection_is_persisted_once_and_read_back() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let repository = EventRepository::new(pool);
    let event = EconomicEvent {
        id: 0,
        provider: "fixture".into(),
        provider_id: "selection-test".into(),
        release_group_id: None,
        country: "United States".into(),
        currency: Some("USD".into()),
        category: "employment".into(),
        event: "Nonfarm Payrolls".into(),
        event_zh_cn: None,
        event_zh_tw: None,
        event_time: Utc::now() + Duration::minutes(5),
        importance: 3,
        actual: None,
        previous: None,
        consensus: None,
        forecast: None,
        unit: None,
        status: EventStatus::Watching,
        time_exact: true,
    };
    let event_id = repository.save_events(&[event]).await.unwrap()[0];
    let first = MarketSelection {
        symbols: vec![MarketSymbol::Dxy, MarketSymbol::Us2y],
        probabilities: [("dxy".to_owned(), 0.91)].into_iter().collect(),
        source: "jev",
        model: "jev-latest".into(),
    };
    let saved = repository
        .save_market_selection(event_id, &first)
        .await
        .unwrap();
    let ignored_duplicate = repository
        .save_market_selection(
            event_id,
            &MarketSelection {
                symbols: vec![MarketSymbol::Gold],
                probabilities: Default::default(),
                source: "category_fallback",
                model: "fallback".into(),
            },
        )
        .await
        .unwrap();
    assert_eq!(saved, first);
    assert_eq!(ignored_duplicate, first);
    assert_eq!(
        repository.market_selection(event_id).await.unwrap(),
        Some(first)
    );
    assert!(
        repository
            .market_selection_missing_active()
            .await
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn missed_releases_are_reclassified_as_historical() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let repository = EventRepository::new(pool);
    let now = Utc::now();
    let event = |provider_id: &str, hours_ago: i64, actual: Option<Decimal>| EconomicEvent {
        id: 0,
        provider: "fixture".into(),
        provider_id: provider_id.into(),
        release_group_id: None,
        country: "United States".into(),
        currency: Some("USD".into()),
        category: "inflation".into(),
        event: provider_id.into(),
        event_zh_cn: None,
        event_zh_tw: None,
        event_time: now - Duration::hours(hours_ago),
        importance: 1,
        actual,
        previous: None,
        consensus: None,
        forecast: None,
        unit: Some("%".into()),
        status: EventStatus::Scheduled,
        time_exact: true,
    };

    let value = Some(Decimal::new(32, 1));
    // Past the watch window with a published value: can never advance again.
    let stuck = repository
        .save_events(&[event("stuck", 3, value)])
        .await
        .unwrap()[0];
    // Timed out or flagged missing, then a later sync delivered the value.
    let timed_out = repository
        .save_events(&[event("timed-out", 3, value)])
        .await
        .unwrap()[0];
    repository
        .set_status(timed_out, EventStatus::Timeout)
        .await
        .unwrap();
    let missing = repository
        .save_events(&[event("missing", 3, value)])
        .await
        .unwrap()[0];
    repository
        .set_status(missing, EventStatus::DataUnavailable)
        .await
        .unwrap();
    // Still inside the watch window: the watcher owns this row.
    let recent = repository
        .save_events(&[event("recent", 0, value)])
        .await
        .unwrap()[0];
    // Past the window but no published value: the missing-release flow owns this row.
    let unreleased = repository
        .save_events(&[event("unreleased", 3, None)])
        .await
        .unwrap()[0];
    // A terminal status with evidence must never be rewritten.
    let completed = repository
        .save_events(&[event("completed", 3, value)])
        .await
        .unwrap()[0];
    repository
        .set_status(completed, EventStatus::Completed)
        .await
        .unwrap();

    let healed = repository
        .reconcile_missed_releases(now - Duration::minutes(30))
        .await
        .unwrap();

    assert_eq!(healed, 3);
    assert_eq!(
        repository.get(stuck).await.unwrap().status,
        EventStatus::Historical
    );
    assert_eq!(
        repository.get(timed_out).await.unwrap().status,
        EventStatus::Historical
    );
    assert_eq!(
        repository.get(missing).await.unwrap().status,
        EventStatus::Historical
    );
    assert_eq!(
        repository.get(recent).await.unwrap().status,
        EventStatus::Scheduled
    );
    assert_eq!(
        repository.get(unreleased).await.unwrap().status,
        EventStatus::Scheduled
    );
    assert_eq!(
        repository.get(completed).await.unwrap().status,
        EventStatus::Completed
    );
    // The reconciliation is idempotent.
    assert_eq!(
        repository
            .reconcile_missed_releases(now - Duration::minutes(30))
            .await
            .unwrap(),
        0
    );
}
