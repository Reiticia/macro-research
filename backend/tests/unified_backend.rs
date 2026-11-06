use std::sync::Arc;

use async_trait::async_trait;
use axum::{
    body::Body,
    http::{Request, StatusCode},
};
use chrono::{Duration, Utc};
use market_event_analyzer::{
    AppState,
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

#[path = "support/logo_cache.rs"]
mod logo_cache;

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
    pool: sqlx::SqlitePool,
}

async fn app() -> TestApp {
    // The tracked template is the test fixture; the developer's own config.toml stays private.
    let mut config =
        AppConfig::from_path("config.example.toml").expect("config.example.toml is present");
    config.auth.enabled = true;
    config.translation.enabled = false;
    config.telegram.enabled = false;

    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    // Test-only credential, persisted as a digest exactly like a Telegram-issued key.
    sqlx::query("INSERT INTO api_key_request (id, requester_id, message_id, requested_kind, state) VALUES (1, 42, 1, 'general', 'approved')")
        .execute(&pool).await.unwrap();
    sqlx::query("INSERT INTO api_key (request_id, owner_id, kind, token_hash, token_prefix, issued_by, status) VALUES (1, 42, 'general', ?, 'test', 42, 'active')")
        .bind(market_event_analyzer::access_keys::hash("secret-token"))
        .execute(&pool).await.unwrap();

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
        store: Arc::new(TokenStore::new(config.auth.enabled, pool.clone())),
        quota: quota.clone(),
    };
    let (event_bus, _) = broadcast::channel(16);
    let state = AppState {
        config: Arc::new(config),
        events: events.clone(),
        market,
        analyses,
        calendar_service,
        news_service: Arc::new(market_event_analyzer::news::NewsService::new(
            market_event_analyzer::news::http_client(None).unwrap(),
        )),
        earnings_service: Arc::new(market_event_analyzer::earnings::EarningsService::new(
            pool.clone(),
            reqwest::Client::new(),
            reqwest::Client::new(),
        )),
        market_service,
        analysis_service,
        health,
        telegram: None,
        llm_usage: None,
        auth,
        quota,
        event_bus,
        fcm: None,
        market_selector: None,
        backfill,
    };
    TestApp { state, pool }
}

#[tokio::test]
async fn news_api_authenticates_and_reads_only_the_scheduled_cache() {
    use market_event_analyzer::news::{NewsService, NewsSource, http_client};
    use std::sync::atomic::{AtomicUsize, Ordering};
    let mut app = app().await;
    let calls = Arc::new(AtomicUsize::new(0));
    let counter = calls.clone();
    let raw = include_str!("fixtures/news/rss.xml").replace(
        "Wed, 04 Nov 2026 08:45:00 GMT",
        &(Utc::now() - chrono::Duration::minutes(1)).to_rfc2822(),
    );
    let upstream = axum::Router::new().route(
        "/rss",
        axum::routing::get(move |headers: axum::http::HeaderMap| {
            let raw = raw.clone();
            let counter = counter.clone();
            async move {
                assert!(!headers.contains_key("authorization"));
                assert!(!headers.contains_key("x-installation-id"));
                counter.fetch_add(1, Ordering::SeqCst);
                raw
            }
        }),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/rss", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, upstream).await.unwrap() });
    let service = Arc::new(NewsService::with_sources(
        http_client(None).unwrap(),
        vec![NewsSource {
            id: "bbc".into(),
            name: "BBC Business".into(),
            url,
        }],
    ));
    app.state.news_service = service.clone();
    let router = api::router(app.state);
    assert_eq!(
        router
            .clone()
            .oneshot(request("GET", "/api/v1/news", None))
            .await
            .unwrap()
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        router
            .clone()
            .oneshot(request("GET", "/api/v1/news", Some("secret-token")))
            .await
            .unwrap()
            .status(),
        StatusCode::SERVICE_UNAVAILABLE
    );
    assert_eq!(calls.load(Ordering::SeqCst), 0);
    service.refresh().await;
    for _ in 0..2 {
        let response = router
            .clone()
            .oneshot(request("GET", "/api/v1/news", Some("secret-token")))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()["cache-control"], "no-store");
        let body = axum::body::to_bytes(response.into_body(), 1024 * 1024)
            .await
            .unwrap();
        let value: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(value["sources"][0]["status"], "fresh");
        assert_eq!(value["articles"][0]["title"], "Markets & inflation outlook");
        assert_eq!(value["articles"][0]["feedId"], "bbc");
        assert!(value["articles"][0]["publishedAt"].is_string());
        assert!(value["articles"][0].get("body").is_none());
    }
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    task.abort();
}

#[tokio::test]
async fn archived_news_routes_preserve_original_links_and_never_fetch_on_read() {
    use market_event_analyzer::{
        news::{NewsArticle, NewsService, NewsSource, http_client},
        news_archive::{NewsArchive, article_id},
        news_page::{PageReader, PageText},
    };
    let mut app = app().await;
    let now = Utc::now();
    let source = NewsSource {
        id: "fed".into(),
        name: "Federal Reserve".into(),
        url: "http://127.0.0.1:9/never-fetch".into(),
    };
    let archive = NewsArchive::new(app.pool.clone());
    let url = "https://www.federalreserve.gov/newsevents/original.htm";
    let id = article_id(url);
    archive.record_source(&source, true, now).await.unwrap();
    archive
        .ingest(
            &[NewsArticle {
                title_translations: Default::default(),
                archive_id: id.clone(),
                title: "Policy release".into(),
                url: url.into(),
                source: source.name.clone(),
                feed_id: source.id.clone(),
                published_at: now - chrono::Duration::days(5),
                summary: Some("Public summary".into()),
            }],
            now,
        )
        .await
        .unwrap();
    archive
        .save_body(
            &id,
            &PageText {
                url: "https://www.federalreserve.gov/newsevents/resolved.htm".into(),
                text: "Archived public text. ".repeat(20),
                truncated: false,
            },
            now,
        )
        .await
        .unwrap();
    app.state.news_service = Arc::new(
        NewsService::with_sources(http_client(None).unwrap(), vec![source])
            .with_archive(app.pool.clone(), PageReader::new(None).unwrap()),
    );
    sqlx::query("UPDATE news_articles SET title_translations=?,title_translation_status='available' WHERE id=?")
        .bind(serde_json::json!({"en":"Policy release","zh-CN":"政策公布","zh-TW":"政策公布"}).to_string())
        .bind(&id).execute(&app.pool).await.unwrap();
    let router = api::router(app.state);
    let path = format!("/api/v1/news/articles/{id}");
    assert_eq!(
        router
            .clone()
            .oneshot(request("GET", &path, None))
            .await
            .unwrap()
            .status(),
        StatusCode::UNAUTHORIZED
    );
    for _ in 0..2 {
        let response = router
            .clone()
            .oneshot(request("GET", &path, Some("secret-token")))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()["cache-control"], "no-store");
        let bytes = axum::body::to_bytes(response.into_body(), 1024 * 1024)
            .await
            .unwrap();
        let body: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(body["url"], url);
        assert_eq!(body["archiveId"], id);
        assert_eq!(body["bodyStatus"], "available");
        assert_eq!(body["titleTranslations"]["zh-CN"], "政策公布");
        assert_eq!(body["title"], "Policy release");
        assert!(
            body["text"]
                .as_str()
                .unwrap()
                .contains("Archived public text")
        );
        assert!(body["fetchedAt"].is_string());
    }
    let response = router
        .clone()
        .oneshot(request("GET", "/api/v1/news", Some("secret-token")))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let bytes = axum::body::to_bytes(response.into_body(), 1024 * 1024)
        .await
        .unwrap();
    let body: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(body["articles"][0]["url"], url);
    assert_eq!(body["articles"][0]["archiveId"], id);
    assert_eq!(
        body["articles"][0]["titleTranslations"]["en"],
        "Policy release"
    );
    assert_eq!(
        body["articles"][0]["titleTranslations"]["zh-TW"],
        "政策公布"
    );
    assert_eq!(body["articles"][0]["title"], "Policy release");
    assert_eq!(body["stale"], true);
    for path in [
        "/api/v1/news?offset=bad",
        "/api/v1/news?offset=-1",
        "/api/v1/news/articles/invalid",
    ] {
        let response = router
            .clone()
            .oneshot(request("GET", path, Some("secret-token")))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        let bytes = axum::body::to_bytes(response.into_body(), 4096)
            .await
            .unwrap();
        assert_eq!(
            serde_json::from_slice::<serde_json::Value>(&bytes).unwrap()["error"]["code"],
            "invalid_request"
        );
    }
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT SUM(body_attempts) FROM news_articles")
            .fetch_one(&app.pool)
            .await
            .unwrap(),
        0
    );
    sqlx::query("UPDATE news_articles SET published_at=? WHERE id=?")
        .bind(now.timestamp() - 31 * 86400)
        .bind(&id)
        .execute(&app.pool)
        .await
        .unwrap();
    assert_eq!(
        router
            .oneshot(request("GET", &path, Some("secret-token")))
            .await
            .unwrap()
            .status(),
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn renamed_public_provider_ids_keep_authenticated_by_provider_lookup_working() {
    let app = app().await;
    let mut old = fixture_event(0);
    old.provider = "biquote".into();
    old.provider_id = "old-occurrence".into();
    old.event = "Old English Label".into();
    old.event_zh_cn = Some("消费者信心指数".into());
    let id = app.state.events.save_events(&[old.clone()]).await.unwrap()[0];
    let mut new = old.clone();
    new.provider = "trading_view".into();
    new.provider_id = "new-occurrence".into();
    new.event = "New English Label".into();
    assert_eq!(
        app.state.events.save_events(&[new]).await.unwrap(),
        vec![id]
    );
    let router = api::router(app.state);
    for (provider, provider_id) in [
        ("biquote", "old-occurrence"),
        ("trading_view", "new-occurrence"),
    ] {
        let path =
            format!("/api/v1/events/by-provider?provider={provider}&provider_id={provider_id}");
        assert_eq!(
            router
                .clone()
                .oneshot(request("GET", &path, None))
                .await
                .unwrap()
                .status(),
            StatusCode::UNAUTHORIZED
        );
        let response = router
            .clone()
            .oneshot(request("GET", &path, Some("secret-token")))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = axum::body::to_bytes(response.into_body(), 65536)
            .await
            .unwrap();
        let value: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!(value["id"], id);
        assert_eq!(value["provider"], "trading_view");
        assert_eq!(value["providerId"], "new-occurrence");
    }
}

#[tokio::test]
async fn watcher_uses_cached_chinese_identity_without_models_and_preserves_the_watched_id() {
    let mut app = app().await;
    let mut old = fixture_event(0);
    old.event_time = Utc::now() - Duration::minutes(2);
    old.provider_id = "watched-old".into();
    old.event = "Original English Label".into();
    old.event_zh_cn = Some("消费者信心指数".into());
    let id = app.state.events.save_events(&[old.clone()]).await.unwrap()[0];
    app.state
        .events
        .set_status(id, EventStatus::Watching)
        .await
        .unwrap();
    app.state
        .events
        .save_event_name_translations(&[(
            "Different English Label".into(),
            "消费者信心指数".into(),
            "消費者信心指數".into(),
        )])
        .await
        .unwrap();
    let mut remote = old;
    remote.provider = "biquote".into();
    remote.provider_id = "remote-new".into();
    remote.event = "Different English Label".into();
    remote.event_zh_cn = None;
    remote.actual = Some(Decimal::new(75, 0));
    remote.status = EventStatus::Released;
    app.state.calendar_service = Arc::new(CalendarService::new(
        Arc::new(StubCalendar(vec![remote])),
        Arc::new(StubCalendar(Vec::new())),
        app.state.events.clone(),
    ));
    let mut receiver = app.state.event_bus.subscribe();
    let task = tokio::spawn(market_event_analyzer::scheduler::event_watch_loop(
        app.state.clone(),
        app.state.config.calendar.clone(),
        app.state.config.scheduler.clone(),
    ));
    let published = tokio::time::timeout(std::time::Duration::from_secs(3), receiver.recv())
        .await
        .unwrap()
        .unwrap();
    task.abort();
    assert!(
        matches!(published,market_event_analyzer::model::AppEvent::EconomicEventReleased { event_id,.. } if event_id==id)
    );
    let stored = app.state.events.get(id).await.unwrap();
    assert_eq!(stored.actual, Some(Decimal::new(75, 0)));
    assert_eq!(stored.provider, "trading_view");
    assert_eq!(app.state.events.observations(id).await.unwrap().len(), 1);
    assert_eq!(
        app.state
            .events
            .find_provider_event("biquote", "remote-new")
            .await
            .unwrap()
            .unwrap()
            .id,
        id
    );
}

#[tokio::test]
async fn watcher_never_releases_a_legacy_same_name_row_without_its_own_persisted_actual() {
    let mut app = app().await;
    let mut old = fixture_event(0);
    old.event_time = Utc::now() - Duration::minutes(2);
    old.provider_id = "legacy-one".into();
    let id = app.state.events.save_events(&[old.clone()]).await.unwrap()[0];
    app.state
        .events
        .set_status(id, EventStatus::Watching)
        .await
        .unwrap();
    let legacy:i64=sqlx::query_scalar("INSERT INTO economic_event(provider,provider_id,country,currency,category,event,event_time,importance,status,time_exact)
        SELECT provider,'legacy-two',country,currency,category,event,event_time,importance,status,time_exact FROM economic_event WHERE id=? RETURNING id")
        .bind(id).fetch_one(&app.pool).await.unwrap();
    let mut remote = old;
    remote.actual = Some(Decimal::new(3, 1));
    remote.status = EventStatus::Released;
    app.state.calendar_service = Arc::new(CalendarService::new(
        Arc::new(StubCalendar(vec![remote])),
        Arc::new(StubCalendar(Vec::new())),
        app.state.events.clone(),
    ));
    let mut receiver = app.state.event_bus.subscribe();
    let task = tokio::spawn(market_event_analyzer::scheduler::event_watch_loop(
        app.state.clone(),
        app.state.config.calendar.clone(),
        app.state.config.scheduler.clone(),
    ));
    tokio::time::timeout(std::time::Duration::from_secs(3), receiver.recv())
        .await
        .unwrap()
        .unwrap();
    let extra = tokio::time::timeout(std::time::Duration::from_millis(200), receiver.recv()).await;
    task.abort();
    assert!(extra.is_err());
    assert_eq!(
        app.state.events.get(id).await.unwrap().status,
        EventStatus::Released
    );
    let untouched = app.state.events.get(legacy).await.unwrap();
    assert_eq!(untouched.status, EventStatus::Watching);
    assert!(untouched.actual.is_none());
}

#[tokio::test]
async fn watcher_does_not_publish_when_duplicate_upstream_actual_values_disagree() {
    let mut app = app().await;
    let mut old = fixture_event(0);
    old.event_time = Utc::now() - Duration::minutes(2);
    old.provider_id = "conflict-one".into();
    let id = app.state.events.save_events(&[old.clone()]).await.unwrap()[0];
    app.state
        .events
        .set_status(id, EventStatus::Watching)
        .await
        .unwrap();
    let mut first = old.clone();
    first.actual = Some(Decimal::new(3, 1));
    first.status = EventStatus::Released;
    let mut second = first.clone();
    second.provider_id = "conflict-two".into();
    second.actual = Some(Decimal::new(4, 1));
    app.state.calendar_service = Arc::new(CalendarService::new(
        Arc::new(StubCalendar(vec![first, second])),
        Arc::new(StubCalendar(Vec::new())),
        app.state.events.clone(),
    ));
    let mut receiver = app.state.event_bus.subscribe();
    let task = tokio::spawn(market_event_analyzer::scheduler::event_watch_loop(
        app.state.clone(),
        app.state.config.calendar.clone(),
        app.state.config.scheduler.clone(),
    ));
    let published =
        tokio::time::timeout(std::time::Duration::from_millis(200), receiver.recv()).await;
    task.abort();
    assert!(published.is_err());
    let stored = app.state.events.get(id).await.unwrap();
    assert!(stored.actual.is_none());
    assert_eq!(stored.status, EventStatus::Watching);
}

#[tokio::test]
async fn company_logo_route_authenticates_and_returns_only_bounded_png_assets() {
    let cache_dir = logo_cache::CacheDir::new();
    let mut app = app().await;
    let png = include_bytes!("../../android/app/src/test/resources/earnings/logo.png").to_vec();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}/asset", listener.local_addr().unwrap());
    let assets = axum::Router::new().route(
        "/asset/{symbol}",
        axum::routing::get(move || {
            let png = png.clone();
            async move { ([("content-type", "image/png")], png) }
        }),
    );
    let task = tokio::spawn(async move { axum::serve(listener, assets).await.unwrap() });
    let http = reqwest::Client::builder().no_proxy().build().unwrap();
    app.state.earnings_service = Arc::new(
        market_event_analyzer::earnings::EarningsService::new(
            app.pool.clone(),
            http.clone(),
            http.clone(),
        )
        .with_logos(
            market_event_analyzer::earnings_logo::CompanyLogoService::new(http)
                .with_base(base)
                .with_cache_dir(cache_dir.path()),
        ),
    );
    let router = api::router(app.state);
    let denied = router
        .clone()
        .oneshot(request("GET", "/api/v1/earnings/logo/AAPL", None))
        .await
        .unwrap();
    assert_eq!(denied.status(), StatusCode::UNAUTHORIZED);
    let response = router
        .clone()
        .oneshot(request(
            "GET",
            "/api/v1/earnings/logo/AAPL",
            Some("secret-token"),
        ))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["content-type"], "image/png");
    assert_eq!(response.headers()["x-content-type-options"], "nosniff");
    assert_eq!(response.headers()["cache-control"], "no-store");
    assert_eq!(
        axum::body::to_bytes(response.into_body(), 256 * 1024)
            .await
            .unwrap()
            .as_ref(),
        include_bytes!("../../android/app/src/test/resources/earnings/logo.png")
    );
    let invalid = router
        .oneshot(request(
            "GET",
            "/api/v1/earnings/logo/AAPL%3Fbad",
            Some("secret-token"),
        ))
        .await
        .unwrap();
    assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);
    task.abort();
}

#[tokio::test]
async fn earnings_current_price_is_authenticated_cached_and_never_creates_sampling_rows() {
    use market_event_analyzer::market::{StockQuote, StockQuoteProvider, StockTimestampBasis};
    use std::sync::atomic::{AtomicUsize, Ordering};
    struct Quotes(AtomicUsize);
    #[async_trait::async_trait]
    impl StockQuoteProvider for Quotes {
        async fn stock_quote(
            &self,
            symbol: &str,
        ) -> Result<StockQuote, market_event_analyzer::error::AppError> {
            self.0.fetch_add(1, Ordering::SeqCst);
            Ok(StockQuote {
                symbol: symbol.into(),
                timestamp: Utc::now(),
                price: 123.45,
                currency: Some("USD".into()),
                timestamp_basis: StockTimestampBasis::ProviderQuote,
            })
        }
    }
    let mut app = app().await;
    let quotes = Arc::new(Quotes(AtomicUsize::new(0)));
    let http = reqwest::Client::new();
    app.state.earnings_service = Arc::new(
        market_event_analyzer::earnings::EarningsService::new(app.pool.clone(), http.clone(), http)
            .with_live_quotes(quotes.clone()),
    );
    let router = api::router(app.state);
    let uri = "/api/v1/earnings/quote/AAPL";
    let denied = router
        .clone()
        .oneshot(request("GET", uri, None))
        .await
        .unwrap();
    assert_eq!(denied.status(), StatusCode::UNAUTHORIZED);
    assert_eq!(quotes.0.load(Ordering::SeqCst), 0);
    for _ in 0..2 {
        let response = router
            .clone()
            .oneshot(request("GET", uri, Some("secret-token")))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()["cache-control"], "no-store");
        let bytes = axum::body::to_bytes(response.into_body(), 10_000)
            .await
            .unwrap();
        let root: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(root["symbol"], "AAPL");
        assert_eq!(root["price"], "123.45");
        assert_eq!(root["stale"], false);
        assert!(root.get("fetchedAt").is_some());
    }
    assert_eq!(quotes.0.load(Ordering::SeqCst), 1);
    let invalid = router
        .oneshot(request(
            "GET",
            "/api/v1/earnings/quote/AAPL%3Fbad",
            Some("secret-token"),
        ))
        .await
        .unwrap();
    assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM earnings_stock_tracking")
        .fetch_one(&app.pool)
        .await
        .unwrap();
    assert_eq!(rows, 0);
}

#[tokio::test]
async fn earnings_route_is_authenticated_and_has_a_separate_wire_contract() {
    let app = app().await;
    let router = api::router(app.state);
    let today = Utc::now()
        .with_timezone(&chrono_tz::America::New_York)
        .date_naive();
    let payload = serde_json::json!({
        "date": today, "timezone":"America/New_York", "updatedAt":Utc::now(),
        "nasdaqUpdatedAt":Utc::now(), "tradingViewUpdatedAt":null,
        "stale":false, "warnings":[], "events":[{
            "symbol":"AAPL", "name":"Apple", "source":"nasdaq", "session":"after_hours",
            "fiscalQuarterEnding":"Sep/2026", "epsForecast":"1.98", "analystCount":7,
            "marketCap":null, "actualEps":null, "surprisePercent":null, "lastYearEps":null, "tradingView":null
        }]
    });
    sqlx::query("INSERT INTO earnings_calendar(date,payload) VALUES (?,?)")
        .bind(today.to_string())
        .bind(payload.to_string())
        .execute(&app.pool)
        .await
        .unwrap();
    let uri = format!("/api/v1/earnings?date={today}");
    let denied = router
        .clone()
        .oneshot(request("GET", &uri, None))
        .await
        .unwrap();
    assert_eq!(denied.status(), StatusCode::UNAUTHORIZED);
    let response = router
        .clone()
        .oneshot(request("GET", &uri, Some("secret-token")))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let bytes = axum::body::to_bytes(response.into_body(), 1_000_000)
        .await
        .unwrap();
    let root: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
    assert_eq!(root["events"][0]["epsForecast"], "1.98");
    assert!(root["events"][0].get("eventTime").is_none());
    let invalid = router
        .oneshot(request(
            "GET",
            "/api/v1/earnings?date=2000-01-01",
            Some("secret-token"),
        ))
        .await
        .unwrap();
    assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn retired_earnings_timelines_are_not_advertised_or_served() {
    let app = app().await;
    let router = api::router(app.state);
    let response = router
        .clone()
        .oneshot(request("GET", "/api/v1/meta", None))
        .await
        .unwrap();
    let body = axum::body::to_bytes(response.into_body(), 16384)
        .await
        .unwrap();
    let meta: serde_json::Value = serde_json::from_slice(&body).unwrap();
    let capabilities = meta["capabilities"].as_array().unwrap();
    assert!(!capabilities.contains(&serde_json::json!("earningsMarket")));
    for capability in ["earnings", "earningsLogos", "earningsQuote", "market"] {
        assert!(capabilities.contains(&serde_json::json!(capability)));
    }
    for token in [None, Some("secret-token")] {
        let response = router
            .clone()
            .oneshot(request(
                "GET",
                "/api/v1/earnings/market/2026-11-03/AAPL",
                token,
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }
    for table in ["earnings_stock_tracking", "earnings_stock_opening"] {
        let count: i64 = sqlx::query_scalar(&format!("SELECT COUNT(*) FROM {table}"))
            .fetch_one(&app.pool)
            .await
            .unwrap();
        assert_eq!(count, 0);
    }
}

fn request(method: &str, uri: &str, token: Option<&str>) -> Request<Body> {
    let mut builder = Request::builder().method(method).uri(uri);
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    builder.body(Body::empty()).unwrap()
}

#[tokio::test]
async fn release_timeout_does_not_end_market_collection_or_delete_evidence() {
    let app = app().await;
    let now = Utc::now();
    let mut pending = fixture_event(0);
    pending.provider_id = "timed-out-still-collecting".into();
    pending.event_time = now - Duration::minutes(40);
    pending.status = EventStatus::Timeout;
    let mut finished = pending.clone();
    finished.provider_id = "timed-out-window-finished".into();
    finished.event_time = now - Duration::minutes(70);
    let ids = app
        .state
        .events
        .save_events(&[pending.clone(), finished.clone()])
        .await
        .unwrap();
    let (pending_id, finished_id) = (ids[0], ids[1]);
    for (id, event) in [(pending_id, &pending), (finished_id, &finished)] {
        app.state
            .market
            .save_quote(
                id,
                &Quote {
                    symbol: MarketSymbol::Gold,
                    timestamp: event.event_time - Duration::minutes(1),
                    price: 99.0,
                },
            )
            .await
            .unwrap();
    }
    app.state
        .market
        .save_quote(
            finished_id,
            &Quote {
                symbol: MarketSymbol::Gold,
                timestamp: finished.event_time + Duration::minutes(60),
                price: 100.0,
            },
        )
        .await
        .unwrap();
    let mut notifications = app.state.event_bus.subscribe();
    let collector = tokio::spawn(market_event_analyzer::scheduler::market_collect_loop(
        app.state.clone(),
        app.state.config.scheduler.clone(),
    ));
    // The first iteration handles both fixtures without contacting external providers/models.
    let result = tokio::time::timeout(std::time::Duration::from_secs(10), async {
        let mut collected = false;
        let mut analyzed = false;
        while !collected || !analyzed {
            match notifications.recv().await.unwrap() {
                market_event_analyzer::model::AppEvent::MarketDataCollected { event_id }
                    if event_id == pending_id =>
                {
                    collected = true
                }
                market_event_analyzer::model::AppEvent::AnalysisCompleted { event_id }
                    if event_id == finished_id =>
                {
                    analyzed = true
                }
                _ => {}
            }
        }
    })
    .await;
    collector.abort();
    result.expect("timed-out events must still be collected and finalized");

    assert_eq!(
        app.state.market.snapshots(pending_id).await.unwrap().len(),
        2
    );
    assert_eq!(
        app.state.events.get(pending_id).await.unwrap().status,
        EventStatus::Timeout
    );
    let finished_event = app.state.events.get(finished_id).await.unwrap();
    assert_eq!(finished_event.status, EventStatus::Completed);
    assert!(finished_event.actual.is_none());
    let report = app.state.analyses.get(finished_id).await.unwrap();
    assert!(report.raw_surprise.is_none());
    assert_eq!(report.observed_reactions.len(), 1);
    assert!(report.observed_reactions[0].change_60m.is_some());
    assert_eq!(
        app.state.market.snapshots(finished_id).await.unwrap().len(),
        2
    );
    let active = app.state.events.market_active().await.unwrap();
    assert!(active.iter().any(|event| event.id == pending_id));
    assert!(active.iter().all(|event| event.id != finished_id));
}

#[tokio::test]
async fn access_key_bot_discovery_is_public_and_optional() {
    let app = app().await;
    let response = api::router(app.state)
        .oneshot(request("GET", "/api/v1/access-key-bot", None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    let body = axum::body::to_bytes(response.into_body(), 8192)
        .await
        .unwrap();
    let body: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(body, serde_json::json!({"telegramBotUrl": null}));
}

#[tokio::test]
async fn access_key_bot_returns_configured_bots_public_link_without_authentication() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let telegram = axum::Router::new().route("/botfake-token/getMe", axum::routing::post(|| async {
        axum::Json(serde_json::json!({"ok": true, "result": {"is_bot": true, "username": "example_bot"}}))
    }));
    let task = tokio::spawn(async move { axum::serve(listener, telegram).await.unwrap() });
    let mut app = app().await;
    app.state.telegram = Some(Arc::new(market_event_analyzer::alert::TelegramClient::new(
        reqwest::Client::new(),
        base,
        "fake-token",
    )));
    let response = api::router(app.state)
        .oneshot(request("GET", "/api/v1/access-key-bot", None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    let body = axum::body::to_bytes(response.into_body(), 8192)
        .await
        .unwrap();
    let body: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(
        body,
        serde_json::json!({"telegramBotUrl": "https://t.me/example_bot"})
    );
    task.abort();
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
    assert_eq!(meta.headers()["cache-control"], "no-store");
    let body = axum::body::to_bytes(meta.into_body(), 8192).await.unwrap();
    let meta: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(meta["apiVersion"], 1);
    assert!(meta.get("version").is_none());

    for candidate in [None, Some("wrong-token")] {
        let response = router
            .clone()
            .oneshot(request("GET", "/api/v1/events/upcoming", candidate))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(response.headers()["cache-control"], "no-store");
        let status = router
            .clone()
            .oneshot(request("GET", "/api/v1/status", candidate))
            .await
            .unwrap();
        assert_eq!(status.status(), StatusCode::UNAUTHORIZED);
        assert_eq!(status.headers()["cache-control"], "no-store");
    }

    let verified = router
        .clone()
        .oneshot(request("GET", "/api/v1/status", Some("secret-token")))
        .await
        .unwrap();
    assert_eq!(verified.status(), StatusCode::OK);
    assert_eq!(verified.headers()["cache-control"], "no-store");
    let authorized = router
        .oneshot(request(
            "GET",
            "/api/v1/events/upcoming?days=1",
            Some("secret-token"),
        ))
        .await
        .unwrap();
    assert_eq!(authorized.status(), StatusCode::OK);
    assert_eq!(authorized.headers()["cache-control"], "no-store");
}

#[tokio::test]
async fn explicitly_disabled_auth_exposes_status_so_clients_must_not_claim_key_validation() {
    let mut app = app().await;
    app.state.auth.store = Arc::new(TokenStore::new(false, app.state.events.pool().clone()));
    let response = api::router(app.state)
        .oneshot(request("GET", "/api/v1/status", None))
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
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
async fn history_filters_exact_importance_before_pagination() {
    let app = app().await;
    let mut events = Vec::new();
    for (hours, importance) in [
        (-1, 1),
        (-2, 3),
        (-3, 2),
        (-4, 3),
        (-5, 3),
        (-6, 3),
        (-7, 0),
    ] {
        let mut event = fixture_event(hours);
        event.importance = importance;
        if hours == -5 {
            event.category = "employment".into();
        }
        if hours == -6 {
            event.country = "China".into();
        }
        events.push(event);
    }
    app.state.events.save_events(&events).await.unwrap();
    let router = api::router(app.state.clone());
    let base = "/api/v1/events/history?country=United%20States&category=inflation";
    for (suffix, expected) in [
        ("&importance=3&limit=1&offset=0", vec!["fixture--2"]),
        ("&importance=3&limit=1&offset=1", vec!["fixture--4"]),
        ("&importance=3&limit=1&offset=2", vec![]),
        ("&importance=2", vec!["fixture--3"]),
        ("&importance=1", vec!["fixture--1"]),
        ("&importance=0", vec!["fixture--7"]),
        ("&importance=1,3&limit=2", vec!["fixture--1", "fixture--2"]),
        ("&importance=3,1&limit=2&offset=2", vec!["fixture--4"]),
        ("&importance=1,3&limit=2&offset=3", vec![]),
        ("&importance=3,3", vec!["fixture--2", "fixture--4"]),
        (
            "&importance=1,2,3",
            vec!["fixture--1", "fixture--2", "fixture--3", "fixture--4"],
        ),
        (
            "",
            vec![
                "fixture--1",
                "fixture--2",
                "fixture--3",
                "fixture--4",
                "fixture--7",
            ],
        ),
    ] {
        let response = router
            .clone()
            .oneshot(request(
                "GET",
                &format!("{base}{suffix}"),
                Some("secret-token"),
            ))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let body = axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .unwrap();
        let page: Vec<EconomicEvent> = serde_json::from_slice(&body).unwrap();
        assert_eq!(
            page.iter()
                .map(|event| event.provider_id.as_str())
                .collect::<Vec<_>>(),
            expected
        );
    }
    for invalid_levels in ["4", "1,4", "", "1,", "-1", "high"] {
        let invalid = router
            .clone()
            .oneshot(request(
                "GET",
                &format!("{base}&importance={invalid_levels}"),
                Some("secret-token"),
            ))
            .await
            .unwrap();
        assert_eq!(invalid.status(), StatusCode::BAD_REQUEST);
    }
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
async fn retired_ai_routes_are_unavailable_and_existing_data_is_preserved() {
    let app = app().await;
    sqlx::query("INSERT INTO ai_analysis (event_id, language, method, timezone, revision, chain_json, data_analysis, market_outlook, model, generated_at) VALUES (1, 'en', 2, 'UTC', 1, '[]', 'data', 'outlook', 'test-model', '2026-09-19T00:00:00Z')")
        .execute(app.state.events.pool()).await.unwrap();
    sqlx::query("INSERT INTO shared_ai_job(event_id, method, language, target_revision) VALUES(1, 2, 'en', 2)")
        .execute(app.state.events.pool()).await.unwrap();
    let router = api::router(app.state.clone());
    for (method, path) in [
        ("GET", "/api/v1/events/1/ai-analysis?language=en&method=2"),
        (
            "GET",
            "/api/v1/events/1/ai-analysis?language=en&method=2&refresh=true",
        ),
        (
            "GET",
            "/api/v1/ai-analysis/by-provider?provider=trading_view&provider_id=fixture-2",
        ),
        ("POST", "/api/v1/events/1/analysis-feedback"),
    ] {
        let response = router
            .clone()
            .oneshot(request(method, path, Some("secret-token")))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::NOT_FOUND);
    }
    let response = router
        .oneshot(request("GET", "/api/v1/meta", None))
        .await
        .unwrap();
    let body: serde_json::Value = serde_json::from_slice(
        &axum::body::to_bytes(response.into_body(), usize::MAX)
            .await
            .unwrap(),
    )
    .unwrap();
    assert_eq!(body["aiEnabled"], false);
    assert!(
        !body["capabilities"]
            .as_array()
            .unwrap()
            .contains(&serde_json::json!("aiAnalysis"))
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM ai_analysis")
            .fetch_one(app.state.events.pool())
            .await
            .unwrap(),
        1
    );
    let status: String = sqlx::query_scalar("SELECT status FROM shared_ai_job")
        .fetch_one(app.state.events.pool())
        .await
        .unwrap();
    assert_eq!(status, "pending"); // No worker is started; old jobs stay inert.
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

#[tokio::test]
async fn websocket_binds_the_same_device_and_revocation_closes_an_idle_connection() {
    use market_event_analyzer::access_keys::{AccessKeys, KeyKind};
    use tokio::{
        io::{AsyncReadExt, AsyncWriteExt},
        net::TcpStream,
    };
    const UUID_A: &str = "11111111-1111-4111-8111-111111111111";
    const UUID_B: &str = "22222222-2222-4222-8222-222222222222";
    async fn handshake(
        address: std::net::SocketAddr,
        secret: &str,
        uuid: Option<&str>,
    ) -> (TcpStream, String) {
        let mut stream = TcpStream::connect(address).await.unwrap();
        let identity = uuid
            .map(|uuid| {
                format!(
                    "X-Installation-Id: {uuid}\r\nX-Android-Id-Hash: {}\r\n",
                    "a".repeat(64)
                )
            })
            .unwrap_or_default();
        let request = format!(
            "GET /api/v1/ws HTTP/1.1\r\nHost: {address}\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nAuthorization: Bearer {secret}\r\n{identity}\r\n"
        );
        stream.write_all(request.as_bytes()).await.unwrap();
        let response = tokio::time::timeout(std::time::Duration::from_secs(5), async {
            let mut headers = Vec::new();
            while !headers.ends_with(b"\r\n\r\n") {
                headers.push(stream.read_u8().await.unwrap());
                assert!(headers.len() < 8192);
            }
            String::from_utf8(headers).unwrap()
        })
        .await
        .unwrap();
        (stream, response)
    }
    let app = app().await;
    let keys = AccessKeys::new(app.state.events.pool().clone());
    let (request, _) = keys.request(99, 1, KeyKind::Device).await.unwrap();
    let key = keys
        .approve(request.id, 42, KeyKind::Device)
        .await
        .unwrap()
        .unwrap();
    keys.finish_delivery(&key, true).await.unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move {
        axum::serve(listener, api::router(app.state)).await.unwrap();
    });
    let (_, missing) = handshake(address, &key.secret, None).await;
    assert!(missing.starts_with("HTTP/1.1 401"));
    let (mut socket, authorized) = handshake(address, &key.secret, Some(UUID_A)).await;
    assert!(authorized.starts_with("HTTP/1.1 101"));
    let (_, other) = handshake(address, &key.secret, Some(UUID_B)).await;
    assert!(other.starts_with("HTTP/1.1 401"));
    keys.revoke(key.id, 42).await.unwrap();
    let mut frame = [0u8; 2];
    tokio::time::timeout(
        std::time::Duration::from_secs(7),
        socket.read_exact(&mut frame),
    )
    .await
    .unwrap()
    .unwrap();
    assert_eq!(frame[0] & 0x0f, 8); // WebSocket Close, even when the event bus is idle.
    let (_, revoked) = handshake(address, &key.secret, Some(UUID_A)).await;
    assert!(revoked.starts_with("HTTP/1.1 401"));
    server.abort();
}
