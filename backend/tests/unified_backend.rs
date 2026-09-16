use std::sync::{Arc, Once};

use async_trait::async_trait;
use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use chrono::{Duration, Utc};
use market_event_analyzer::{
    AppState,
    ai_analysis::AiAnalysisService,
    alert::{AlertService, HealthRegistry},
    analysis::{AnalysisService, RuleEngine},
    api,
    auth::{AuthState, TokenStore},
    backfill::repository::BackfillRepository,
    calendar::{CalendarProvider, CalendarService},
    config::AppConfig,
    error::AppError,
    market::{MarketDataProvider, MarketService},
    model::{Candle, EconomicEvent, EventStatus, Interval, LiveQuote, MarketSymbol, Quote},
    quota::QuotaService,
    repository::{AnalysisRepository, EventRepository, MarketRepository},
    translation_correction::TranslationCorrectionService,
};
use rust_decimal::Decimal;
use serde_json::json;
use sqlx::sqlite::SqlitePoolOptions;
use tokio::sync::broadcast;
use tower::ServiceExt;

const TEST_TOKENS_ENV: &str = "API_TOKENS_UNIFIED_BACKEND_TEST";

struct StubCalendar(Vec<EconomicEvent>);

#[async_trait]
impl CalendarProvider for StubCalendar {
    async fn fetch_events(
        &self,
        start: chrono::DateTime<Utc>,
        end: chrono::DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        Ok(self
            .0
            .iter()
            .filter(|event| event.event_time >= start && event.event_time < end)
            .cloned()
            .collect())
    }
}

struct StubMarket;

#[async_trait]
impl MarketDataProvider for StubMarket {
    async fn quote(&self, symbol: MarketSymbol) -> Result<Quote, AppError> {
        Ok(Quote {
            symbol,
            timestamp: Utc::now(),
            price: 100.0,
        })
    }

    async fn live_quote(&self, symbol: MarketSymbol) -> Result<LiveQuote, AppError> {
        Ok(LiveQuote {
            symbol,
            timestamp: Utc::now(),
            price: 100.0,
            provider: "stub".into(),
            change_percent: None,
            high: None,
            low: None,
            market_state: None,
            stale: false,
        })
    }

    async fn candles(
        &self,
        _: MarketSymbol,
        _: chrono::DateTime<Utc>,
        _: chrono::DateTime<Utc>,
        _: Interval,
    ) -> Result<Vec<Candle>, AppError> {
        Ok(Vec::new())
    }
}

fn fixture_event(hours_from_now: i64) -> EconomicEvent {
    EconomicEvent {
        id: 0,
        provider: "trading_view".into(),
        provider_id: format!("fixture-{hours_from_now}"),
        release_group_id: None,
        country: "United States".into(),
        currency: Some("USD".into()),
        category: "inflation".into(),
        event: "Core CPI m/m".into(),
        event_zh_cn: None,
        event_zh_tw: None,
        event_time: Utc::now() + Duration::hours(hours_from_now),
        importance: 3,
        actual: None,
        previous: Some(Decimal::new(2, 1)),
        consensus: Some(Decimal::new(2, 1)),
        forecast: Some(Decimal::new(2, 1)),
        unit: Some("%".into()),
        status: EventStatus::Scheduled,
        time_exact: true,
    }
}

struct TestApp {
    state: AppState,
    events: EventRepository,
}

async fn app() -> TestApp {
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        // Safe in tests: the variable is written once, before any reader runs.
        unsafe { std::env::set_var(TEST_TOKENS_ENV, "alice:secret-token") };
    });
    let mut config = AppConfig::from_path("config.toml").expect("config.toml is present");
    config.auth.tokens_env = TEST_TOKENS_ENV.into();
    config.ai.enabled = false;
    config.translation.enabled = false;
    config.telegram.enabled = false;

    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();

    let events = EventRepository::new(pool.clone());
    let market = MarketRepository::new(pool.clone());
    let analyses = AnalysisRepository::new(pool.clone());
    let backfill = BackfillRepository::new(pool.clone());
    let alerts = Arc::new(AlertService::new(
        None,
        Vec::new(),
        pool.clone(),
        config.alerts.clone(),
    ));
    let health = Arc::new(HealthRegistry::new(
        pool.clone(),
        Some(alerts.clone()),
        config.alerts.failure_threshold,
        config.alerts.cooldown_seconds,
    ));
    events
        .save_events(&[fixture_event(2)])
        .await
        .expect("fixture event is stored");
    let calendar_service = Arc::new(
        CalendarService::new(
            Arc::new(StubCalendar(vec![fixture_event(2)])),
            Arc::new(StubCalendar(Vec::new())),
            events.clone(),
        )
        .with_health(health.clone()),
    );
    let market_service = Arc::new(
        MarketService::new(
            Arc::new(StubMarket),
            Arc::new(StubMarket),
            market.clone(),
            vec![MarketSymbol::Gold],
        )
        .with_health(health.clone()),
    );
    let analysis_service = Arc::new(AnalysisService::new(
        events.clone(),
        market.clone(),
        analyses.clone(),
        RuleEngine::from_path("rules.toml").unwrap(),
    ));
    let corrections = Arc::new(TranslationCorrectionService::new(
        pool.clone(),
        events.clone(),
        Some(alerts),
    ));
    let quota = Arc::new(QuotaService::new(pool.clone(), config.limits.clone()));
    let auth = AuthState {
        store: Arc::new(TokenStore::from_config(&config.auth).unwrap()),
        quota: quota.clone(),
    };
    let (event_bus, _) = broadcast::channel(16);
    let state = AppState {
        config: Arc::new(config),
        events: events.clone(),
        market,
        analyses,
        calendar_service,
        market_service,
        analysis_service,
        ai_analysis_service: None::<Arc<AiAnalysisService>>,
        corrections,
        health,
        llm_usage: None,
        auth,
        quota,
        event_bus,
        backfill,
    };
    TestApp { state, events }
}

fn request(method: &str, uri: &str, token: Option<&str>) -> Request<Body> {
    let mut builder = Request::builder().method(method).uri(uri);
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    builder.body(Body::empty()).unwrap()
}

fn json_request(uri: &str, token: &str, body: serde_json::Value) -> Request<Body> {
    Request::builder()
        .method("POST")
        .uri(uri)
        .header("authorization", format!("Bearer {token}"))
        .header("content-type", "application/json")
        .body(Body::from(body.to_string()))
        .unwrap()
}

#[tokio::test]
async fn meta_is_public_while_data_requires_a_token() {
    let app = app().await;
    let router = api::router(app.state.clone());

    let meta = router
        .clone()
        .oneshot(request("GET", "/api/v1/meta", None))
        .await
        .unwrap();
    assert_eq!(meta.status(), StatusCode::OK);

    for candidate in [None, Some("wrong-token")] {
        let response = router
            .clone()
            .oneshot(request("GET", "/api/v1/events/upcoming", candidate))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }

    let authorized = router
        .oneshot(request(
            "GET",
            "/api/v1/events/upcoming?days=1",
            Some("secret-token"),
        ))
        .await
        .unwrap();
    assert_eq!(authorized.status(), StatusCode::OK);
}

#[tokio::test]
async fn history_accepts_a_country_list() {
    let app = app().await;
    let response = api::router(app.state.clone())
        .oneshot(request(
            "GET",
            "/api/v1/events/history?country=United%20States,China&limit=5",
            Some("secret-token"),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}

#[tokio::test]
async fn translation_corrections_require_an_admin_decision() {
    let app = app().await;
    app.events.save_events(&[fixture_event(-3)]).await.unwrap();
    let router = api::router(app.state.clone());

    let submitted = router
        .oneshot(json_request(
            "/api/v1/translations/corrections",
            "secret-token",
            json!({
                "eventName": "Core CPI m/m",
                "zhCn": "核心CPI月率",
                "zhTw": "核心CPI月率"
            }),
        ))
        .await
        .unwrap();
    assert_eq!(submitted.status(), StatusCode::OK);

    // Nothing is applied before the admin decides.
    let rows = app
        .events
        .history_page_multi(vec![], None, 10, 0, None, None)
        .await
        .unwrap();
    assert!(rows.iter().all(|event| event.event_zh_cn.is_none()));

    let pending = app.state.corrections.pending().await.unwrap();
    assert_eq!(pending.len(), 1);
    app.state.corrections.approve(pending[0].id).await.unwrap();

    let rows = app
        .events
        .history_page_multi(vec![], None, 10, 0, None, None)
        .await
        .unwrap();
    assert_eq!(rows[0].event_zh_cn.as_deref(), Some("核心CPI月率"));
}

#[tokio::test]
async fn muted_event_names_stop_accepting_corrections() {
    let app = app().await;
    let first = app
        .state
        .corrections
        .submit("Nonfarm Payrolls", "非农", "非農", Some("alice".into()))
        .await
        .unwrap();
    app.state.corrections.mute(first.id).await.unwrap();
    let second = app
        .state
        .corrections
        .submit(
            "Nonfarm Payrolls",
            "新增非农",
            "新增非農",
            Some("bob".into()),
        )
        .await
        .unwrap();
    assert_eq!(second.status, "muted");
    assert_eq!(second.id, 0);
    // The muted request left the pending queue; nothing new was queued.
    assert!(app.state.corrections.pending().await.unwrap().is_empty());
}

#[tokio::test]
async fn duplicate_corrections_within_a_day_reuse_the_existing_request() {
    let app = app().await;
    let first = app
        .state
        .corrections
        .submit("Retail Sales m/m", "零售销售", "零售銷售", None)
        .await
        .unwrap();
    let second = app
        .state
        .corrections
        .submit("Retail Sales m/m", "零售销售", "零售銷售", None)
        .await
        .unwrap();
    assert_eq!(first.id, second.id);
    assert_eq!(app.state.corrections.pending().await.unwrap().len(), 1);
}

#[tokio::test]
async fn source_failures_escalate_only_after_the_threshold() {
    let app = app().await;
    let health = app.state.health.clone();
    health.record_failure("market.cnbc", "timeout").await;
    health.record_failure("market.cnbc", "timeout").await;
    let snapshot = health.snapshot().await.unwrap();
    let entry = snapshot
        .iter()
        .find(|entry| entry.key == "market.cnbc")
        .unwrap();
    assert_eq!(entry.status, "healthy");
    assert_eq!(entry.consecutive_failures, 2);

    health.record_failure("market.cnbc", "timeout").await;
    let snapshot = health.snapshot().await.unwrap();
    let entry = snapshot
        .iter()
        .find(|entry| entry.key == "market.cnbc")
        .unwrap();
    assert_eq!(entry.status, "down");
    assert!(entry.last_notified_at.is_some());

    // A recovery is announced and resets the counter.
    health.record_success("market.cnbc").await;
    let snapshot = health.snapshot().await.unwrap();
    let entry = snapshot
        .iter()
        .find(|entry| entry.key == "market.cnbc")
        .unwrap();
    assert_eq!(entry.status, "healthy");
    assert_eq!(entry.consecutive_failures, 0);
}

#[tokio::test]
async fn ai_analysis_reports_unavailable_when_the_server_has_no_model_key() {
    let app = app().await;
    let response = api::router(app.state.clone())
        .oneshot(request(
            "GET",
            "/api/v1/events/1/ai-analysis?language=zh-CN",
            Some("secret-token"),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::CONFLICT);
}
