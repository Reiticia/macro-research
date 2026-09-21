use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};

use axum::{Json, Router, extract::State, routing::post};
use chrono::{Duration, Utc};
use market_event_analyzer::{
    ai_analysis::{AiAnalysisService, AnalysisMethod},
    config::{AiConfig, LimitConfig},
    model::{EconomicEvent, EventStatus},
    quota::QuotaService,
    repository::{AnalysisRepository, EventRepository, MarketRepository},
};
use rust_decimal::Decimal;
use serde_json::json;
use sqlx::sqlite::SqlitePoolOptions;

#[derive(Clone)]
struct RelayState {
    calls: Arc<AtomicUsize>,
}

async fn complete(State(state): State<RelayState>) -> Json<serde_json::Value> {
    state.calls.fetch_add(1, Ordering::SeqCst);
    Json(json!({
        "choices": [{
            "message": {
                "content": "{\"chain\":[],\"dataAnalysis\":\"data\",\"marketOutlook\":\"outlook\",\"risks\":\"risk\"}"
            }
        }]
    }))
}

#[tokio::test]
async fn lazy_generation_is_single_flight_and_scoped_to_the_requested_language() {
    let pool = SqlitePoolOptions::new()
        .max_connections(4)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let events = EventRepository::new(pool.clone());
    let analyses = AnalysisRepository::new(pool.clone());
    let market = MarketRepository::new(pool.clone());
    let event_id = events
        .save_events(&[EconomicEvent {
            id: 0,
            provider: "test".into(),
            provider_id: "lazy-ai".into(),
            release_group_id: None,
            country: "United States".into(),
            currency: Some("USD".into()),
            category: "inflation".into(),
            event: "CPI YoY".into(),
            event_zh_cn: None,
            event_zh_tw: None,
            event_time: Utc::now() - Duration::hours(2),
            importance: 3,
            actual: Some(Decimal::new(32, 1)),
            previous: Some(Decimal::new(30, 1)),
            consensus: Some(Decimal::new(31, 1)),
            forecast: Some(Decimal::new(31, 1)),
            unit: Some("%".into()),
            status: EventStatus::Completed,
            time_exact: true,
        }])
        .await
        .unwrap()[0];
    sqlx::query(
        "INSERT INTO analysis_report (event_id, raw_surprise, macro_signal, expected_reaction_json, observed_reaction_json, summary, created_at, updated_at) VALUES (?, ?, 'hawkish', '[]', '{\"reactions\":[],\"comparisons\":[],\"historical\":null}', 'summary', ?, ?)",
    )
    .bind(event_id)
    .bind("0.1")
    .bind(Utc::now().to_rfc3339())
    .bind(Utc::now().to_rfc3339())
    .execute(&pool)
    .await
    .unwrap();

    let relay_state = RelayState {
        calls: Arc::new(AtomicUsize::new(0)),
    };
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base_url = format!("http://{}/v1", listener.local_addr().unwrap());
    let relay = tokio::spawn({
        let relay_state = relay_state.clone();
        async move {
            axum::serve(
                listener,
                Router::new()
                    .route("/v1/chat/completions", post(complete))
                    .with_state(relay_state),
            )
            .await
            .unwrap();
        }
    });

    let mut config = AiConfig::default();
    config.base_url = base_url;
    config.api_key = "test-key".into();
    config.model = "test-model".into();
    let service = Arc::new(
        AiAnalysisService::new(
            reqwest::Client::new(),
            config,
            pool.clone(),
            events,
            analyses,
            market,
        )
        .unwrap(),
    );
    let quota = QuotaService::new(
        pool.clone(),
        LimitConfig {
            ai_analysis_per_token_per_day: 10,
            ai_regenerate_cooldown_seconds: 0,
            ai_concurrency: 2,
            llm_usage_retention_days: 180,
        },
    );

    let first = service.generate_lazy(
        event_id,
        "en",
        AnalysisMethod::NumbersOnly,
        "UTC",
        "alice",
        &quota,
    );
    let second = service.generate_lazy(
        event_id,
        "en",
        AnalysisMethod::NumbersOnly,
        "UTC",
        "alice",
        &quota,
    );
    let (first, second) = tokio::join!(first, second);
    let first = first.unwrap();
    let second = second.unwrap();
    assert_eq!(relay_state.calls.load(Ordering::SeqCst), 1);
    assert_ne!(first.from_cache, second.from_cache);

    let chinese = service
        .generate_lazy(
            event_id,
            "zh-CN",
            AnalysisMethod::NumbersOnly,
            "UTC",
            "alice",
            &quota,
        )
        .await
        .unwrap();
    assert!(!chinese.from_cache);
    assert_eq!(relay_state.calls.load(Ordering::SeqCst), 2);
    let rows: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM ai_analysis")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(rows, 2);

    relay.abort();
}
