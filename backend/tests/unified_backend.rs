use std::sync::Arc;

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
};
use rust_decimal::Decimal;
use sqlx::sqlite::SqlitePoolOptions;
use tokio::sync::broadcast;
use tower::ServiceExt;

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
}

async fn app() -> TestApp {
    // The tracked template is the test fixture; the developer's own config.toml stays private.
    let mut config =
        AppConfig::from_path("config.example.toml").expect("config.example.toml is present");
    config.auth.enabled = true;
    config.auth.tokens = "alice:secret-token".into();
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
        health,
        llm_usage: None,
        auth,
        quota,
        event_bus,
        fcm: None,
        backfill,
    };
    TestApp { state }
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
async fn missing_shared_ai_returns_not_found_even_without_a_model_key() {
    let app = app().await;
    let response = api::router(app.state.clone())
        .oneshot(request(
            "GET",
            "/api/v1/events/1/ai-analysis?language=zh-CN",
            Some("secret-token"),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn shared_ai_is_readable_and_feedback_is_recorded_once_per_revision() {
    let app = app().await;
    sqlx::query(
        "INSERT INTO ai_analysis (event_id, language, method, timezone, revision, chain_json, data_analysis, market_outlook, model, generated_at) \
         VALUES (1, 'en', 2, 'UTC', 1, '[]', 'data', 'outlook', 'test-model', '2026-09-19T00:00:00+00:00')",
    )
    .execute(app.state.events.pool())
    .await
    .unwrap();
    let router = api::router(app.state.clone());

    let cached = router
        .clone()
        .oneshot(request(
            "GET",
            "/api/v1/events/1/ai-analysis?language=en&method=2",
            Some("secret-token"),
        ))
        .await
        .unwrap();
    assert_eq!(cached.status(), StatusCode::OK);

    // Direct-mode clients have a device-local id, so they read the same cached row by provider
    // identity. The lookup is authenticated because a cache miss can now trigger model work.
    let public_cached = router
        .clone()
        .oneshot(request(
            "GET",
            "/api/v1/ai-analysis/by-provider?provider=trading_view&provider_id=fixture-2&language=en&method=2",
            Some("secret-token"),
        ))
        .await
        .unwrap();
    assert_eq!(public_cached.status(), StatusCode::OK);

    let body = serde_json::json!({"language":"en","method":2,"revision":1,"message":"outlook ignores the dollar move"});
    let first = router
        .clone()
        .oneshot(json_request(
            "/api/v1/events/1/analysis-feedback",
            "secret-token",
            body.clone(),
        ))
        .await
        .unwrap();
    assert_eq!(first.status(), StatusCode::OK);
    let second = router
        .oneshot(json_request(
            "/api/v1/events/1/analysis-feedback",
            "secret-token",
            body,
        ))
        .await
        .unwrap();
    assert_eq!(second.status(), StatusCode::OK);
    let ids: Vec<i64> = sqlx::query_scalar("SELECT id FROM analysis_feedback")
        .fetch_all(app.state.events.pool())
        .await
        .unwrap();
    assert_eq!(ids.len(), 1);

    // The same revision against an unknown analysis is rejected.
    let missing = api::router(app.state.clone())
        .oneshot(json_request(
            "/api/v1/events/1/analysis-feedback",
            "secret-token",
            serde_json::json!({"language":"en","method":1,"revision":9,"message":"x"}),
        ))
        .await
        .unwrap();
    assert_eq!(missing.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn events_are_resolvable_by_provider_identity_and_names_by_cache() {
    let app = app().await;
    let router = api::router(app.state.clone());
    let response = router
        .clone()
        .oneshot(request(
            "GET",
            "/api/v1/events/by-provider?provider=trading_view&provider_id=fixture-2",
            Some("secret-token"),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let missing = router
        .clone()
        .oneshot(request(
            "GET",
            "/api/v1/events/by-provider?provider=trading_view&provider_id=other",
            Some("secret-token"),
        ))
        .await
        .unwrap();
    assert_eq!(missing.status(), StatusCode::NOT_FOUND);

    // Translation-cache reads are public: they neither mutate data nor trigger model work, so a
    // direct-mode client can localize names without receiving a full backend access token.
    let response = router
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/api/v1/translations/names")
                .header("content-type", "application/json")
                .body(Body::from(
                    serde_json::json!(["Nonfarm Payrolls"]).to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
}
