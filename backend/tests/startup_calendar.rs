use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use chrono::{DateTime, Duration, Utc};
use market_event_analyzer::{
    calendar::{CalendarProvider, CalendarService},
    error::AppError,
    model::EconomicEvent,
    repository::EventRepository,
    scheduler::startup_calendar_sync,
};
use sqlx::sqlite::SqlitePoolOptions;

#[derive(Default)]
struct RecordingProvider {
    ranges: Mutex<Vec<(DateTime<Utc>, DateTime<Utc>)>>,
}

#[async_trait]
impl CalendarProvider for RecordingProvider {
    async fn fetch_events(
        &self,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        self.ranges.lock().unwrap().push((start, end));
        Err(AppError::Provider("test outage".into()))
    }
}

#[tokio::test]
async fn startup_requests_seven_complete_utc_days_and_continues_after_errors() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    let primary = Arc::new(RecordingProvider::default());
    let fallback = Arc::new(RecordingProvider::default());
    let service = Arc::new(CalendarService::new(
        primary.clone(),
        fallback.clone(),
        EventRepository::new(pool),
    ));
    let before = Utc::now().date_naive();
    startup_calendar_sync(service).await;
    let after = Utc::now().date_naive();
    let ranges = primary.ranges.lock().unwrap();
    assert_eq!(ranges.len(), 7);
    assert_eq!(*ranges, *fallback.ranges.lock().unwrap());
    let today = ranges[6].0.date_naive();
    assert!(today == before || today == after);
    assert_eq!(ranges[0].0.date_naive(), today - Duration::days(6));
    for (start, end) in ranges.iter() {
        assert_eq!(*end - *start, Duration::days(1));
        assert_eq!(start.time(), chrono::NaiveTime::MIN);
    }
    for pair in ranges.windows(2) {
        assert_eq!(pair[0].1, pair[1].0);
    }
}
