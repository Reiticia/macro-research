use std::{
    collections::HashMap,
    sync::{Arc, Mutex},
};

use async_trait::async_trait;
use axum::{Json, Router, extract::Query, routing::get};
use chrono::{DateTime, Duration, Utc};
use market_event_analyzer::{
    calendar::{BiquoteCalendarProvider, CalendarProvider, CalendarService},
    error::AppError,
    model::{EconomicEvent, EventStatus},
    repository::EventRepository,
};
use rust_decimal::Decimal;
use serde_json::{Value, json};
use sqlx::sqlite::SqlitePoolOptions;

async fn repository() -> EventRepository {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    EventRepository::new(pool)
}

fn start() -> DateTime<Utc> {
    "2026-10-28T00:00:00Z".parse().unwrap()
}

fn row(id: usize, time: DateTime<Utc>) -> Value {
    json!({"id":format!("mql5:{id}"), "eventId":"mql5:series", "time":time,
        "countryCode":"US", "currency":"USD", "name":"CPI m/m", "importance":"high",
        "actual":0, "forecast":0.2, "unit":"percent", "multiplier":"none", "timeMode":"exact"})
}

async fn serve(
    rows: Vec<Value>,
) -> (
    BiquoteCalendarProvider,
    Arc<Mutex<Vec<HashMap<String, String>>>>,
    tokio::task::JoinHandle<()>,
) {
    let requests = Arc::new(Mutex::new(Vec::new()));
    let seen = requests.clone();
    let router = Router::new().route(
        "/api/calendar",
        get(move |Query(query): Query<HashMap<String, String>>| {
            let seen = seen.clone();
            let rows = rows.clone();
            async move {
                seen.lock().unwrap().push(query.clone());
                let from: DateTime<Utc> = query["from"].parse().unwrap();
                let to: DateTime<Utc> = query["to"].parse().unwrap();
                let limit: usize = query["limit"].parse().unwrap();
                Json(
                    rows.into_iter()
                        .filter(|row| {
                            let time: DateTime<Utc> =
                                row["time"].as_str().unwrap().parse().unwrap();
                            time >= from && time <= to
                        })
                        .take(limit)
                        .collect::<Vec<_>>(),
                )
            }
        }),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/api/calendar", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    (
        BiquoteCalendarProvider::new(reqwest::Client::new(), url),
        requests,
        task,
    )
}

#[tokio::test]
async fn saturated_days_are_split_without_losing_or_duplicating_boundary_events() {
    let rows = (0..600)
        .map(|id| row(id, start() + Duration::minutes(id as i64 * 2)))
        .collect();
    let (provider, requests, task) = serve(rows).await;
    let events = provider
        .fetch_events(start(), start() + Duration::days(1))
        .await
        .unwrap();
    assert_eq!(events.len(), 600);
    assert!(events.iter().all(|e| e.actual == Some(Decimal::ZERO)));
    let requests = requests.lock().unwrap();
    assert_eq!(requests.len(), 3);
    assert!(requests.iter().all(|q| q["limit"] == "500"));
    task.abort();
}

#[tokio::test]
async fn daily_windows_are_half_open_and_use_occurrence_not_series_ids() {
    let rows = vec![
        row(1, start()),
        row(2, start() + Duration::days(1)),
        row(3, start() + Duration::days(2)),
    ];
    let (provider, requests, task) = serve(rows).await;
    let events = provider
        .fetch_events(start(), start() + Duration::days(2))
        .await
        .unwrap();
    assert_eq!(
        events
            .iter()
            .map(|e| e.provider_id.as_str())
            .collect::<Vec<_>>(),
        vec!["mql5:1", "mql5:2"]
    );
    assert_eq!(requests.lock().unwrap().len(), 2);
    assert!(
        provider
            .fetch_events(start(), start())
            .await
            .unwrap()
            .is_empty()
    );
    task.abort();
}

#[tokio::test]
async fn unsplittable_row_cap_fails_instead_of_reporting_partial_success() {
    let (provider, _, task) = serve((0..500).map(|id| row(id, start())).collect()).await;
    let error = provider
        .fetch_events(start(), start() + Duration::seconds(1))
        .await
        .unwrap_err();
    assert!(error.to_string().contains("row cap"));
    task.abort();
}

struct Feed {
    name: &'static str,
    result: u8,
    calls: Arc<Mutex<Vec<&'static str>>>,
}
#[async_trait]
impl CalendarProvider for Feed {
    async fn fetch_events(
        &self,
        _: DateTime<Utc>,
        _: DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        self.calls.lock().unwrap().push(self.name);
        match self.result {
            0 => Err(AppError::Provider("test outage".into())),
            1 => Ok(Vec::new()),
            _ => Ok(vec![event(self.name, "id")]),
        }
    }
}

fn event(provider: &str, id: &str) -> EconomicEvent {
    EconomicEvent {
        id: 0,
        provider: provider.into(),
        provider_id: id.into(),
        release_group_id: None,
        country: "United States".into(),
        currency: Some("USD".into()),
        category: "prices".into(),
        event: "Core CPI m/m".into(),
        event_zh_cn: Some("核心CPI月率".into()),
        event_zh_tw: None,
        event_time: start() + Duration::hours(12),
        importance: 3,
        actual: None,
        previous: Some(Decimal::ONE),
        consensus: Some(Decimal::ONE),
        forecast: Some(Decimal::ONE),
        unit: Some("%".into()),
        status: EventStatus::Scheduled,
        time_exact: true,
    }
}

#[tokio::test]
async fn production_priority_is_tradingview_then_biquote_then_weekly() {
    for (primary, secondary, expected) in [
        (2, 2, vec!["trading_view"]),
        (0, 2, vec!["trading_view", "biquote"]),
        (1, 2, vec!["trading_view", "biquote"]),
        (0, 0, vec!["trading_view", "biquote", "forex_factory"]),
    ] {
        let calls = Arc::new(Mutex::new(Vec::new()));
        let feed = |name, result| {
            Arc::new(Feed {
                name,
                result,
                calls: calls.clone(),
            })
        };
        let service = CalendarService::new(
            feed("trading_view", primary),
            feed("forex_factory", 2),
            repository().await,
        )
        .with_secondary(feed("biquote", secondary));
        let result = service
            .lookup(start(), start() + Duration::days(1))
            .await
            .unwrap();
        assert_eq!(*calls.lock().unwrap(), expected);
        assert_eq!(result.events[0].provider, *expected.last().unwrap());
        assert_eq!(result.degraded_detail.is_some(), primary != 2);
    }
}

#[tokio::test]
async fn promotion_preserves_ids_observations_and_primary_values() {
    let repository = repository().await;
    let old = event("biquote", "old");
    let id = repository.save_events(&[old.clone()]).await.unwrap()[0];
    repository
        .set_status(id, EventStatus::Watching)
        .await
        .unwrap();
    let mut new = event("trading_view", "tv:occurrence");
    new.event = "Core Inflation Rate MoM".into();
    new.actual = Some(Decimal::new(3, 1));
    new.event_zh_cn = None;
    assert_eq!(
        repository.save_events(&[new.clone()]).await.unwrap(),
        vec![id]
    );
    assert_eq!(repository.get(id).await.unwrap().provider, "trading_view");
    assert_eq!(
        repository.get(id).await.unwrap().event_zh_cn.as_deref(),
        Some("核心CPI月率")
    );
    assert_eq!(repository.observations(id).await.unwrap().len(), 2);
    assert_eq!(repository.save_events(&[old]).await.unwrap(), vec![id]);
    assert_eq!(repository.get(id).await.unwrap().actual, new.actual);
    assert_eq!(
        repository
            .events_in_range(start(), start() + Duration::days(1))
            .await
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        repository.get(id).await.unwrap().status,
        EventStatus::Watching
    );
}

#[tokio::test]
async fn fallback_can_fill_a_missing_primary_without_demoting_it() {
    let repository = repository().await;
    let id = repository
        .save_events(&[event("trading_view", "new")])
        .await
        .unwrap()[0];
    let mut fallback = event("biquote", "old");
    fallback.actual = Some(Decimal::ZERO);
    assert_eq!(repository.save_events(&[fallback]).await.unwrap(), vec![id]);
    let stored = repository.get(id).await.unwrap();
    assert_eq!(stored.provider, "trading_view");
    assert_eq!(stored.actual, Some(Decimal::ZERO));
}

#[tokio::test]
async fn ambiguous_or_inexact_occurrences_are_not_promoted() {
    let repository = repository().await;
    repository
        .save_events(&[event("biquote", "old1"), event("biquote", "old2")])
        .await
        .unwrap();
    let ids = repository
        .save_events(&[event("trading_view", "new")])
        .await
        .unwrap();
    assert_eq!(ids, vec![3]);
    let mut inexact = event("trading_view", "tentative");
    inexact.time_exact = false;
    assert_ne!(repository.save_events(&[inexact]).await.unwrap(), ids);
}
