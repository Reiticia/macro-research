use super::*;
use market_event_analyzer::calendar::{
    fill_missing_values,
    release_identity::{ReleaseRejection, actual_for_target, release_value_for},
};

fn pair() -> (EconomicEvent, EconomicEvent) {
    let target = fixture_event(0);
    let mut source = target.clone();
    source.provider = "biquote".into();
    source.provider_id = "bq:print".into();
    source.actual = Some(Decimal::from(165));
    (target, source)
}

fn michigan_pair() -> (EconomicEvent, EconomicEvent) {
    let (mut target, mut source) = pair();
    target.event = "Michigan Consumer Sentiment Prel".into();
    target.unit = None;
    target.release_metadata.period = Some("Nov. 2026".into());
    source.event = "Michigan Consumer Sentiment".into();
    source.unit = None;
    source.release_metadata.period = Some("2026-11-01T00:00:00Z".into());
    source.release_metadata.series_id = Some("mql5:840210001".into());
    (target, source)
}

#[test]
fn scales_never_authorize_cross_dimension_conversion() {
    let (mut target, mut source) = pair();
    for (from, to) in [
        ("K", "%"),
        ("M", "USD"),
        ("K", "M"),
        ("M USD", "M EUR"),
        ("K jobs", "K barrels"),
        ("M barrels", "points"),
        ("mystery", "mystery"),
    ] {
        source.unit = Some(from.into());
        target.unit = Some(to.into());
        assert_eq!(actual_for_target(&target, &source), None, "{from} -> {to}");
    }
    for (from, to, expected) in [
        ("M USD", "K USD", Decimal::from(165000)),
        ("M barrels", "barrels", Decimal::from(165000000)),
        ("K jobs", "jobs", Decimal::from(165000)),
        ("T EUR", "B EUR", Decimal::from(165000)),
        ("B", "B", Decimal::from(165)),
    ] {
        source.unit = Some(from.into());
        target.unit = Some(to.into());
        assert_eq!(
            actual_for_target(&target, &source),
            Some(expected),
            "{from} -> {to}"
        );
    }
    source.actual = Some(Decimal::MAX);
    source.unit = Some("T USD".into());
    target.unit = Some("USD".into());
    assert!(actual_for_target(&target, &source).is_none());
}

#[test]
fn verified_nfp_dimension_does_not_infer_missing_scale_or_accept_wrong_dimension() {
    let (mut target, mut source) = pair();
    target.event = "Non Farm Payrolls".into();
    source.event = "Nonfarm Payrolls".into();
    target.unit = Some("K".into());
    source.unit = Some("K jobs".into());
    assert_eq!(
        release_value_for(&target, &source, &[]),
        Ok(Decimal::from(165))
    );
    target.unit = None;
    assert!(release_value_for(&target, &source, &[]).is_err());
    target.unit = Some("K".into());
    source.unit = Some("K USD".into());
    assert!(release_value_for(&target, &source, &[]).is_err());
}

#[tokio::test]
async fn generic_period_conflicts_cannot_merge_or_fill_even_with_a_stored_alias() {
    let app = app().await;
    let (mut target, mut source) = pair();
    target.release_metadata.period = Some("2026-11-01".into());
    source.release_metadata.period = Some("2026-11-08".into());
    let id = app
        .state
        .events
        .save_events(std::slice::from_ref(&target))
        .await
        .unwrap()[0];
    sqlx::query(
        "INSERT INTO event_provider_alias(provider,provider_id,event_id) VALUES ('biquote',?,?)",
    )
    .bind(&source.provider_id)
    .bind(id)
    .execute(&app.pool)
    .await
    .unwrap();
    target.id = id;
    assert_eq!(
        release_value_for(&target, &source, &[]),
        Err(ReleaseRejection::IdentityMismatch)
    );
    assert!(
        !app.state
            .events
            .fill_biquote_release(&target, &source)
            .await
            .unwrap()
    );
    app.state.events.save_events(&[source]).await.unwrap();
    assert!(app.state.events.get(id).await.unwrap().actual.is_none());
}

#[tokio::test]
async fn release_instant_queries_use_the_expression_index_and_preserve_legacy_timestamps() {
    use sqlx::Row;
    let app = app().await;
    let mut target = fixture_event(0);
    target.id = app
        .state
        .events
        .save_events(std::slice::from_ref(&target))
        .await
        .unwrap()[0];
    sqlx::query("UPDATE economic_event SET event_time=? WHERE id=?")
        .bind(
            target
                .event_time
                .to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true),
        )
        .bind(target.id)
        .execute(&app.pool)
        .await
        .unwrap();
    let plan = sqlx::query("EXPLAIN QUERY PLAN SELECT * FROM economic_event WHERE julianday(event_time)=julianday(?) ORDER BY id")
        .bind(target.event_time.to_rfc3339()).fetch_all(&app.pool).await.unwrap();
    assert!(plan.iter().any(|row| {
        row.get::<String, _>("detail")
            .contains("idx_event_release_instant")
    }));
    assert!(
        app.state
            .events
            .events_at_release_time(target.event_time)
            .await
            .unwrap()
            .iter()
            .any(|e| e.id == target.id)
    );
}

#[test]
fn fallback_keeps_comparison_basis_and_original_provenance() {
    let (mut target, mut source) = pair();
    target.provider = "forex_factory".into();
    target.event = "Core CPI m/m".into();
    source.event = "Core Inflation Rate MoM".into();
    source.previous = Some(Decimal::from(999));
    source.consensus = Some(Decimal::from(998));
    source.forecast = source.consensus;
    source.release_metadata.actual_source = Some("trading_view".into());
    source.release_metadata.actual_source_id = Some("original:print".into());
    source.release_metadata.actual_observed_at = Some(Utc::now() - Duration::minutes(5));
    let mut rows = vec![target.clone()];
    fill_missing_values(&mut rows, std::slice::from_ref(&source));
    let filled = &rows[0];
    assert_eq!(filled.actual, source.actual);
    assert_eq!(filled.previous, target.previous);
    assert_eq!(filled.consensus, target.consensus);
    assert_eq!(filled.forecast, target.forecast);
    assert_eq!(filled.unit, target.unit);
    assert_eq!(
        filled.release_metadata.actual_source,
        source.release_metadata.actual_source
    );
    assert_eq!(
        filled.release_metadata.actual_source_id,
        source.release_metadata.actual_source_id
    );
    assert_eq!(
        filled.release_metadata.actual_observed_at,
        source.release_metadata.actual_observed_at
    );
}

#[test]
fn fallback_cannot_bypass_ivey_veto_or_unit_checks() {
    let (mut target, mut source) = pair();
    target.provider = "forex_factory".into();
    target.country = "Canada".into();
    target.event = "Ivey PMI".into();
    target.unit = None;
    source.country = target.country.clone();
    source.event = target.event.clone();
    source.unit = None;
    source.release_metadata.period = Some("Oct. 2026".into());
    let mut rows = vec![target];
    fill_missing_values(&mut rows, &[source]);
    assert!(rows[0].actual.is_none());

    let (mut target, mut source) = pair();
    target.provider = "forex_factory".into();
    source.unit = Some("K".into());
    let mut rows = vec![target];
    fill_missing_values(&mut rows, &[source]);
    assert!(rows[0].actual.is_none());
}

#[tokio::test]
async fn ordinary_ingest_checks_phase_conflicts_in_database_and_later_batch_rows() {
    for already_stored in [true, false] {
        let app = app().await;
        let (target, source) = michigan_pair();
        let id = app
            .state
            .events
            .save_events(std::slice::from_ref(&target))
            .await
            .unwrap()[0];
        let mut final_row = target.clone();
        final_row.provider_id = "tv:final".into();
        final_row.event = "Michigan Consumer Sentiment Final".into();
        if already_stored {
            app.state.events.save_events(&[final_row]).await.unwrap();
            app.state.events.save_events(&[source]).await.unwrap();
        } else {
            app.state
                .events
                .save_events(&[source, final_row])
                .await
                .unwrap();
        }
        assert!(app.state.events.get(id).await.unwrap().actual.is_none());
        assert!(
            app.state
                .events
                .find_provider_event("biquote", "bq:print")
                .await
                .unwrap()
                .is_none()
        );
    }
}

#[tokio::test]
async fn stored_alias_does_not_bypass_duplicate_target_ambiguity_during_ingest() {
    let app = app().await;
    let (target, source) = pair();
    let id = app
        .state
        .events
        .save_events(std::slice::from_ref(&target))
        .await
        .unwrap()[0];
    sqlx::query("INSERT INTO economic_event(provider,provider_id,country,category,event,event_time,importance,time_exact,status,unit)
        SELECT provider,'duplicate-target',country,category,event,event_time,importance,time_exact,status,unit FROM economic_event WHERE id=?")
        .bind(id).execute(&app.pool).await.unwrap();
    sqlx::query(
        "INSERT INTO event_provider_alias(provider,provider_id,event_id) VALUES ('biquote',?,?)",
    )
    .bind(&source.provider_id)
    .bind(id)
    .execute(&app.pool)
    .await
    .unwrap();
    app.state.events.save_events(&[source]).await.unwrap();
    assert!(app.state.events.get(id).await.unwrap().actual.is_none());
}

#[tokio::test]
async fn higher_priority_bad_alias_cannot_relabel_or_overwrite_existing_row() {
    let app = app().await;
    let (mut target, mut source) = pair();
    target.country = "Canada".into();
    target.event = "Ivey PMI s.a".into();
    target.unit = None;
    target.release_metadata.period = Some("Oct. 2026".into());
    target.actual = Some(Decimal::from(60));
    source.country = "Canada".into();
    source.event = "Ivey PMI n.s.a.".into();
    source.unit = None;
    source.release_metadata.period = Some("2026-10-01T00:00:00Z".into());
    let id = app
        .state
        .events
        .save_events(std::slice::from_ref(&source))
        .await
        .unwrap()[0];
    sqlx::query("INSERT INTO event_provider_alias(provider,provider_id,event_id) VALUES ('trading_view',?,?)")
        .bind(&target.provider_id).bind(id).execute(&app.pool).await.unwrap();
    let observations = app.state.events.observations(id).await.unwrap().len();
    app.state.events.save_events(&[target]).await.unwrap();
    let stored = app.state.events.get(id).await.unwrap();
    assert_eq!(stored.provider, source.provider);
    assert_eq!(stored.provider_id, source.provider_id);
    assert_eq!(stored.event, source.event);
    assert_eq!(stored.actual, source.actual);
    assert_eq!(
        app.state.events.observations(id).await.unwrap().len(),
        observations
    );
}

#[tokio::test]
async fn incompatible_promotion_preserves_old_row_without_relabeling_its_basis() {
    let app = app().await;
    let (target, mut source) = pair();
    source.unit = Some("K".into());
    source.consensus = Some(Decimal::from(999));
    let id = app
        .state
        .events
        .save_events(std::slice::from_ref(&source))
        .await
        .unwrap()[0];
    app.state
        .events
        .save_events(std::slice::from_ref(&target))
        .await
        .unwrap();
    let old = app.state.events.get(id).await.unwrap();
    assert_eq!(old.provider, source.provider);
    assert_eq!(old.actual, source.actual);
    assert_eq!(old.unit, source.unit);
    assert_eq!(old.consensus, source.consensus);
    assert!(
        app.state
            .events
            .find_provider_event("trading_view", &target.provider_id)
            .await
            .unwrap()
            .is_none()
    );
    // An authoritative primary value can subsequently promote the row without copying old values.
    let mut primary = target;
    primary.actual = Some(Decimal::from(3));
    app.state
        .events
        .save_events(std::slice::from_ref(&primary))
        .await
        .unwrap();
    let updated = app.state.events.get(id).await.unwrap();
    assert_eq!(updated.provider, primary.provider);
    assert_eq!(updated.actual, primary.actual);
    assert_eq!(updated.consensus, primary.consensus);
    assert_eq!(updated.unit, primary.unit);
}

#[tokio::test]
async fn weekly_provenance_survives_ingest_instead_of_becoming_forex_factory() {
    let app = app().await;
    let (mut target, source) = pair();
    target.provider = "forex_factory".into();
    let mut rows = vec![target];
    fill_missing_values(&mut rows, std::slice::from_ref(&source));
    let id = app.state.events.save_events(&rows).await.unwrap()[0];
    let stored = app.state.events.get(id).await.unwrap();
    assert_eq!(
        stored.release_metadata.actual_source.as_deref(),
        Some("biquote")
    );
    assert_eq!(
        stored.release_metadata.actual_source_id.as_deref(),
        Some("bq:print")
    );
}

#[test]
fn shared_gate_reports_identity_phase_and_unit_failures() {
    let (target, source) = michigan_pair();
    let mut final_row = target.clone();
    final_row.event = "Michigan Consumer Sentiment Final".into();
    assert_eq!(
        release_value_for(&target, &source, &[target.clone(), final_row]),
        Err(ReleaseRejection::PhaseConflict)
    );
    let (target, mut source) = pair();
    source.event = "Unrelated Indicator".into();
    assert_eq!(
        release_value_for(&target, &source, &[]),
        Err(ReleaseRejection::IdentityMismatch)
    );
    source.event = target.event.clone();
    source.unit = Some("K".into());
    assert_eq!(
        release_value_for(&target, &source, &[]),
        Err(ReleaseRejection::IncompatibleUnits)
    );
}
