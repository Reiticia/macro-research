use std::sync::{
    Arc, Mutex,
    atomic::{AtomicUsize, Ordering},
};

use async_trait::async_trait;
use market_event_analyzer::{
    error::AppError,
    model::{EconomicEvent, EventStatus},
    repository::EventRepository,
    translation::{
        EventNameTranslation, EventNameTranslator, TranslationRevision, TranslationService,
        TranslationVerdict,
    },
};
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

fn event(name: &str) -> EconomicEvent {
    EconomicEvent {
        id: 0,
        provider: "fixture".into(),
        provider_id: format!("fixture-{name}"),
        release_group_id: None,
        country: "United States".into(),
        currency: Some("USD".into()),
        category: "inflation".into(),
        event: name.into(),
        event_zh_cn: None,
        event_zh_tw: None,
        event_time: chrono::Utc::now(),
        importance: 3,
        actual: None,
        previous: None,
        consensus: None,
        forecast: None,
        unit: Some("%".into()),
        status: EventStatus::Scheduled,
        time_exact: true,
    }
}

/// Rejects the first draft of every name and accepts the revision, recording what the retry
/// was told.
#[derive(Default)]
struct RetryOnceTranslator {
    translates: AtomicUsize,
    verifies: AtomicUsize,
    revisions: Mutex<Vec<TranslationRevision>>,
}

#[async_trait]
impl EventNameTranslator for RetryOnceTranslator {
    async fn translate(
        &self,
        names: &[String],
        revisions: &[TranslationRevision],
    ) -> Result<Vec<EventNameTranslation>, AppError> {
        self.translates.fetch_add(1, Ordering::SeqCst);
        self.revisions
            .lock()
            .unwrap()
            .extend(revisions.iter().cloned());
        let prefix = if revisions.is_empty() {
            "初稿"
        } else {
            "修订"
        };
        Ok(names
            .iter()
            .map(|source| EventNameTranslation {
                source: source.clone(),
                zh_cn: format!("{prefix}{source}"),
                zh_tw: format!("{prefix}{source}"),
            })
            .collect())
    }

    async fn verify(
        &self,
        translations: &[EventNameTranslation],
    ) -> Result<Vec<TranslationVerdict>, AppError> {
        self.verifies.fetch_add(1, Ordering::SeqCst);
        Ok(translations
            .iter()
            .map(|row| {
                let draft = row.zh_cn.starts_with("初稿");
                TranslationVerdict {
                    source: row.source.clone(),
                    approved: !draft,
                    reason: draft.then(|| "use the standard term".to_owned()),
                }
            })
            .collect())
    }
}

#[tokio::test]
async fn rejected_names_are_retried_with_the_reviewer_note_and_only_approved_rows_are_cached() {
    let repository = repository().await;
    let translator = Arc::new(RetryOnceTranslator::default());
    let service = TranslationService::new(repository.clone(), translator.clone(), 10);

    let mut events = vec![event("CPI y/y"), event("Nonfarm Payrolls")];
    service.enrich(&mut events).await.unwrap();

    assert_eq!(
        translator.translates.load(Ordering::SeqCst),
        2,
        "one draft round plus one retry round"
    );
    assert_eq!(translator.verifies.load(Ordering::SeqCst), 2);
    let revisions = translator.revisions.lock().unwrap();
    assert_eq!(revisions.len(), 2, "both names are handed back with a note");
    assert!(
        revisions
            .iter()
            .all(|row| row.reason == "use the standard term"
                && row.previous_zh_cn == format!("初稿{}", row.source))
    );
    drop(revisions);

    assert_eq!(events[0].event_zh_cn.as_deref(), Some("修订CPI y/y"));
    assert_eq!(
        events[1].event_zh_tw.as_deref(),
        Some("修订Nonfarm Payrolls")
    );
    let cached = repository
        .cached_event_name_translations(&["CPI y/y".into()])
        .await
        .unwrap();
    assert_eq!(
        cached.get("CPI y/y").map(|(zh_cn, _)| zh_cn.as_str()),
        Some("修订CPI y/y"),
        "the rejected draft never reaches the cache"
    );
}

struct RejectingTranslator {
    translates: AtomicUsize,
}

impl Default for RejectingTranslator {
    fn default() -> Self {
        Self {
            translates: AtomicUsize::new(0),
        }
    }
}

#[async_trait]
impl EventNameTranslator for RejectingTranslator {
    async fn translate(
        &self,
        names: &[String],
        _revisions: &[TranslationRevision],
    ) -> Result<Vec<EventNameTranslation>, AppError> {
        self.translates.fetch_add(1, Ordering::SeqCst);
        Ok(names
            .iter()
            .map(|source| EventNameTranslation {
                source: source.clone(),
                zh_cn: "差".into(),
                zh_tw: "差".into(),
            })
            .collect())
    }

    async fn verify(
        &self,
        translations: &[EventNameTranslation],
    ) -> Result<Vec<TranslationVerdict>, AppError> {
        Ok(translations
            .iter()
            .map(|row| TranslationVerdict {
                source: row.source.clone(),
                approved: false,
                reason: Some("still wrong".into()),
            })
            .collect())
    }
}

#[tokio::test]
async fn names_that_never_pass_review_stay_untranslated_and_the_loop_is_bounded() {
    let repository = repository().await;
    let translator = Arc::new(RejectingTranslator::default());
    let service =
        TranslationService::new(repository.clone(), translator.clone(), 10).with_max_rounds(2);

    let mut events = vec![event("CPI y/y")];
    service.enrich(&mut events).await.unwrap();

    assert_eq!(translator.translates.load(Ordering::SeqCst), 2);
    assert!(
        events[0].event_zh_cn.is_none(),
        "nothing unverified is published"
    );
    assert!(
        repository
            .cached_event_name_translations(&["CPI y/y".into()])
            .await
            .unwrap()
            .is_empty(),
        "the next sync retries it instead of caching a rejected name"
    );
}

/// A reviewer that cannot answer (endpoint error) must not block usable translations.
struct UnavailableReviewer {
    translates: AtomicUsize,
}

impl Default for UnavailableReviewer {
    fn default() -> Self {
        Self {
            translates: AtomicUsize::new(0),
        }
    }
}

#[async_trait]
impl EventNameTranslator for UnavailableReviewer {
    async fn translate(
        &self,
        names: &[String],
        _revisions: &[TranslationRevision],
    ) -> Result<Vec<EventNameTranslation>, AppError> {
        self.translates.fetch_add(1, Ordering::SeqCst);
        Ok(names
            .iter()
            .map(|source| EventNameTranslation {
                source: source.clone(),
                zh_cn: "消费者价格指数年率".into(),
                zh_tw: "消費者物價指數年率".into(),
            })
            .collect())
    }

    async fn verify(
        &self,
        _translations: &[EventNameTranslation],
    ) -> Result<Vec<TranslationVerdict>, AppError> {
        Err(AppError::Provider("review relay is down".into()))
    }
}

#[tokio::test]
async fn an_unavailable_reviewer_keeps_the_translation_instead_of_failing_the_sync() {
    let repository = repository().await;
    let translator = Arc::new(UnavailableReviewer::default());
    let service = TranslationService::new(repository.clone(), translator.clone(), 10);

    let mut events = vec![event("CPI y/y")];
    service.enrich(&mut events).await.unwrap();

    assert_eq!(translator.translates.load(Ordering::SeqCst), 1);
    assert_eq!(events[0].event_zh_cn.as_deref(), Some("消费者价格指数年率"));
    assert_eq!(
        repository
            .cached_event_name_translations(&["CPI y/y".into()])
            .await
            .unwrap()
            .len(),
        1
    );
}
