use std::{
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    time::Duration as StdDuration,
};

use async_trait::async_trait;
use chrono::{DateTime, Duration, Utc};
use market_event_analyzer::{
    analysis::{AnalysisService, RuleEngine},
    backfill::{BackfillRange, BackfillService, repository::BackfillRepository},
    calendar::CalendarProvider,
    error::AppError,
    market::{MarketDataProvider, MarketService},
    model::{Candle, EconomicEvent, EventStatus, Interval, MarketSymbol, Quote},
    repository::{AnalysisRepository, EventRepository, MarketRepository},
};
use rust_decimal::Decimal;
use sqlx::sqlite::SqlitePoolOptions;

struct Calendar {
    wrong_date: bool,
    calls: AtomicUsize,
}
#[async_trait]
impl CalendarProvider for Calendar {
    async fn fetch_events(
        &self,
        from: DateTime<Utc>,
        _: DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        let time = from + Duration::hours(if self.wrong_date { 48 } else { 12 });
        Ok((0..2)
            .map(|n| EconomicEvent {
                id: 0,
                provider: "fixture".into(),
                provider_id: format!("{from}-{n}"),
                release_group_id: None,
                country: "United States".into(),
                currency: Some("USD".into()),
                category: "inflation".into(),
                event: format!("Fixture CPI {n}"),
                event_zh_cn: None,
                event_zh_tw: None,
                event_time: time,
                importance: 3,
                actual: Some(Decimal::new(32, 1)),
                previous: Some(Decimal::new(30, 1)),
                consensus: Some(Decimal::new(31, 1)),
                forecast: None,
                unit: Some("%".into()),
                status: EventStatus::Scheduled,
                time_exact: true,
            })
            .collect())
    }
}
struct Market {
    fail: AtomicBool,
    calls: AtomicUsize,
}
#[async_trait]
impl MarketDataProvider for Market {
    async fn quote(&self, _: MarketSymbol) -> Result<Quote, AppError> {
        panic!("historical backfill must never fetch current quotes")
    }
    async fn candles(
        &self,
        symbol: MarketSymbol,
        from: DateTime<Utc>,
        to: DateTime<Utc>,
        interval: Interval,
    ) -> Result<Vec<Candle>, AppError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        if self.fail.load(Ordering::SeqCst) {
            return Err(AppError::Provider("fixture missing data".into()));
        }
        let mut rows = vec![];
        let mut time = from;
        while time < to {
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

async fn setup(
    wrong_date: bool,
    fail: bool,
) -> (
    BackfillService,
    EventRepository,
    AnalysisRepository,
    Arc<Calendar>,
    Arc<Market>,
    sqlx::SqlitePool,
) {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let events = EventRepository::new(pool.clone());
    let market_repo = MarketRepository::new(pool.clone());
    let analyses = AnalysisRepository::new(pool.clone());
    let cal = Arc::new(Calendar {
        wrong_date,
        calls: AtomicUsize::new(0),
    });
    let provider = Arc::new(Market {
        fail: AtomicBool::new(fail),
        calls: AtomicUsize::new(0),
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
    let service = BackfillService::new(
        BackfillRepository::new(pool.clone()),
        cal.clone(),
        events.clone(),
        analyses.clone(),
        market,
        analysis,
        StdDuration::ZERO,
    );
    (service, events, analyses, cal, provider, pool)
}
fn range() -> BackfillRange {
    let date = Utc::now().date_naive() - Duration::days(2);
    BackfillRange {
        from: date,
        to: date,
    }
}

#[tokio::test]
async fn imports_calendar_and_real_horizon_evidence_once_per_day_asset() {
    let (service, events, analyses, cal, market, pool) = setup(false, false).await;
    let first = service.run(range()).await.unwrap();
    assert_eq!(first.status, "complete");
    assert_eq!(first.events, 2);
    assert_eq!(first.analyses, 2);
    assert_eq!(market.calls.load(Ordering::SeqCst), 1); // both events share candles
    let stored = events.history(None, None, 10).await.unwrap();
    for event in stored {
        assert_eq!(event.status, EventStatus::Historical);
        let report = analyses.get(event.id).await.unwrap();
        assert_eq!(report.observed_reactions[0].change_60m, Some(0.0));
        let evidence = report.historical.unwrap();
        assert!(evidence.revised_data_possible);
        assert_eq!(evidence.coverage[0].available_horizons, [1, 5, 15, 30, 60]);
        assert!(evidence.coverage[0].baseline_time.unwrap() < event.event_time);
    }
    assert!(events.market_active().await.unwrap().is_empty());
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM market_snapshot")
            .fetch_one(&pool)
            .await
            .unwrap(),
        0
    );
    let second = service.run(range()).await.unwrap();
    assert_eq!(first.id, second.id);
    assert_eq!(cal.calls.load(Ordering::SeqCst), 1); // completed day checkpoint
    assert_eq!(market.calls.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn missing_markets_are_explicit_and_retry_does_not_duplicate_calendar() {
    let (service, events, analyses, _, market, _) = setup(false, true).await;
    let first = service.run(range()).await.unwrap();
    assert_eq!(first.status, "partial");
    assert_eq!(first.events, 2);
    assert!(!first.day_errors.is_empty());
    for event in events.history(None, None, 10).await.unwrap() {
        let report = analyses.get(event.id).await.unwrap();
        assert!(report.observed_reactions.is_empty());
        assert_eq!(
            report.historical.unwrap().coverage[0].reason.as_deref(),
            Some("provider_error")
        );
        assert!(report.comparisons.iter().all(|c| c.conforms.is_none()));
    }
    market.fail.store(false, Ordering::SeqCst);
    assert_eq!(service.run(range()).await.unwrap().status, "complete");
    assert_eq!(events.history(None, None, 10).await.unwrap().len(), 2);
}

#[tokio::test]
async fn date_ignoring_source_cannot_poison_historical_database() {
    let (service, events, _, _, market, _) = setup(true, false).await;
    let summary = service.run(range()).await.unwrap();
    assert_eq!(summary.status, "partial");
    assert_eq!(summary.days_failed, 1);
    assert!(events.history(None, None, 10).await.unwrap().is_empty());
    assert_eq!(market.calls.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn protects_existing_live_release_values_and_reports() {
    let (service, events, analyses, cal, _, pool) = setup(false, false).await;
    let start = range().from.and_hms_opt(0, 0, 0).unwrap().and_utc();
    let mut original = cal
        .fetch_events(start, start + Duration::days(1))
        .await
        .unwrap()
        .remove(0);
    original.actual = Some(Decimal::new(25, 1));
    let id = events.save_events(&[original]).await.unwrap()[0];
    let live = AnalysisService::new(
        events.clone(),
        MarketRepository::new(pool),
        analyses.clone(),
        RuleEngine::from_path("rules.toml").unwrap(),
    );
    live.analyze(id).await.unwrap();
    events.set_status(id, EventStatus::Completed).await.unwrap();
    service.run(range()).await.unwrap();
    assert_eq!(
        events.get(id).await.unwrap().actual,
        Some(Decimal::new(25, 1))
    );
    assert_eq!(events.get(id).await.unwrap().status, EventStatus::Completed);
    assert!(analyses.get(id).await.unwrap().historical.is_none());
}

#[tokio::test]
async fn live_collectors_keep_ownership_until_their_report_is_finished() {
    let (service, events, _, cal, _, _) = setup(false, false).await;
    let start = range().from.and_hms_opt(0, 0, 0).unwrap().and_utc();
    let original = cal
        .fetch_events(start, start + Duration::days(1))
        .await
        .unwrap()
        .remove(0);
    let id = events.save_events(&[original]).await.unwrap()[0];
    events.set_status(id, EventStatus::Watching).await.unwrap();
    let summary = service.run(range()).await.unwrap();
    assert_eq!(summary.status, "partial");
    assert_eq!(summary.analyses, 1);
    assert_eq!(events.get(id).await.unwrap().status, EventStatus::Watching);
}

#[tokio::test]
async fn public_calendar_refresh_does_not_erase_archived_api_values() {
    let (service, events, _, _, _, _) = setup(false, false).await;
    service.run(range()).await.unwrap();
    let mut event = events.history(None, None, 1).await.unwrap().remove(0);
    let id = event.id;
    event.status = EventStatus::Scheduled;
    event.actual = None;
    event.consensus = None;
    events.save_events(&[event]).await.unwrap();
    let stored = events.get(id).await.unwrap();
    assert_eq!(stored.actual, Some(Decimal::new(32, 1)));
    assert_eq!(stored.consensus, Some(Decimal::new(31, 1)));
    assert_eq!(stored.status, EventStatus::Historical);
}

#[tokio::test]
async fn history_pagination_handles_same_time_releases_and_date_bounds() {
    let (service, events, _, _, _, _) = setup(false, false).await;
    service.run(range()).await.unwrap();
    let first = events
        .history_page(None, None, 1, 0, None, None)
        .await
        .unwrap();
    let second = events
        .history_page(None, None, 1, 1, None, None)
        .await
        .unwrap();
    assert_ne!(first[0].id, second[0].id);
    assert!(
        events
            .history_page(None, None, 1, 2, None, None)
            .await
            .unwrap()
            .is_empty()
    );
    assert!(
        events
            .history_page(None, None, 100, 0, Some(Utc::now()), None)
            .await
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn run_lease_rejects_duplicate_worker_and_can_resume_after_failure() {
    let (service, _, _, _, _, _) = setup(false, false).await;
    let r = range();
    let id = service.repository.begin(r.from, r.to).await.unwrap();
    assert!(service.repository.begin(r.from, r.to).await.is_err());
    service
        .repository
        .finish(id, "failed", Some("interrupted"))
        .await
        .unwrap();
    assert_eq!(service.repository.begin(r.from, r.to).await.unwrap(), id);
}
