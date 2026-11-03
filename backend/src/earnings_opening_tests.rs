use super::*;

#[tokio::test]
async fn migration_preserves_existing_release_prices_without_replaying_preupgrade_jobs() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::raw_sql(include_str!(
        "../migrations/024_earnings_stock_collection.sql"
    ))
    .execute(&pool)
    .await
    .unwrap();
    let start = now().timestamp();
    sqlx::query("INSERT INTO earnings_stock_tracking(date,symbol,company_name,actual_eps,detected_at,ends_at) VALUES('2026-11-02','AAPL','Apple','1.2',?,?)")
        .bind(start).bind(start + 3600).execute(&pool).await.unwrap();
    let id: i64 = sqlx::query_scalar("SELECT id FROM earnings_stock_tracking")
        .fetch_one(&pool)
        .await
        .unwrap();
    save_quote(&pool, id, &quote("AAPL", now(), 100.0), now())
        .await
        .unwrap();
    sqlx::raw_sql(include_str!(
        "../migrations/025_earnings_opening_collection.sql"
    ))
    .execute(&pool)
    .await
    .unwrap();
    let result = market(&pool, now().date_naive(), now()).await;
    assert_eq!(result.tracking.unwrap().detected_at, now());
    assert_eq!(result.snapshots.len(), 1);
    assert_eq!(result.snapshots[0].close, 100.0);
    assert!(result.opening_session.is_none());
}

#[tokio::test]
async fn batch_limit_is_shared_and_fair_across_both_phases() {
    let pool = pool().await;
    let detected = Utc.with_ymd_and_hms(2026, 11, 3, 14, 20, 0).unwrap();
    let date = detected.date_naive();
    for index in 0..35 {
        let mut row = event(&format!("S{index}"), None);
        row.session = "pre_market".into();
        observe(
            &pool,
            date,
            &[row.clone()],
            detected - Duration::seconds(65),
        )
        .await;
        row.actual_eps = Some("1".into());
        observe(&pool, date, &[row], detected).await;
    }
    let provider = Arc::new(Provider::default());
    let clock = Arc::new(Mutex::new(detected + Duration::minutes(10)));
    let service = collector(&pool, &provider, &clock);
    for _ in 0..3 {
        service.collect_once().await.unwrap();
        *clock.lock().unwrap() += Duration::seconds(15);
    }
    for table in ["earnings_stock_tracking", "earnings_stock_opening"] {
        let count: i64 = sqlx::query_scalar(&format!(
            "SELECT COUNT(*) FROM {table} WHERE last_attempt_at IS NOT NULL"
        ))
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(count, 35);
    }
}

async fn release(pool: &SqlitePool, time: DateTime<Utc>, session: &str) -> NaiveDate {
    let date = time.with_timezone(&New_York).date_naive();
    let mut row = event("AAPL", None);
    row.session = session.into();
    observe(pool, date, &[row.clone()], time - Duration::seconds(65)).await;
    row.actual_eps = Some("1.2".into());
    assert_eq!(observe(pool, date, &[row], time).await, 1);
    date
}
async fn market(pool: &SqlitePool, date: NaiveDate, time: DateTime<Utc>) -> EarningsStockResponse {
    market_response(pool, date, "AAPL", time).await.unwrap()
}

#[tokio::test]
async fn pre_market_schedules_same_day_and_after_hours_next_day_without_polling_before_open() {
    for (hour, session, next_day) in [(12, "pre_market", 3), (22, "after_hours", 4)] {
        let pool = pool().await;
        let detected = Utc.with_ymd_and_hms(2026, 11, 3, hour, 0, 0).unwrap();
        let date = release(&pool, detected, session).await;
        let open = Utc.with_ymd_and_hms(2026, 11, next_day, 14, 30, 0).unwrap();
        let result = market(&pool, date, detected).await;
        let opening = result.opening_session.unwrap();
        assert_eq!(opening.time_basis, "regular_session_open");
        assert_eq!(opening.tracking.detected_at, detected);
        assert_eq!(opening.tracking.starts_at, open);
        assert_eq!(opening.tracking.ends_at, open + Duration::hours(1));
        assert_eq!(opening.tracking.status, "scheduled");
        assert_eq!(opening.coverage.expected_minute_count, 0);
        assert!(opening.snapshots.is_empty());
        assert!(!opening.coverage.window_ended);
        let provider = Arc::new(Provider::default());
        let clock = Arc::new(Mutex::new(open - Duration::seconds(1)));
        let service = collector(&pool, &provider, &clock);
        assert_eq!(service.collect_once().await.unwrap(), 0);
        assert!(provider.calls.lock().unwrap().is_empty());
        *clock.lock().unwrap() = open;
        *provider.reply.lock().unwrap() = Some(quote("AAPL", open, 150.0));
        assert_eq!(service.collect_once().await.unwrap(), 1);
        let result = market(&pool, date, open).await;
        assert!(result.snapshots.is_empty());
        assert_eq!(result.tracking.unwrap().status, "completed");
        let opening = result.opening_session.unwrap();
        assert_eq!(opening.tracking.status, "collecting");
        assert_eq!(opening.snapshots.len(), 1);
        assert_eq!(opening.snapshots[0].open, 150.0);
    }
}

#[tokio::test]
async fn overlapping_release_and_opening_windows_share_requests_but_not_their_samples_or_baselines()
{
    let pool = pool().await;
    let detected = Utc.with_ymd_and_hms(2026, 11, 3, 14, 20, 0).unwrap();
    let date = release(&pool, detected, "pre_market").await;
    let open = detected + Duration::minutes(10);
    let provider = Arc::new(Provider::default());
    let clock = Arc::new(Mutex::new(detected + Duration::seconds(5)));
    let service = collector(&pool, &provider, &clock);
    *provider.reply.lock().unwrap() = Some(quote("AAPL", clock_time(&clock), 100.0));
    assert_eq!(service.collect_once().await.unwrap(), 1);
    *clock.lock().unwrap() = open + Duration::seconds(10);
    *provider.reply.lock().unwrap() = Some(quote("AAPL", clock_time(&clock), 120.0));
    assert_eq!(service.collect_once().await.unwrap(), 2);
    assert_eq!(provider.calls.lock().unwrap().len(), 2); // once each iteration, not once per phase
    let result = market(&pool, date, clock_time(&clock)).await;
    assert_eq!(result.snapshots[0].open, 100.0);
    let opening = result.opening_session.unwrap();
    assert_eq!(opening.snapshots.len(), 1);
    assert_eq!(opening.snapshots[0].open, 120.0);
    assert_eq!(opening.coverage.expected_minute_count, 1);
    assert_eq!(opening.tracking.starts_at, open);
    assert_eq!(
        result.tracking.unwrap().ends_at,
        detected + Duration::hours(1)
    );
}

#[tokio::test]
async fn opening_rejects_preopen_and_window_end_quotes_and_does_not_extend_on_late_detection() {
    let pool = pool().await;
    let open = Utc.with_ymd_and_hms(2026, 11, 3, 14, 30, 0).unwrap();
    let detected = open + Duration::minutes(10);
    let date = release(&pool, detected, "pre_market").await;
    let provider = Arc::new(Provider::default());
    let clock = Arc::new(Mutex::new(detected));
    let service = collector(&pool, &provider, &clock);
    *provider.reply.lock().unwrap() = Some(quote("AAPL", detected, 110.0));
    assert_eq!(service.collect_once().await.unwrap(), 2);
    let opening = market(&pool, date, detected).await.opening_session.unwrap();
    assert_eq!(opening.tracking.starts_at, open);
    assert_eq!(opening.tracking.ends_at, open + Duration::hours(1));
    assert_eq!(opening.coverage.missing_minute_count, 10);
    // A missing first ten minutes is not silently moved to the next trading day.
    *clock.lock().unwrap() = open + Duration::hours(1);
    *provider.reply.lock().unwrap() = Some(quote("AAPL", clock_time(&clock), 115.0));
    assert_eq!(service.collect_once().await.unwrap(), 1); // release window only
    let opening = market(&pool, date, clock_time(&clock))
        .await
        .opening_session
        .unwrap();
    assert_eq!(opening.snapshots.len(), 1);
    assert!(opening.coverage.window_ended);
    assert_eq!(opening.coverage.expected_minute_count, 60);
    assert_eq!(opening.coverage.missing_minute_count, 59);
    assert_eq!(opening.tracking.status, "completed");
}

#[tokio::test]
async fn opening_filters_preopen_quotes_even_when_fresh_and_keeps_real_ohlc() {
    let pool = pool().await;
    let detected = Utc.with_ymd_and_hms(2026, 11, 3, 12, 0, 0).unwrap();
    let date = release(&pool, detected, "pre_market").await;
    let open = detected + Duration::minutes(150);
    let provider = Arc::new(Provider::default());
    let clock = Arc::new(Mutex::new(open + Duration::seconds(30)));
    let service = collector(&pool, &provider, &clock);
    *provider.reply.lock().unwrap() = Some(quote("AAPL", open - Duration::seconds(1), 99.0));
    assert_eq!(service.collect_once().await.unwrap(), 0);
    let opening = market(&pool, date, clock_time(&clock))
        .await
        .opening_session
        .unwrap();
    assert_eq!(
        opening.tracking.last_error.as_deref(),
        Some("stock_quote_outside_collection_window")
    );
    for (second, price) in [(20, 105.0), (5, 100.0), (30, 102.0)] {
        *provider.reply.lock().unwrap() =
            Some(quote("AAPL", open + Duration::seconds(second), price));
        assert_eq!(service.collect_once().await.unwrap(), 1);
    }
    let opening = market(&pool, date, clock_time(&clock))
        .await
        .opening_session
        .unwrap();
    let row = &opening.snapshots[0];
    assert_eq!(
        (row.open, row.high, row.low, row.close),
        (100.0, 105.0, 100.0, 102.0)
    );
    assert!(opening.tracking.last_error.is_none());
}

#[tokio::test]
async fn unknown_sessions_do_not_guess_and_corrections_do_not_duplicate_or_reschedule_opening() {
    let pool = pool().await;
    let detected = now();
    let date = release(&pool, detected, "unknown").await;
    assert!(
        market(&pool, date, detected)
            .await
            .opening_session
            .is_none()
    );
    let pool = self::pool().await;
    let date = release(&pool, detected, "after_hours").await;
    let initial = market(&pool, date, detected).await.opening_session.unwrap();
    let mut corrected = event("AAPL", Some("2.4"));
    corrected.session = "pre_market".into();
    assert_eq!(
        observe(&pool, date, &[corrected], detected + Duration::seconds(65)).await,
        0
    );
    let final_window = market(&pool, date, detected).await.opening_session.unwrap();
    assert_eq!(initial.tracking.starts_at, final_window.tracking.starts_at);
    assert_eq!(initial.tracking.id, final_window.tracking.id);
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM earnings_stock_opening")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(count, 1);
}

#[tokio::test]
async fn restart_preserves_scheduled_opening_across_weekend_and_dst_without_extending_window() {
    let temporary = temporary::CacheDir::new();
    std::fs::create_dir_all(temporary.path()).unwrap();
    let options = SqliteConnectOptions::new()
        .filename(temporary.path().join("opening.db"))
        .create_if_missing(true)
        .foreign_keys(true);
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(options.clone())
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let detected = Utc.with_ymd_and_hms(2026, 10, 30, 21, 0, 0).unwrap();
    let date = release(&pool, detected, "after_hours").await;
    pool.close().await;
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(options)
        .await
        .unwrap();
    let open = Utc.with_ymd_and_hms(2026, 11, 2, 14, 30, 0).unwrap();
    let provider = Arc::new(Provider::default());
    let clock = Arc::new(Mutex::new(open + Duration::minutes(10)));
    let service = collector(&pool, &provider, &clock);
    *provider.reply.lock().unwrap() = Some(quote("AAPL", clock_time(&clock), 125.0));
    assert_eq!(service.collect_once().await.unwrap(), 1);
    let opening = market(&pool, date, clock_time(&clock))
        .await
        .opening_session
        .unwrap();
    assert_eq!(opening.tracking.starts_at, open);
    assert_eq!(opening.tracking.ends_at, open + Duration::hours(1));
    assert_eq!(opening.coverage.missing_minute_count, 10);
    *clock.lock().unwrap() = open + Duration::hours(1);
    assert_eq!(service.collect_once().await.unwrap(), 0);
    assert_eq!(provider.calls.lock().unwrap().len(), 1);
    drop(service);
    pool.close().await;
}

#[tokio::test]
async fn missed_opening_is_completed_empty_not_replayed_at_current_prices() {
    let pool = pool().await;
    let open = Utc.with_ymd_and_hms(2026, 11, 3, 14, 30, 0).unwrap();
    let detected = open + Duration::hours(2);
    let date = release(&pool, detected, "pre_market").await;
    let opening = market(&pool, date, detected).await.opening_session.unwrap();
    assert!(opening.coverage.window_ended);
    assert_eq!(opening.tracking.status, "completed");
    assert_eq!(opening.coverage.missing_minute_count, 60);
    assert!(opening.snapshots.is_empty());
}

#[tokio::test]
async fn rollback_and_retention_include_opening_windows_and_samples() {
    let pool = pool().await;
    let time = now();
    let date = time.date_naive();
    observe(
        &pool,
        date,
        &[event("AAPL", None)],
        time - Duration::seconds(65),
    )
    .await;
    let mut tx = pool.begin().await.unwrap();
    observe_calendar(&mut tx, date, &[event("AAPL", Some("1"))], time)
        .await
        .unwrap();
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM earnings_stock_opening")
        .fetch_one(&mut *tx)
        .await
        .unwrap();
    assert_eq!(count, 1);
    tx.rollback().await.unwrap();
    assert!(market(&pool, date, time).await.opening_session.is_none());
    release(&pool, time, "after_hours").await;
    let opening = market(&pool, date, time).await.opening_session.unwrap();
    let open = opening.tracking.starts_at;
    save_window_quote(
        &pool,
        opening.tracking.id,
        true,
        &quote("AAPL", open, 100.0),
        open,
    )
    .await
    .unwrap();
    let provider = Arc::new(Provider::default());
    let clock = Arc::new(Mutex::new(time + Duration::days(121)));
    collector(&pool, &provider, &clock)
        .collect_once()
        .await
        .unwrap();
    for table in ["earnings_stock_opening", "earnings_stock_opening_snapshot"] {
        let count: i64 = sqlx::query_scalar(&format!("SELECT COUNT(*) FROM {table}"))
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(count, 0);
    }
}
