use chrono::{DateTime, Duration, Utc};
use market_event_analyzer::{
    calendar::{
        CalendarProvider, ForexFactoryProvider,
        parse::{normalized_event_name, same_named_occurrence},
    },
    model::{EconomicEvent, EventStatus},
    repository::EventRepository,
};
use rust_decimal::Decimal;
use sqlx::{Row, sqlite::SqlitePoolOptions};

fn time() -> DateTime<Utc> {
    DateTime::parse_from_rfc3339("2026-11-05T13:30:00Z")
        .unwrap()
        .with_timezone(&Utc)
}
fn event(provider: &str, id: &str, name: &str) -> EconomicEvent {
    EconomicEvent {
        id: 0,
        provider: provider.into(),
        provider_id: id.into(),
        release_group_id: None,
        release_metadata: Default::default(),
        country: "United States".into(),
        currency: Some("USD".into()),
        category: "prices".into(),
        event: name.into(),
        event_zh_cn: None,
        event_zh_tw: None,
        event_time: time(),
        importance: 3,
        actual: None,
        previous: None,
        consensus: None,
        forecast: None,
        unit: Some("%".into()),
        status: EventStatus::Scheduled,
        time_exact: true,
    }
}
async fn repository() -> EventRepository {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    EventRepository::new(pool)
}
async fn count(repo: &EventRepository) -> i64 {
    sqlx::query_scalar("SELECT COUNT(*) FROM economic_event")
        .fetch_one(repo.pool())
        .await
        .unwrap()
}
#[test]
fn english_rule_lowercases_then_removes_everything_except_ascii_letters_and_digits() {
    assert_eq!(
        normalized_event_name(" Core_CPI (YoY)-2026! "),
        "corecpiyoy2026"
    );
    assert_eq!(normalized_event_name("CPI m/m"), "cpimm");
    assert_eq!(normalized_event_name("CPI MoM"), "cpimom");
    assert_eq!(normalized_event_name("K-12"), "k12");
    assert_eq!(normalized_event_name("失业率 !!!"), "");
    assert_ne!(
        normalized_event_name("Report 2026"),
        normalized_event_name("Report 2027")
    );
}
#[test]
fn chinese_display_labels_never_authorize_identity_even_across_language_fields() {
    let mut left = event("biquote", "a", "First English Name");
    let mut right = event("biquote", "b", "Different English Name");
    left.event_zh_cn = Some("失业率".into());
    right.event_zh_tw = Some("失业率".into());
    assert!(!same_named_occurrence(&left, &right));
    right.event_zh_tw = Some("失业率 ".into());
    assert!(!same_named_occurrence(&left, &right));
    left.event_zh_cn = Some(" ".into());
    right.event_zh_tw = Some(" ".into());
    assert!(!same_named_occurrence(&left, &right));
    left.event = "!!!".into();
    right.event = "？？？".into();
    left.event_zh_cn = None;
    right.event_zh_tw = None;
    assert!(!same_named_occurrence(&left, &right));
}
#[tokio::test]
async fn changed_id_and_formatted_english_name_reuse_id_keep_state_and_observation_foreign_keys() {
    let repo = repository().await;
    let old = event("biquote", "mql5:old", "Core CPI (YoY)");
    let id = repo.save_events(&[old.clone()]).await.unwrap()[0];
    repo.set_status(id, EventStatus::Watching).await.unwrap();
    let mut new = event("biquote", "fxempire:new", "core-cpi_yoy");
    new.actual = Some(Decimal::new(31, 1));
    new.status = EventStatus::Released;
    assert_eq!(repo.save_events(&[new.clone()]).await.unwrap(), vec![id]);
    assert_eq!(count(&repo).await, 1);
    assert_eq!(repo.get(id).await.unwrap().status, EventStatus::Watching);
    assert_eq!(repo.get(id).await.unwrap().actual, new.actual);
    assert_eq!(repo.observations(id).await.unwrap().len(), 2);
    for key in [&old.provider_id, &new.provider_id] {
        assert_eq!(
            repo.find_provider_event("biquote", key)
                .await
                .unwrap()
                .unwrap()
                .id,
            id
        );
    }
    // Alternating old/new identifiers never creates a second row or a new observation.
    repo.save_events(&[new.clone()]).await.unwrap();
    let mut old_again = old;
    old_again.actual = new.actual;
    old_again.status = EventStatus::Released;
    repo.save_events(&[old_again]).await.unwrap();
    assert_eq!(count(&repo).await, 1);
    assert_eq!(repo.observations(id).await.unwrap().len(), 2);
}
#[tokio::test]
async fn one_batch_duplicate_names_reuse_one_row_and_do_not_lose_an_actual_to_a_blank_copy() {
    let repo = repository().await;
    let mut first = event("biquote", "one", "Initial Jobless Claims");
    first.actual = Some(Decimal::new(210, 0));
    let second = event("biquote", "two", "initial-jobless_claims");
    let ids = repo.save_events(&[first.clone(), second]).await.unwrap();
    assert_eq!(ids.len(), 2);
    assert_eq!(ids[0], ids[1]);
    assert_eq!(count(&repo).await, 1);
    assert_eq!(repo.get(ids[0]).await.unwrap().actual, first.actual);
    assert_eq!(repo.observations(ids[0]).await.unwrap().len(), 1);
}
#[tokio::test]
async fn identical_cached_chinese_does_not_merge_different_english_names() {
    let repo = repository().await;
    let mut first = event("biquote", "first", "Consumer Climate");
    first.event_zh_cn = Some("消费者信心指数".into());
    let id = repo.save_events(&[first]).await.unwrap()[0];
    repo.save_event_name_translations(&[(
        "Household Sentiment".into(),
        "消费者信心指数".into(),
        "消費者信心指數".into(),
    )])
    .await
    .unwrap();
    let mut updated = event("biquote", "second", "Household Sentiment");
    updated.actual = Some(Decimal::new(75, 0));
    assert_ne!(repo.save_events(&[updated]).await.unwrap(), vec![id]);
    assert_eq!(count(&repo).await, 2);
    assert!(repo.get(id).await.unwrap().actual.is_none());
    let mut traditional = event("biquote", "third", "Another Sentiment Title");
    traditional.event_zh_tw = Some("消费者信心指数".into());
    assert_ne!(repo.save_events(&[traditional]).await.unwrap(), vec![id]);
    assert_eq!(count(&repo).await, 3);
}
#[tokio::test]
async fn exact_country_exact_instant_and_exact_time_flag_are_required_for_name_dedup() {
    let repo = repository().await;
    let mut old = event("trading_view", "first", "CPI");
    old.event_zh_cn = Some("居民消费价格指数".into());
    repo.save_events(&[old.clone()]).await.unwrap();
    let mut next_month = old.clone();
    next_month.provider_id = "month2".into();
    next_month.event_time += Duration::days(31);
    let mut country = old.clone();
    country.provider_id = "country2".into();
    country.country = "Canada".into();
    let mut fraction = old.clone();
    fraction.provider_id = "fraction".into();
    fraction.event_time += Duration::nanoseconds(1);
    let mut inexact = old;
    inexact.provider_id = "inexact".into();
    inexact.time_exact = false;
    repo.save_events(&[next_month, country, fraction, inexact])
        .await
        .unwrap();
    assert_eq!(count(&repo).await, 5);
}
#[tokio::test]
async fn blank_keys_or_chinese_whitespace_differences_do_not_merge_unrelated_rows() {
    let repo = repository().await;
    let mut a = event("biquote", "a", "!!!");
    let mut b = event("biquote", "b", "？？？");
    a.event_zh_cn = Some(" ".into());
    b.event_zh_cn = Some(" ".into());
    repo.save_events(&[a, b]).await.unwrap();
    let mut c = event("biquote", "c", "Different A");
    let mut d = event("biquote", "d", "Different B");
    c.event_zh_cn = Some("采购经理指数".into());
    d.event_zh_cn = Some("采购经理指数 ".into());
    repo.save_events(&[c, d]).await.unwrap();
    assert_eq!(count(&repo).await, 4);
}
#[tokio::test]
async fn lower_priority_aliases_cannot_overwrite_primary_values_or_move_its_schedule() {
    let repo = repository().await;
    let mut main = event("trading_view", "tv", "CPI");
    main.actual = Some(Decimal::new(31, 1));
    let id = repo.save_events(&[main.clone()]).await.unwrap()[0];
    let mut fallback = event("biquote", "bq", "c-p-i");
    fallback.actual = Some(Decimal::new(99, 1));
    assert_eq!(
        repo.save_events(&[fallback.clone()]).await.unwrap(),
        vec![id]
    );
    fallback.event_time += Duration::hours(1);
    assert_eq!(repo.save_events(&[fallback]).await.unwrap(), vec![id]);
    let stored = repo.get(id).await.unwrap();
    assert_eq!(stored.provider, "trading_view");
    assert_eq!(stored.actual, main.actual);
    assert_eq!(stored.event_time, main.event_time);
    assert_eq!(count(&repo).await, 1);
}
#[tokio::test]
async fn higher_priority_source_promotes_one_row_and_both_provider_lookups_still_work() {
    let repo = repository().await;
    let old = event("forex_factory", "ff", "Core CPI m/m");
    let id = repo.save_events(&[old.clone()]).await.unwrap()[0];
    let mut new = event("trading_view", "tv", "Core Inflation Rate MoM");
    new.actual = Some(Decimal::new(3, 1));
    assert_eq!(repo.save_events(&[new.clone()]).await.unwrap(), vec![id]);
    assert_eq!(
        repo.find_provider_event(&old.provider, &old.provider_id)
            .await
            .unwrap()
            .unwrap()
            .id,
        id
    );
    assert_eq!(
        repo.find_provider_event(&new.provider, &new.provider_id)
            .await
            .unwrap()
            .unwrap()
            .id,
        id
    );
    assert_eq!(count(&repo).await, 1);
}
#[tokio::test]
async fn historical_numbers_stay_protected_and_authenticated_imports_are_separate() {
    let repo = repository().await;
    let mut old = event("biquote", "past", "CPI");
    old.status = EventStatus::Historical;
    old.actual = Some(Decimal::new(31, 1));
    let id = repo.save_events(&[old.clone()]).await.unwrap()[0];
    let mut new = event("biquote", "replacement", "c-p-i");
    new.actual = Some(Decimal::new(99, 1));
    assert_eq!(repo.save_events(&[new]).await.unwrap(), vec![id]);
    assert_eq!(repo.get(id).await.unwrap().actual, old.actual);
    let mut archive = event("trading_economics_api", "archive", "CPI");
    archive.status = EventStatus::Historical;
    repo.save_events(&[archive]).await.unwrap();
    assert_eq!(count(&repo).await, 2);
}
#[tokio::test]
async fn ambiguous_legacy_rows_are_not_deleted_and_a_third_identity_is_skipped_while_other_rows_sync()
 {
    let repo = repository().await;
    let old = event("biquote", "legacy1", "CPI");
    repo.save_events(&[old]).await.unwrap();
    sqlx::query("INSERT INTO economic_event(provider,provider_id,country,category,event,event_time,importance,time_exact,status)
        SELECT provider,'legacy2',country,category,event,event_time,importance,time_exact,status FROM economic_event")
        .execute(repo.pool()).await.unwrap();
    let duplicate = event("trading_view", "third", "c-p-i");
    let unique = event("trading_view", "unique", "Nonfarm Payrolls");
    let ids = repo.save_events(&[duplicate, unique]).await.unwrap();
    assert_eq!(ids.len(), 1);
    assert_eq!(count(&repo).await, 3);
    assert!(
        repo.find_provider_event("trading_view", "third")
            .await
            .unwrap()
            .is_none()
    );
    assert_eq!(
        repo.find_provider_event("trading_view", "unique")
            .await
            .unwrap()
            .unwrap()
            .id,
        ids[0]
    );
}
#[tokio::test]
async fn forex_factory_raw_utc_notation_changes_do_not_create_two_records() {
    let make = |date: &str| serde_json::json!([{"title":"Core CPI m/m","country":"USD","date":date,"impact":"High"}]);
    let a = make("2026-11-05T13:30:00Z");
    let b = make("2026-11-05T13:30:00+00:00");
    let router = axum::Router::new()
        .route(
            "/a",
            axum::routing::get(move || {
                let a = a.clone();
                async move { axum::Json(a) }
            }),
        )
        .route(
            "/b",
            axum::routing::get(move || {
                let b = b.clone();
                async move { axum::Json(b) }
            }),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let server = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    let http = reqwest::Client::builder()
        .no_proxy()
        .timeout(std::time::Duration::from_secs(2))
        .build()
        .unwrap();
    let old = ForexFactoryProvider::new(http.clone(), format!("{base}/a"))
        .fetch_events(time() - Duration::days(1), time() + Duration::days(1))
        .await
        .unwrap();
    let new = ForexFactoryProvider::new(http, format!("{base}/b"))
        .fetch_events(time() - Duration::days(1), time() + Duration::days(1))
        .await
        .unwrap();
    assert_ne!(old[0].provider_id, new[0].provider_id);
    let repo = repository().await;
    let a = repo.save_events(&old).await.unwrap();
    let b = repo.save_events(&new).await.unwrap();
    assert_eq!(a, b);
    assert_eq!(count(&repo).await, 1);
    server.abort();
}
#[tokio::test]
async fn timestamp_z_storage_is_compared_as_the_same_exact_utc_instant() {
    let repo = repository().await;
    repo.save_events(&[event("biquote", "old", "CPI")])
        .await
        .unwrap();
    sqlx::query("UPDATE economic_event SET event_time='2026-11-05T13:30:00Z'")
        .execute(repo.pool())
        .await
        .unwrap();
    repo.save_events(&[event("biquote", "new", "c-p-i")])
        .await
        .unwrap();
    assert_eq!(count(&repo).await, 1);
}
#[tokio::test]
async fn migration_027_seeds_existing_identities_without_changing_events_or_observations() {
    use sqlx::Executor;
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    for m in sqlx::migrate!("./migrations")
        .iter()
        .filter(|m| m.version < 27)
    {
        pool.execute(m.sql.as_ref()).await.unwrap();
    }
    sqlx::query("INSERT INTO economic_event(provider,provider_id,country,category,event,event_time,importance) VALUES('biquote','old','United States','prices','CPI','2026-11-05T13:30:00+00:00',3)").execute(&pool).await.unwrap();
    sqlx::query("INSERT INTO event_observation(event_id,observed_at,actual) VALUES(1,'2026-11-05T13:31:00+00:00','3.1')").execute(&pool).await.unwrap();
    pool.execute(include_str!("../migrations/027_event_provider_aliases.sql"))
        .await
        .unwrap();
    // The current repository also reads the additive metadata column.
    pool.execute(include_str!("../migrations/029_release_metadata.sql"))
        .await
        .unwrap();
    let repo = EventRepository::new(pool);
    assert_eq!(
        repo.find_provider_event("biquote", "old")
            .await
            .unwrap()
            .unwrap()
            .id,
        1
    );
    assert_eq!(repo.observations(1).await.unwrap().len(), 1);
    assert_eq!(count(&repo).await, 1);
    assert_eq!(sqlx::query("SELECT event_id FROM event_provider_alias WHERE provider='biquote' AND provider_id='old'").fetch_one(repo.pool()).await.unwrap().get::<i64,_>(0),1);
}
#[tokio::test]
async fn conflicting_same_batch_actuals_do_not_choose_an_arbitrary_duplicate_value() {
    let repo = repository().await;
    let mut a = event("biquote", "a", "CPI");
    a.actual = Some(Decimal::new(31, 1));
    let mut b = event("biquote", "b", "c-p-i");
    b.actual = Some(Decimal::new(32, 1));
    let ids = repo.save_events(&[a.clone(), b]).await.unwrap();
    assert_eq!(ids[0], ids[1]);
    assert_eq!(count(&repo).await, 1);
    assert!(repo.get(ids[0]).await.unwrap().actual.is_none());
    assert_eq!(repo.observations(ids[0]).await.unwrap().len(), 1);
    // A subsequent unambiguous source response can fill the missing release normally.
    a.actual = Some(Decimal::new(33, 1));
    assert_eq!(repo.save_events(&[a.clone()]).await.unwrap(), vec![ids[0]]);
    assert_eq!(repo.get(ids[0]).await.unwrap().actual, a.actual);
}

#[tokio::test]
async fn conflicting_duplicate_values_preserve_previously_stored_numbers_and_history() {
    let repo = repository().await;
    let mut old = event("biquote", "old", "CPI");
    old.actual = Some(Decimal::new(30, 1));
    old.previous = Some(Decimal::new(29, 1));
    let id = repo.save_events(&[old.clone()]).await.unwrap()[0];
    let mut a = old.clone();
    a.provider_id = "new-a".into();
    a.actual = Some(Decimal::new(31, 1));
    let mut b = old.clone();
    b.provider_id = "new-b".into();
    b.actual = Some(Decimal::new(32, 1));
    assert_eq!(repo.save_events(&[a, b]).await.unwrap(), vec![id, id]);
    let stored = repo.get(id).await.unwrap();
    assert_eq!(stored.actual, old.actual);
    assert_eq!(stored.previous, old.previous);
    assert_eq!(repo.observations(id).await.unwrap().len(), 1);
}

#[tokio::test]
async fn concurrent_transactions_serialize_name_detection_and_reuse_the_same_row() {
    let path = std::env::temp_dir().join(format!(
        "event-dedup-{}-{}.sqlite",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(&path)
        .create_if_missing(true)
        .journal_mode(sqlx::sqlite::SqliteJournalMode::Wal)
        .busy_timeout(std::time::Duration::from_secs(10));
    let pool = SqlitePoolOptions::new()
        .max_connections(2)
        .connect_with(options)
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let repo = EventRepository::new(pool.clone());
    let a = [event("biquote", "a", "CPI")];
    let b = [event("biquote", "b", "c-p-i")];
    let (left, right) = tokio::join!(repo.save_events(&a), repo.save_events(&b));
    assert_eq!(left.unwrap(), right.unwrap());
    assert_eq!(count(&repo).await, 1);
    pool.close().await;
    drop(repo);
    drop(pool);
    // SQLx's connection worker may release its Windows file handle just after pool close.
    for attempt in 0..50 {
        match std::fs::remove_file(&path) {
            Ok(()) => return,
            Err(error) if error.raw_os_error() == Some(32) && attempt < 49 => {
                tokio::time::sleep(std::time::Duration::from_millis(20)).await
            }
            Err(error) => panic!("temporary SQLite cleanup failed: {error}"),
        }
    }
}
