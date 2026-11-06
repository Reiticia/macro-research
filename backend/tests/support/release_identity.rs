use super::*;
use market_event_analyzer::{
    calendar::{
        parse::{same_named_occurrence, same_occurrence},
        release_identity::{actual_for_target, normalize_period},
    },
    model::AppEvent,
    scheduler::biquote_release_loop,
};
use std::time::Duration as StdDuration;

fn michigan() -> EconomicEvent {
    let mut event = fixture_event(0);
    event.provider_id = "1001652126805".into();
    event.event = "Michigan Consumer Sentiment Prel".into();
    event.event_time = Utc::now() - Duration::seconds(10);
    event.release_metadata.period = Some("Nov. 2026".into());
    event.consensus = Some(Decimal::from(48));
    event.forecast = event.consensus;
    event.previous = Some(Decimal::new(463, 1));
    event.unit = None;
    event
}

fn biquote(target: &EconomicEvent) -> EconomicEvent {
    let mut source = target.clone();
    source.id = 0;
    source.provider = "biquote".into();
    source.provider_id = "mql5:268717".into();
    source.event = "Michigan Consumer Sentiment".into();
    source.release_metadata = Default::default();
    source.release_metadata.period = Some("2026-11-01T00:00:00Z".into());
    source.release_metadata.series_id = Some("mql5:840210001".into());
    source.consensus = Some(Decimal::new(485, 1));
    source.forecast = source.consensus;
    source.previous = Some(Decimal::from(48));
    source.actual = Some(Decimal::from(51));
    source
}

fn serve(app: &mut TestApp, rows: Vec<EconomicEvent>) {
    app.state.calendar_service = Arc::new(
        CalendarService::new(
            Arc::new(StubCalendar(Vec::new())),
            Arc::new(StubCalendar(Vec::new())),
            app.state.events.clone(),
        )
        .with_secondary(Arc::new(StubCalendar(rows))),
    );
}

async fn next_release(rx: &mut broadcast::Receiver<AppEvent>) -> AppEvent {
    tokio::time::timeout(StdDuration::from_secs(3), rx.recv())
        .await
        .unwrap()
        .unwrap()
}

#[test]
fn periods_are_explicit_and_michigan_stages_and_units_fail_closed() {
    for raw in [
        "Nov. 2026",
        "Nov 2026",
        "November 2026",
        "2026-11",
        "2026-11-01",
        "2026-11-01T00:00:00Z",
    ] {
        assert_eq!(normalize_period(raw).as_deref(), Some("2026-11"));
    }
    assert!(normalize_period("Nov").is_none());
    assert!(normalize_period("unknown").is_none());
    let target = michigan();
    let source = biquote(&target);
    assert!(same_occurrence(&target, &source));
    assert_ne!(target.consensus, source.consensus);
    assert_ne!(target.previous, source.previous);
    for variant in [
        "final",
        "different_period",
        "missing_period",
        "country",
        "time",
        "inexact",
        "translated_wrong_metric",
    ] {
        let mut bad = target.clone();
        match variant {
            "final" => bad.event = "Michigan Consumer Sentiment Final".into(),
            "different_period" => bad.release_metadata.period = Some("Oct. 2026".into()),
            "missing_period" => bad.release_metadata.period = None,
            "country" => bad.country = "Canada".into(),
            "time" => bad.event_time += Duration::seconds(1),
            "inexact" => bad.time_exact = false,
            "translated_wrong_metric" => {
                bad.event = "Michigan Current Conditions Prel".into();
                bad.event_zh_cn = Some("信心指数".into());
            }
            _ => unreachable!(),
        }
        let mut translated = source.clone();
        translated.event_zh_cn = Some("信心指数".into());
        assert!(!same_occurrence(&bad, &translated), "{variant}");
        assert!(!same_named_occurrence(&bad, &translated), "{variant}");
    }
    let mut unknown_target = target.clone();
    unknown_target.event = "Michigan Unknown Preliminary Label".into();
    unknown_target.event_zh_cn = Some("相同译名".into());
    let mut unknown_source = source.clone();
    unknown_source.event = "Michigan Unknown Label".into();
    unknown_source.event_zh_cn = Some("相同译名".into());
    assert!(!same_occurrence(&unknown_target, &unknown_source));
    let mut wrong_unit = source.clone();
    wrong_unit.unit = Some("%".into());
    assert!(actual_for_target(&target, &wrong_unit).is_none());
    let mut scaled_target = fixture_event(0);
    scaled_target.unit = Some("jobs".into());
    let mut scaled_source = scaled_target.clone();
    scaled_source.unit = Some("K jobs".into());
    scaled_source.actual = Some(Decimal::new(1655, 1));
    assert_eq!(
        actual_for_target(&scaled_target, &scaled_source),
        Some(Decimal::from(165500))
    );
}

#[test]
fn all_five_verified_michigan_pairs_match_but_wrong_series_does_not() {
    for (tv, bq, series, percent) in [
        (
            "Michigan Consumer Sentiment Prel",
            "Michigan Consumer Sentiment",
            "mql5:840210001",
            false,
        ),
        (
            "Michigan Consumer Expectations Prel",
            "Michigan Consumer Expectations",
            "mql5:840210002",
            false,
        ),
        (
            "Michigan Current Conditions Prel",
            "Michigan Current Conditions",
            "mql5:840210003",
            false,
        ),
        (
            "Michigan Inflation Expectations Prel",
            "Michigan Inflation Expectations",
            "mql5:840210004",
            true,
        ),
        (
            "Michigan 5 Year Inflation Expectations Prel",
            "Michigan 5-Year Inflation Expectations",
            "mql5:840210005",
            true,
        ),
    ] {
        let mut target = michigan();
        target.event = tv.into();
        let mut source = biquote(&target);
        source.event = bq.into();
        source.release_metadata.series_id = Some(series.into());
        if percent {
            target.unit = Some("%".into());
            source.unit = Some("%".into());
        }
        assert!(same_occurrence(&target, &source), "{tv}");
        assert!(actual_for_target(&target, &source).is_some(), "{tv}");
        source.release_metadata.series_id = Some("mql5:wrong-series".into());
        assert!(!same_occurrence(&target, &source), "{tv}");
    }
}

#[tokio::test]
async fn ingest_uses_verified_identity_and_preserves_primary_forecast_previous_and_metadata() {
    let app = app().await;
    let target = michigan();
    let source = biquote(&target);
    let id = app
        .state
        .events
        .save_events(std::slice::from_ref(&target))
        .await
        .unwrap()[0];
    assert_eq!(
        app.state
            .events
            .save_events(std::slice::from_ref(&source))
            .await
            .unwrap(),
        vec![id]
    );
    let stored = app.state.events.get(id).await.unwrap();
    assert_eq!(stored.actual, source.actual);
    assert_eq!(stored.consensus, target.consensus);
    assert_eq!(stored.previous, target.previous);
    assert_eq!(stored.unit, target.unit);
    assert_eq!(
        stored.release_metadata.period,
        target.release_metadata.period
    );
    assert_eq!(
        stored.release_metadata.actual_source.as_deref(),
        Some("biquote")
    );
    assert_eq!(
        stored.release_metadata.actual_source_id,
        Some(source.provider_id.clone())
    );
    assert!(stored.release_metadata.actual_observed_at.is_some());
    app.state
        .events
        .save_events(std::slice::from_ref(&target))
        .await
        .unwrap();
    let late = app.state.events.get(id).await.unwrap();
    assert_eq!(late.actual, stored.actual);
    assert_eq!(
        late.release_metadata.actual_observed_at,
        stored.release_metadata.actual_observed_at
    );
    assert_eq!(
        late.release_metadata.actual_source,
        stored.release_metadata.actual_source
    );
    assert_eq!(
        app.state
            .events
            .find_provider_event("biquote", &source.provider_id)
            .await
            .unwrap()
            .unwrap()
            .id,
        id
    );
}

#[tokio::test]
async fn fallback_first_promotion_adopts_primary_comparison_basis_without_losing_actual() {
    let app = app().await;
    let target = michigan();
    let source = biquote(&target);
    let id = app
        .state
        .events
        .save_events(std::slice::from_ref(&source))
        .await
        .unwrap()[0];
    let first = app.state.events.get(id).await.unwrap();
    assert_eq!(
        app.state
            .events
            .save_events(std::slice::from_ref(&target))
            .await
            .unwrap(),
        vec![id]
    );
    let promoted = app.state.events.get(id).await.unwrap();
    assert_eq!(promoted.provider, "trading_view");
    assert_eq!(promoted.actual, source.actual);
    assert_eq!(promoted.consensus, target.consensus);
    assert_eq!(promoted.previous, target.previous);
    assert_eq!(promoted.unit, target.unit);
    assert_eq!(
        promoted.release_metadata.actual_source,
        first.release_metadata.actual_source
    );
    assert_eq!(
        promoted.release_metadata.actual_observed_at,
        first.release_metadata.actual_observed_at
    );
    assert_eq!(
        app.state
            .events
            .find_provider_event("biquote", &source.provider_id)
            .await
            .unwrap()
            .unwrap()
            .id,
        id
    );
}

#[tokio::test]
async fn legacy_duplicate_owners_both_receive_actual_and_notifications_under_their_original_ids() {
    for already_valued in [false, true] {
        let mut app = app().await;
        let target = michigan();
        let source = biquote(&target);
        let id = app
            .state
            .events
            .save_events(std::slice::from_ref(&target))
            .await
            .unwrap()[0];
        // Simulate the two retained IDs in the operator's log. Do not delete or reassign either.
        let duplicate: i64 = sqlx::query_scalar("INSERT INTO economic_event(provider,provider_id,country,currency,category,event,event_time,importance,status,time_exact,actual,previous,consensus,forecast,unit,release_metadata)
            VALUES ('biquote',?,'United States','USD','sentiment',?,?,3,'watching',1,?,'48','48.5','48.5',NULL,?) RETURNING id")
            .bind(&source.provider_id).bind(&source.event).bind(source.event_time.to_rfc3339())
            .bind(if already_valued { Some("51") } else { None })
            .bind(serde_json::to_string(&source.release_metadata).unwrap()).fetch_one(&app.pool).await.unwrap();
        sqlx::query("INSERT INTO event_provider_alias(provider,provider_id,event_id) VALUES ('biquote',?,?)")
            .bind(&source.provider_id).bind(duplicate).execute(&app.pool).await.unwrap();
        let observation_count = app.state.events.observations(id).await.unwrap().len();
        serve(&mut app, vec![source.clone()]);
        let mut rx = app.state.event_bus.subscribe();
        let task = tokio::spawn(biquote_release_loop(app.state.clone()));
        let mut ids = Vec::new();
        for _ in 0..if already_valued { 1 } else { 2 } {
            if let AppEvent::EconomicEventReleased {
                event_id,
                consensus,
                ..
            } = next_release(&mut rx).await
            {
                ids.push(event_id);
                if event_id == id {
                    assert_eq!(consensus.as_deref(), Some("48"));
                }
            } else {
                panic!("unexpected event");
            }
        }
        task.abort();
        assert!(ids.contains(&id));
        if !already_valued {
            assert!(ids.contains(&duplicate));
        }
        let stored = app.state.events.get(id).await.unwrap();
        assert_eq!(stored.provider_id, target.provider_id);
        assert_eq!(stored.event, target.event);
        assert_eq!(stored.actual, source.actual);
        assert_eq!(stored.consensus, target.consensus);
        assert_eq!(stored.previous, target.previous);
        assert_eq!(
            app.state.events.get(duplicate).await.unwrap().actual,
            source.actual
        );
        assert_eq!(
            app.state
                .events
                .find_provider_event("biquote", &source.provider_id)
                .await
                .unwrap()
                .unwrap()
                .id,
            duplicate
        );
        assert_eq!(
            app.state.events.observations(id).await.unwrap().len(),
            observation_count
        );
        assert!(!app.state.events.claim_release(id).await.unwrap());
    }
}

#[tokio::test]
async fn ivey_source_specific_mapping_overrides_same_name_chinese_and_stale_alias() {
    let mut app = app().await;
    let mut unadjusted = michigan();
    unadjusted.provider_id = "tv:ivey:nsa".into();
    unadjusted.country = "Canada".into();
    unadjusted.event = "Ivey PMI".into();
    unadjusted.release_metadata.period = Some("Oct. 2026".into());
    unadjusted.event_zh_cn = Some("相同的旧译名".into());
    let mut adjusted = unadjusted.clone();
    adjusted.provider_id = "tv:ivey:sa".into();
    adjusted.event = "Ivey PMI s.a".into();
    let id_nsa = app
        .state
        .events
        .save_events(std::slice::from_ref(&unadjusted))
        .await
        .unwrap()[0];
    let id_sa = app
        .state
        .events
        .save_events(std::slice::from_ref(&adjusted))
        .await
        .unwrap()[0];
    assert_ne!(id_nsa, id_sa);
    let mut source_sa = adjusted.clone();
    source_sa.provider = "biquote".into();
    source_sa.provider_id = "mql5:315181".into();
    source_sa.event = "Ivey PMI".into();
    source_sa.release_metadata.period = Some("2026-10-01T00:00:00Z".into());
    source_sa.actual = Some(Decimal::new(583, 1));
    let mut source_nsa = source_sa.clone();
    source_nsa.provider_id = "mql5:315186".into();
    source_nsa.event = "Ivey PMI n.s.a.".into();
    source_nsa.actual = Some(Decimal::new(564, 1));
    assert!(!same_named_occurrence(&unadjusted, &source_sa));
    let mut unverified_third_source = source_sa.clone();
    unverified_third_source.provider = "forex_factory".into();
    assert!(!same_occurrence(&unadjusted, &unverified_third_source));
    // A bad alias retained from the old name matcher must not authorize writing the wrong row.
    sqlx::query(
        "INSERT INTO event_provider_alias(provider,provider_id,event_id) VALUES ('biquote',?,?)",
    )
    .bind(&source_sa.provider_id)
    .bind(id_nsa)
    .execute(&app.pool)
    .await
    .unwrap();
    app.state
        .events
        .save_events(std::slice::from_ref(&source_sa))
        .await
        .unwrap();
    assert!(app.state.events.get(id_nsa).await.unwrap().actual.is_none());
    serve(&mut app, vec![source_sa, source_nsa]);
    let mut rx = app.state.event_bus.subscribe();
    let task = tokio::spawn(biquote_release_loop(app.state.clone()));
    next_release(&mut rx).await;
    next_release(&mut rx).await;
    task.abort();
    assert_eq!(
        app.state.events.get(id_sa).await.unwrap().actual,
        Some(Decimal::new(583, 1))
    );
    assert_eq!(
        app.state.events.get(id_nsa).await.unwrap().actual,
        Some(Decimal::new(564, 1))
    );
}

#[tokio::test]
async fn missing_period_conflicts_duplicate_providers_or_contradictory_phases_never_fill() {
    for scenario in [
        "missing_period",
        "period",
        "final",
        "both_phases",
        "duplicate",
        "unit",
        "conflict",
    ] {
        let mut app = app().await;
        let mut target = michigan();
        match scenario {
            "missing_period" => target.release_metadata.period = None,
            "period" => target.release_metadata.period = Some("Oct. 2026".into()),
            "final" => target.event = "Michigan Consumer Sentiment Final".into(),
            _ => {}
        }
        let id = app
            .state
            .events
            .save_events(std::slice::from_ref(&target))
            .await
            .unwrap()[0];
        if matches!(scenario, "both_phases" | "duplicate") {
            let name = if scenario == "both_phases" {
                "Michigan Consumer Sentiment Final"
            } else {
                &target.event
            };
            sqlx::query("INSERT INTO economic_event(provider,provider_id,country,category,event,event_time,importance,time_exact,status,release_metadata)
                SELECT provider,'legacy-extra',country,category,?,event_time,importance,time_exact,status,release_metadata FROM economic_event WHERE id=?")
                .bind(name).bind(id).execute(&app.pool).await.unwrap();
        }
        let mut source = biquote(&target);
        source.release_metadata.period = Some("2026-11-01T00:00:00Z".into());
        let mut rows = Vec::new();
        if scenario == "unit" {
            source.unit = Some("%".into());
        }
        if scenario == "conflict" {
            let mut other = source.clone();
            other.provider_id = "mql5:conflicting".into();
            other.actual = Some(Decimal::from(999));
            rows.push(other);
        }
        rows.push(source);
        serve(&mut app, rows);
        let mut rx = app.state.event_bus.subscribe();
        let task = tokio::spawn(biquote_release_loop(app.state.clone()));
        tokio::time::sleep(StdDuration::from_millis(150)).await;
        task.abort();
        assert!(
            app.state.events.get(id).await.unwrap().actual.is_none(),
            "{scenario}"
        );
        assert!(rx.try_recv().is_err(), "{scenario}");
    }
}

#[tokio::test]
async fn migration_029_preserves_legacy_ids_values_aliases_and_observations() {
    use sqlx::Executor;
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    for migration in sqlx::migrate!("./migrations")
        .iter()
        .filter(|m| m.version < 29)
    {
        pool.execute(migration.sql.as_ref()).await.unwrap();
    }
    sqlx::query("INSERT INTO economic_event(id,provider,provider_id,country,category,event,event_time,importance,status,time_exact,previous,consensus,forecast)
        VALUES (798236,'trading_view','1001652126805','United States','sentiment','Michigan Consumer Sentiment Prel','2026-11-06T15:00:00Z',3,'watching',1,'46.3','48','48')")
        .execute(&pool).await.unwrap();
    sqlx::query("INSERT INTO event_provider_alias(provider,provider_id,event_id) VALUES ('trading_view','1001652126805',798236)").execute(&pool).await.unwrap();
    sqlx::query("INSERT INTO event_observation(event_id,observed_at,actual) VALUES (798236,'2026-11-06T14:59:00Z',NULL)").execute(&pool).await.unwrap();
    pool.execute(include_str!("../../migrations/029_release_metadata.sql"))
        .await
        .unwrap();
    let repo = EventRepository::new(pool.clone());
    let event = repo.get(798236).await.unwrap();
    assert_eq!(event.consensus, Some(Decimal::from(48)));
    assert_eq!(event.previous, Some(Decimal::new(463, 1)));
    assert!(event.release_metadata.period.is_none());
    assert!(event.release_metadata.actual_source.is_none());
    assert_eq!(
        repo.find_provider_event("trading_view", "1001652126805")
            .await
            .unwrap()
            .unwrap()
            .id,
        798236
    );
    assert_eq!(repo.observations(798236).await.unwrap().len(), 1);
    assert!(
        sqlx::query("PRAGMA foreign_key_check")
            .fetch_all(&pool)
            .await
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn missing_legacy_period_is_enriched_by_ordinary_sync_not_guessed() {
    let app = app().await;
    let fresh = michigan();
    let source = biquote(&fresh);
    let mut legacy = fresh.clone();
    legacy.release_metadata.period = None;
    let id = app.state.events.save_events(&[legacy]).await.unwrap()[0];
    let old = app.state.events.get(id).await.unwrap();
    assert!(
        !app.state
            .events
            .fill_biquote_release(&old, &source)
            .await
            .unwrap()
    );
    app.state
        .events
        .save_events(std::slice::from_ref(&fresh))
        .await
        .unwrap();
    let enriched = app.state.events.get(id).await.unwrap();
    assert_eq!(
        enriched.release_metadata.period,
        fresh.release_metadata.period
    );
    assert!(
        app.state
            .events
            .fill_biquote_release(&enriched, &source)
            .await
            .unwrap()
    );
    assert_eq!(
        app.state.events.get(id).await.unwrap().consensus,
        fresh.consensus
    );
}

#[tokio::test]
async fn period_or_unit_changed_during_request_prevents_a_stale_fill() {
    let app = app().await;
    let mut target = michigan();
    target.id = app
        .state
        .events
        .save_events(std::slice::from_ref(&target))
        .await
        .unwrap()[0];
    let source = biquote(&target);
    let mut changed = target.clone();
    changed.release_metadata.period = Some("Oct. 2026".into());
    app.state.events.save_events(&[changed]).await.unwrap();
    assert!(
        !app.state
            .events
            .fill_biquote_release(&target, &source)
            .await
            .unwrap()
    );
    assert!(
        app.state
            .events
            .get(target.id)
            .await
            .unwrap()
            .actual
            .is_none()
    );
    app.state
        .events
        .save_events(std::slice::from_ref(&target))
        .await
        .unwrap();
    sqlx::query("UPDATE economic_event SET unit='%' WHERE id=?")
        .bind(target.id)
        .execute(&app.pool)
        .await
        .unwrap();
    assert!(
        !app.state
            .events
            .fill_biquote_release(&target, &source)
            .await
            .unwrap()
    );
    sqlx::query("UPDATE economic_event SET unit=NULL WHERE id=?")
        .bind(target.id)
        .execute(&app.pool)
        .await
        .unwrap();
    // A contradictory phase inserted after target selection is rechecked under the write lock.
    sqlx::query("INSERT INTO economic_event(provider,provider_id,country,category,event,event_time,importance,time_exact,status,release_metadata)
        SELECT provider,'late-final',country,category,'Michigan Consumer Sentiment Final',event_time,importance,time_exact,status,release_metadata FROM economic_event WHERE id=?")
        .bind(target.id).execute(&app.pool).await.unwrap();
    assert!(
        !app.state
            .events
            .fill_biquote_release(&target, &source)
            .await
            .unwrap()
    );
}
