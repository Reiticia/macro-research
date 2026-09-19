use market_event_analyzer::{event_names, repository::EventRepository};
use sqlx::sqlite::SqlitePoolOptions;

#[tokio::test]
async fn seeding_fills_missing_names_without_overwriting_existing_ones() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let repository = EventRepository::new(pool);

    // An administrator-approved correction must survive the seed.
    repository
        .apply_translation("CPI y/y", "自订译名", "自訂譯名")
        .await
        .unwrap();

    repository
        .save_event_name_translations(&event_names::rows())
        .await
        .unwrap();

    let translations = repository
        .cached_event_name_translations(&["CPI y/y".into(), "Nonfarm Payrolls".into()])
        .await
        .unwrap();
    assert_eq!(
        translations.get("CPI y/y"),
        Some(&("自订译名".to_owned(), "自訂譯名".to_owned())),
    );
    assert_eq!(
        translations.get("Nonfarm Payrolls"),
        Some(&("非农就业人数".to_owned(), "非農就業人數".to_owned())),
    );

    // Saved events with untranslated names are backfilled by the seed.
    let mut event = fixture();
    event.event = "Nonfarm Payrolls".into();
    event.category = "Nonfarm Payrolls".into();
    let id = repository.save_events(&[event]).await.unwrap()[0];
    repository
        .save_event_name_translations(&event_names::rows())
        .await
        .unwrap();
    let stored = repository.get(id).await.unwrap();
    assert_eq!(stored.event_zh_cn.as_deref(), Some("非农就业人数"));
    assert_eq!(stored.event_zh_tw.as_deref(), Some("非農就業人數"));
}

fn fixture() -> market_event_analyzer::model::EconomicEvent {
    use market_event_analyzer::model::{EconomicEvent, EventStatus};
    EconomicEvent {
        id: 0,
        provider: "fixture".into(),
        provider_id: "fixture-nonfarm".into(),
        release_group_id: None,
        country: "United States".into(),
        currency: Some("USD".into()),
        category: "employment".into(),
        event: "Nonfarm Payrolls".into(),
        event_zh_cn: None,
        event_zh_tw: None,
        event_time: chrono::Utc::now(),
        importance: 3,
        actual: None,
        previous: None,
        consensus: None,
        forecast: None,
        unit: Some("K".into()),
        status: EventStatus::Scheduled,
        time_exact: true,
    }
}
