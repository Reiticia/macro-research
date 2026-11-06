use super::*;
use market_event_analyzer::{
    model::AppEvent,
    scheduler::{biquote_release_loop, event_watch_loop},
};
use std::{
    sync::Mutex,
    time::{Duration as StdDuration, Instant},
};

type Requests = Arc<Mutex<Vec<(Instant, chrono::DateTime<Utc>, chrono::DateTime<Utc>)>>>;

struct ReleaseFeed {
    rows: Vec<EconomicEvent>,
    ready_on: usize,
    calls: Requests,
}

#[async_trait]
impl CalendarProvider for ReleaseFeed {
    async fn fetch_events(
        &self,
        start: chrono::DateTime<Utc>,
        end: chrono::DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let count = {
            let mut calls = self.calls.lock().unwrap();
            calls.push((Instant::now(), start, end));
            calls.len()
        };
        Ok(self
            .rows
            .iter()
            .cloned()
            .map(|mut row| {
                if count < self.ready_on {
                    row.actual = None;
                }
                row
            })
            .collect())
    }
}

fn use_feed(app: &mut TestApp, rows: Vec<EconomicEvent>, ready_on: usize) -> Requests {
    let calls = Arc::new(Mutex::new(Vec::new()));
    app.state.calendar_service = Arc::new(
        CalendarService::new(
            Arc::new(StubCalendar(Vec::new())),
            Arc::new(StubCalendar(Vec::new())),
            app.state.events.clone(),
        )
        .with_secondary(Arc::new(ReleaseFeed {
            rows,
            ready_on,
            calls: calls.clone(),
        })),
    );
    calls
}

async fn missing_event(app: &TestApp) -> EconomicEvent {
    let mut event = fixture_event(0);
    event.event_time = Utc::now() - Duration::seconds(10);
    event.provider_id = "nfp-primary".into();
    event.event = "Non Farm Payrolls".into();
    event.unit = Some("K".into());
    event.id = app
        .state
        .events
        .save_events(&[event.clone()])
        .await
        .unwrap()[0];
    event
}

fn print_for(event: &EconomicEvent) -> EconomicEvent {
    EconomicEvent {
        id: 0,
        provider: "biquote".into(),
        provider_id: "mql5:nfp".into(),
        event: "Nonfarm Payrolls".into(),
        actual: Some(Decimal::ZERO), // Zero is a real release, not a missing value.
        previous: Some(Decimal::from(100)),
        consensus: Some(Decimal::from(120)),
        forecast: Some(Decimal::from(120)),
        unit: Some("K jobs".into()),
        ..event.clone()
    }
}

struct SlowPrimary;
#[async_trait]
impl CalendarProvider for SlowPrimary {
    async fn fetch_events(
        &self,
        _: chrono::DateTime<Utc>,
        _: chrono::DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        tokio::time::sleep(StdDuration::from_secs(20)).await;
        Ok(Vec::new())
    }
}

#[tokio::test]
async fn polls_every_two_seconds_independent_of_slow_primary_and_stops_after_actual() {
    let mut app = app().await;
    let event = missing_event(&app).await;
    let source = print_for(&event);
    let calls = Arc::new(Mutex::new(Vec::new()));
    app.state.calendar_service = Arc::new(
        CalendarService::new(
            Arc::new(SlowPrimary),
            Arc::new(StubCalendar(Vec::new())),
            app.state.events.clone(),
        )
        .with_secondary(Arc::new(ReleaseFeed {
            rows: vec![source.clone()],
            ready_on: 2,
            calls: calls.clone(),
        })),
    );
    let mut receiver = app.state.event_bus.subscribe();
    let ordinary = tokio::spawn(event_watch_loop(
        app.state.clone(),
        app.state.config.calendar.clone(),
        app.state.config.scheduler.clone(),
    ));
    let fast = tokio::spawn(biquote_release_loop(app.state.clone()));
    let published = tokio::time::timeout(StdDuration::from_secs(6), receiver.recv())
        .await
        .unwrap()
        .unwrap();
    assert!(
        matches!(published, AppEvent::EconomicEventReleased { event_id, actual: Some(value), .. } if event_id == event.id && value == "0")
    );
    let stored = app.state.events.get(event.id).await.unwrap();
    assert_eq!(stored.provider, "trading_view");
    assert_eq!(stored.provider_id, event.provider_id);
    assert_eq!(stored.unit, source.unit);
    assert_eq!(stored.consensus, source.consensus);
    assert_eq!(stored.previous, source.previous);
    assert_eq!(
        app.state.events.observations(event.id).await.unwrap().len(),
        1
    );
    assert_eq!(
        app.state
            .events
            .find_provider_event("biquote", "mql5:nfp")
            .await
            .unwrap()
            .unwrap()
            .id,
        event.id
    );
    tokio::time::sleep(StdDuration::from_millis(2200)).await;
    fast.abort();
    ordinary.abort();
    let calls = calls.lock().unwrap();
    assert_eq!(calls.len(), 2);
    assert!(calls[1].0.duration_since(calls[0].0) >= StdDuration::from_millis(1900));
    for (_, start, end) in calls.iter() {
        assert_eq!(*start, event.event_time);
        assert_eq!(*end - *start, Duration::seconds(1));
    }
    assert!(receiver.try_recv().is_err());
}

#[tokio::test]
async fn merges_simultaneous_releases_and_never_uses_primary_or_weekly_feed() {
    let mut app = app().await;
    let first = missing_event(&app).await;
    let mut second = first.clone();
    second.id = 0;
    second.provider_id = "unemployment-primary".into();
    second.event = "Unemployment Rate".into();
    second.unit = Some("%".into());
    second.id = app
        .state
        .events
        .save_events(&[second.clone()])
        .await
        .unwrap()[0];
    let mut other = print_for(&second);
    other.provider_id = "mql5:unemployment".into();
    other.event = second.event.clone();
    other.unit = second.unit.clone();
    other.actual = Some(Decimal::new(42, 1));
    let calls = use_feed(&mut app, vec![print_for(&first), other], 1);
    let mut receiver = app.state.event_bus.subscribe();
    let task = tokio::spawn(biquote_release_loop(app.state.clone()));
    for _ in 0..2 {
        tokio::time::timeout(StdDuration::from_secs(3), receiver.recv())
            .await
            .unwrap()
            .unwrap();
    }
    task.abort();
    assert_eq!(calls.lock().unwrap().len(), 1);
    assert_eq!(
        app.state.events.get(second.id).await.unwrap().actual,
        Some(Decimal::new(42, 1))
    );
}

#[tokio::test]
async fn no_requests_before_release_after_timeout_for_inexact_low_priority_or_valued_events() {
    let mut app = app().await;
    for (index, offset, exact, importance, actual) in [
        (0, 1, true, 3, None),
        (1, -31, true, 3, None),
        (2, -1, false, 3, None),
        (3, -1, true, 1, None),
        (4, -1, true, 3, Some(Decimal::ONE)),
    ] {
        let mut event = fixture_event(0);
        event.provider_id = format!("excluded-{index}");
        event.event_time = Utc::now() + Duration::minutes(offset);
        event.event = format!("Excluded {index}");
        event.time_exact = exact;
        event.importance = importance;
        event.actual = actual;
        app.state.events.save_events(&[event]).await.unwrap();
    }
    let calls = use_feed(&mut app, Vec::new(), 1);
    let task = tokio::spawn(biquote_release_loop(app.state.clone()));
    tokio::time::sleep(StdDuration::from_millis(150)).await;
    task.abort();
    assert!(calls.lock().unwrap().is_empty());
}

#[tokio::test]
async fn rejects_wrong_identity_time_country_ambiguous_and_conflicting_values() {
    for scenario in [
        "name",
        "time",
        "country",
        "inexact",
        "conflict",
        "ambiguous",
        "alias_owner",
    ] {
        let mut app = app().await;
        let event = missing_event(&app).await;
        let mut source = print_for(&event);
        let mut rows = Vec::new();
        match scenario {
            "name" => source.event = "ADP Employment Change".into(),
            "time" => source.event_time += Duration::seconds(1),
            "country" => source.country = "Canada".into(),
            "inexact" => source.time_exact = false,
            "conflict" => {
                let mut other = source.clone();
                other.provider_id = "mql5:duplicate".into();
                other.actual = Some(Decimal::from(999));
                rows.push(other);
            }
            "ambiguous" => {
                sqlx::query("INSERT INTO economic_event(provider,provider_id,country,currency,category,event,event_time,importance,status,time_exact)
                    SELECT provider,'legacy-duplicate',country,currency,category,event,event_time,importance,status,time_exact FROM economic_event WHERE id=?")
                    .bind(event.id).execute(&app.pool).await.unwrap();
            }
            "alias_owner" => {
                let unrelated = app
                    .state
                    .events
                    .save_events(&[fixture_event(5)])
                    .await
                    .unwrap()[0];
                sqlx::query("INSERT INTO event_provider_alias(provider,provider_id,event_id) VALUES ('biquote','mql5:nfp',?)")
                    .bind(unrelated).execute(&app.pool).await.unwrap();
            }
            _ => unreachable!(),
        }
        rows.push(source);
        let calls = use_feed(&mut app, rows, 1);
        let mut receiver = app.state.event_bus.subscribe();
        let task = tokio::spawn(biquote_release_loop(app.state.clone()));
        tokio::time::sleep(StdDuration::from_millis(150)).await;
        task.abort();
        assert_eq!(calls.lock().unwrap().len(), 1, "{scenario}");
        assert!(
            app.state
                .events
                .get(event.id)
                .await
                .unwrap()
                .actual
                .is_none(),
            "{scenario}"
        );
        assert!(receiver.try_recv().is_err(), "{scenario}");
    }
}

#[tokio::test]
async fn accepts_cached_chinese_known_alias_and_legacy_utc_timestamp_without_model_calls() {
    for scenario in ["chinese", "alias", "timestamp_z"] {
        let mut app = app().await;
        let mut event = missing_event(&app).await;
        let mut source = print_for(&event);
        match scenario {
            "chinese" => {
                event.event_zh_cn = Some("非农就业人数变化".into());
                app.state
                    .events
                    .save_events(&[event.clone()])
                    .await
                    .unwrap();
                source.event = "Different Source Label".into();
                source.event_zh_cn = None;
                app.state
                    .events
                    .save_event_name_translations(&[(
                        source.event.clone(),
                        "非农就业人数变化".into(),
                        "非農就業人數變化".into(),
                    )])
                    .await
                    .unwrap();
            }
            "alias" => {
                source.event = "Previously Verified Source Label".into();
                sqlx::query("INSERT INTO event_provider_alias(provider,provider_id,event_id) VALUES ('biquote','mql5:nfp',?)")
                    .bind(event.id).execute(&app.pool).await.unwrap();
            }
            "timestamp_z" => {
                sqlx::query("UPDATE economic_event SET event_time=? WHERE id=?")
                    .bind(
                        event
                            .event_time
                            .to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true),
                    )
                    .bind(event.id)
                    .execute(&app.pool)
                    .await
                    .unwrap();
            }
            _ => unreachable!(),
        }
        let calls = use_feed(&mut app, vec![source], 1);
        let mut receiver = app.state.event_bus.subscribe();
        let task = tokio::spawn(biquote_release_loop(app.state.clone()));
        tokio::time::timeout(StdDuration::from_secs(3), receiver.recv())
            .await
            .unwrap()
            .unwrap();
        task.abort();
        assert_eq!(calls.lock().unwrap().len(), 1, "{scenario}");
        assert_eq!(
            app.state.events.get(event.id).await.unwrap().actual,
            Some(Decimal::ZERO),
            "{scenario}"
        );
    }
}

#[tokio::test]
async fn late_fill_does_not_overwrite_another_value_or_rescheduled_occurrence_and_claim_is_single()
{
    let app = app().await;
    let event = missing_event(&app).await;
    let source = print_for(&event);
    assert!(
        app.state
            .events
            .fill_biquote_release(&event, &source)
            .await
            .unwrap()
    );
    assert!(
        !app.state
            .events
            .fill_biquote_release(&event, &source)
            .await
            .unwrap()
    );
    assert!(app.state.events.claim_release(event.id).await.unwrap());
    assert!(!app.state.events.claim_release(event.id).await.unwrap());
    assert!(
        !app.state
            .events
            .transition_status(event.id, EventStatus::Watching, EventStatus::Timeout)
            .await
            .unwrap()
    );
    // A late empty primary response must not erase the fast-poll value or its units.
    app.state
        .events
        .save_events_without_observations(std::slice::from_ref(&event))
        .await
        .unwrap();
    let stored = app.state.events.get(event.id).await.unwrap();
    assert_eq!(stored.actual, source.actual);
    assert_eq!(stored.unit, source.unit);
    let mut renamed_empty = event.clone();
    renamed_empty.event = "Nonfarm Payrolls Revised Label".into();
    app.state
        .events
        .save_events_without_observations(&[renamed_empty])
        .await
        .unwrap();
    assert_eq!(
        app.state.events.get(event.id).await.unwrap().actual,
        source.actual
    );
    sqlx::query("UPDATE economic_event SET actual=NULL,status='watching',event_time=? WHERE id=?")
        .bind((event.event_time + Duration::hours(1)).to_rfc3339())
        .bind(event.id)
        .execute(&app.pool)
        .await
        .unwrap();
    assert!(
        !app.state
            .events
            .fill_biquote_release(&event, &source)
            .await
            .unwrap()
    );
}
