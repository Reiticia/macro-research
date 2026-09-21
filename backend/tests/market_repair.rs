use std::{
    sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    },
    time::Duration as StdDuration,
};

use async_trait::async_trait;
use chrono::{DateTime, Duration, Utc};
use market_event_analyzer::{
    analysis::{AnalysisService, RuleEngine},
    backfill::{BackfillRange, BackfillService, repository::BackfillRepository},
    error::AppError,
    market::{MarketDataProvider, MarketService},
    model::{Candle, EconomicEvent, EventStatus, Interval, MarketSymbol, Quote},
    repository::{AnalysisRepository, EventRepository, MarketRepository},
};
use rust_decimal::Decimal;
use sqlx::sqlite::SqlitePoolOptions;

struct Market {
    calls: AtomicUsize,
    bars: usize,
}

#[async_trait]
impl MarketDataProvider for Market {
    async fn quote(&self, _: MarketSymbol) -> Result<Quote, AppError> {
        panic!("market repair must never fetch current quotes")
    }
    async fn candles(
        &self,
        symbol: MarketSymbol,
        from: DateTime<Utc>,
        to: DateTime<Utc>,
        interval: Interval,
    ) -> Result<Vec<Candle>, AppError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        let mut rows = vec![];
        let mut time = from;
        while rows.len() < self.bars && time < to {
            rows.push(Candle {
                symbol,
                timestamp: time,
                open: 100.0,
                high: 100.0,
                low: 100.0,
                close: 100.0,
                volume: None,
            });
            time += Duration::seconds(interval.seconds());
        }
        Ok(rows)
    }
}

fn event(
    provider_id: &str,
    event_time: DateTime<Utc>,
    actual: Option<Decimal>,
    time_exact: bool,
) -> EconomicEvent {
    EconomicEvent {
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
        event_time,
        importance: 1,
        actual,
        previous: None,
        consensus: None,
        forecast: None,
        unit: Some("%".into()),
        status: EventStatus::Scheduled,
        time_exact,
    }
}

#[tokio::test]
async fn repair_rebuilds_missing_market_evidence_and_keeps_live_reports() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let events = EventRepository::new(pool.clone());
    let market_repo = MarketRepository::new(pool.clone());
    let analyses = AnalysisRepository::new(pool.clone());
    let provider = Arc::new(Market {
        calls: AtomicUsize::new(0),
        bars: usize::MAX,
    });
    let market = Arc::new(MarketService::new(
        provider.clone(),
        provider.clone(),
        market_repo.clone(),
        vec![MarketSymbol::Bitcoin],
    ));
    let analysis = Arc::new(AnalysisService::new(
        events.clone(),
        market_repo.clone(),
        analyses.clone(),
        RuleEngine::from_path("rules.toml").unwrap(),
    ));
    let service = BackfillService::new_local(
        BackfillRepository::new(pool.clone()),
        events.clone(),
        analyses.clone(),
        market,
        analysis.clone(),
        StdDuration::ZERO,
    );

    let day = (Utc::now() - Duration::days(3)).date_naive();
    let base = day.and_hms_opt(8, 0, 0).unwrap().and_utc();
    let actual = Some(Decimal::new(32, 1));

    // a) Missed watch window: scheduled with a published value.
    let stuck = events
        .save_events(&[event("stuck", base, actual, true)])
        .await
        .unwrap()[0];
    // b) Live pipeline finished with an empty shell report (no snapshots ever arrived).
    let shell = events
        .save_events(&[event("shell", base + Duration::hours(2), actual, true)])
        .await
        .unwrap()[0];
    events
        .set_status(shell, EventStatus::Completed)
        .await
        .unwrap();
    let shell_report = analysis.analyze(shell).await.unwrap();
    assert!(shell_report.observed_reactions.is_empty());
    // c) Live report with real reactions: must be preserved.
    let live = events
        .save_events(&[event("live", base + Duration::hours(4), actual, true)])
        .await
        .unwrap()[0];
    for (minutes, price) in [(-1_i64, 100.0), (5_i64, 101.0)] {
        market_repo
            .save_quote(
                live,
                &Quote {
                    symbol: MarketSymbol::Bitcoin,
                    timestamp: base + Duration::hours(4) + Duration::minutes(minutes),
                    price,
                },
            )
            .await
            .unwrap();
    }
    events
        .set_status(live, EventStatus::Completed)
        .await
        .unwrap();
    let live_report = analysis.analyze(live).await.unwrap();
    assert!(!live_report.observed_reactions.is_empty());
    // d) Still in the live pipeline: never rewritten.
    let collecting = events
        .save_events(&[event("collecting", base + Duration::hours(6), actual, true)])
        .await
        .unwrap()[0];
    events
        .set_status(collecting, EventStatus::CollectingMarketData)
        .await
        .unwrap();
    // e) No published value: nothing to analyze.
    let unreleased = events
        .save_events(&[event("unreleased", base + Duration::hours(8), None, true)])
        .await
        .unwrap()[0];
    // f) Approximate release time: no intraday reaction is computed for these.
    let approximate = events
        .save_events(&[event(
            "approximate",
            base + Duration::hours(10),
            actual,
            false,
        )])
        .await
        .unwrap()[0];

    let range = BackfillRange { from: day, to: day };
    let first = service.repair_local(range).await.unwrap();
    assert_eq!(first.status, "complete");
    assert_eq!(first.events, 2);
    assert_eq!(first.analyses, 2);
    assert_eq!(provider.calls.load(Ordering::SeqCst), 1); // one day x one asset

    for id in [stuck, shell] {
        let stored = events.get(id).await.unwrap();
        assert_eq!(stored.status, EventStatus::Historical);
        let report = analyses.get(id).await.unwrap();
        assert!(report.historical.is_some());
        assert_eq!(report.observed_reactions.len(), 1);
        assert_eq!(report.observed_reactions[0].change_60m, Some(0.0));
    }
    // The empty shell report was replaced by real historical evidence.
    assert_ne!(
        analyses.get(shell).await.unwrap().updated_at,
        shell_report.updated_at
    );

    let live_after = events.get(live).await.unwrap();
    assert_eq!(live_after.status, EventStatus::Completed);
    let live_report_after = analyses.get(live).await.unwrap();
    assert!(live_report_after.historical.is_none());
    assert_eq!(
        live_report_after.observed_reactions.len(),
        live_report.observed_reactions.len()
    );

    assert_eq!(
        events.get(collecting).await.unwrap().status,
        EventStatus::CollectingMarketData
    );
    assert!(analyses.get(collecting).await.is_err());
    assert_eq!(
        events.get(unreleased).await.unwrap().status,
        EventStatus::Scheduled
    );
    assert_eq!(
        events.get(approximate).await.unwrap().status,
        EventStatus::Scheduled
    );

    // A rerun resumes the same run and performs no additional fetches.
    let second = service.repair_local(range).await.unwrap();
    assert_eq!(first.id, second.id);
    assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn empty_candle_windows_are_never_cached_as_complete() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let events = EventRepository::new(pool.clone());
    let market_repo = MarketRepository::new(pool.clone());
    let analyses = AnalysisRepository::new(pool.clone());
    let provider = Arc::new(Market {
        calls: AtomicUsize::new(0),
        bars: 0,
    });
    let market = Arc::new(MarketService::new(
        provider.clone(),
        provider.clone(),
        market_repo.clone(),
        vec![MarketSymbol::Bitcoin],
    ));
    let analysis = Arc::new(AnalysisService::new(
        events.clone(),
        market_repo,
        analyses.clone(),
        RuleEngine::from_path("rules.toml").unwrap(),
    ));
    let service = BackfillService::new_local(
        BackfillRepository::new(pool.clone()),
        events.clone(),
        analyses.clone(),
        market,
        analysis,
        StdDuration::ZERO,
    );

    let day = (Utc::now() - Duration::days(3)).date_naive();
    let base = day.and_hms_opt(8, 0, 0).unwrap().and_utc();
    events
        .save_events(&[event("stuck", base, Some(Decimal::new(32, 1)), true)])
        .await
        .unwrap();
    let range = BackfillRange { from: day, to: day };

    let first = service.repair_local(range).await.unwrap();
    // The window is explicitly reported as missing, not silently treated as complete.
    assert_eq!(first.status, "partial");
    assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
    let ledger_rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM historical_fetch")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(
        ledger_rows, 0,
        "empty windows must not poison the fetch ledger"
    );

    // A rerun re-requests the window instead of trusting a cached empty payload.
    let second = service.repair_local(range).await.unwrap();
    assert_eq!(first.id, second.id);
    assert_eq!(provider.calls.load(Ordering::SeqCst), 2);
}
