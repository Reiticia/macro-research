use super::*;
use crate::news::{NewsArticle, NewsService};
use crate::news_page::PageReader;
use chrono::Duration as ChronoDuration;
use sqlx::sqlite::SqlitePoolOptions;
use std::sync::atomic::{AtomicUsize, Ordering};

async fn archive() -> NewsArchive {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    NewsArchive::new(pool)
}
fn article(title: &str, url: &str, now: DateTime<Utc>) -> NewsArticle {
    NewsArticle {
        archive_id: crate::news_archive::article_id(url),
        title: title.into(),
        url: url.into(),
        source: "Federal Reserve".into(),
        feed_id: "fed".into(),
        published_at: now - ChronoDuration::seconds(30),
        summary: Some("Untouched summary".into()),
        title_translations: Titles::new(),
    }
}
fn translations(prefix: &str) -> Titles {
    [
        ("en", format!("English {prefix}")),
        ("zh-CN", format!("简体 {prefix}")),
        ("zh-TW", format!("繁體 {prefix}")),
    ]
    .into_iter()
    .map(|(k, v)| (k.into(), v))
    .collect()
}
#[derive(Default)]
struct Fake {
    calls: AtomicUsize,
    fail: bool,
}
#[async_trait]
impl NewsTitleTranslator for Fake {
    async fn translate(&self, titles: &[String]) -> Result<Vec<Titles>, AppError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        if self.fail {
            return Err(failure("test_failure"));
        }
        Ok(titles.iter().map(|title| translations(title)).collect())
    }
}
#[tokio::test]
async fn prepares_all_three_languages_once_and_readers_never_call_models() {
    let archive = archive().await;
    let now = Utc::now();
    let row = article(
        "Policy news",
        "https://www.federalreserve.gov/newsevents/test.htm",
        now,
    );
    archive.ingest(&[row.clone()], now).await.unwrap();
    let fake = Arc::new(Fake::default());
    let worker = NewsTitleWorker::new(archive.clone(), fake.clone());
    assert!(
        archive.list(0, now).await.unwrap().0[0]
            .title_translations
            .is_empty()
    );
    assert_eq!(worker.run_once(now).await.unwrap(), 1);
    let result = archive.list(0, now).await.unwrap().0.remove(0);
    assert_eq!(result.title, row.title);
    assert_eq!(result.summary, row.summary);
    assert_eq!(result.url, row.url);
    assert_eq!(result.title_translations, translations(&row.title));
    assert_eq!(
        archive
            .article(&row.archive_id, now)
            .await
            .unwrap()
            .title_translations,
        result.title_translations
    );
    let restarted = NewsTitleWorker::new(archive.clone(), fake.clone());
    assert_eq!(
        restarted
            .run_once(now + ChronoDuration::hours(2))
            .await
            .unwrap(),
        0
    );
    let service = NewsService::new(crate::news::http_client(None).unwrap())
        .with_archive(archive.pool.clone(), PageReader::new(None).unwrap());
    for _ in 0..3 {
        let _ = service.latest().await;
        service.archived_article(&row.archive_id).await.unwrap();
    }
    assert_eq!(fake.calls.load(Ordering::SeqCst), 1);
}
#[tokio::test]
async fn title_revision_invalidates_only_its_translations_and_requeues_once() {
    let archive = archive().await;
    let now = Utc::now();
    let mut row = article("Old title", "https://www.federalreserve.gov/a.htm", now);
    let fake = Arc::new(Fake::default());
    let worker = NewsTitleWorker::new(archive.clone(), fake.clone());
    archive.ingest(&[row.clone()], now).await.unwrap();
    worker.run_once(now).await.unwrap();
    archive
        .ingest(&[row.clone()], now + ChronoDuration::seconds(1))
        .await
        .unwrap();
    assert_eq!(worker.run_once(now).await.unwrap(), 0);
    row.title = "Corrected title".into();
    archive.ingest(&[row.clone()], now).await.unwrap();
    assert!(
        archive
            .article(&row.archive_id, now)
            .await
            .unwrap()
            .title_translations
            .is_empty()
    );
    worker.run_once(now).await.unwrap();
    assert_eq!(fake.calls.load(Ordering::SeqCst), 2);
    assert_eq!(
        archive
            .article(&row.archive_id, now)
            .await
            .unwrap()
            .title_translations,
        translations(&row.title)
    );
}
#[tokio::test]
async fn retries_and_attempt_caps_survive_restarts_and_failures_keep_originals() {
    let archive = archive().await;
    let now = Utc::now();
    let row = article("Original", "https://www.federalreserve.gov/a.htm", now);
    archive.ingest(&[row.clone()], now).await.unwrap();
    let fake = Arc::new(Fake {
        fail: true,
        ..Default::default()
    });
    for attempt in 0..3 {
        let worker = NewsTitleWorker::new(archive.clone(), fake.clone());
        assert!(
            worker
                .run_once(now + ChronoDuration::hours(attempt * 2))
                .await
                .is_err()
        );
        assert_eq!(
            NewsTitleWorker::new(archive.clone(), fake.clone())
                .run_once(now + ChronoDuration::hours(attempt * 2))
                .await
                .unwrap(),
            0
        );
    }
    assert_eq!(
        NewsTitleWorker::new(archive.clone(), fake.clone())
            .run_once(now + ChronoDuration::hours(20))
            .await
            .unwrap(),
        0
    );
    assert_eq!(fake.calls.load(Ordering::SeqCst), 3);
    let stored = archive.article(&row.archive_id, now).await.unwrap();
    assert_eq!(stored.title, "Original");
    assert!(stored.title_translations.is_empty());
}
#[tokio::test]
async fn multiple_workers_cannot_bill_twice_for_one_claim() {
    let archive = archive().await;
    let now = Utc::now();
    archive
        .ingest(
            &[article("News", "https://www.federalreserve.gov/a.htm", now)],
            now,
        )
        .await
        .unwrap();
    let fake = Arc::new(Fake::default());
    let a = NewsTitleWorker::new(archive.clone(), fake.clone());
    let b = NewsTitleWorker::new(archive, fake.clone());
    let (x, y) = tokio::join!(a.run_once(now), b.run_once(now));
    assert_eq!(x.unwrap() + y.unwrap(), 1);
    assert_eq!(fake.calls.load(Ordering::SeqCst), 1);
}
struct Paused {
    started: tokio::sync::Notify,
    resume: tokio::sync::Notify,
}
#[async_trait]
impl NewsTitleTranslator for Paused {
    async fn translate(&self, titles: &[String]) -> Result<Vec<Titles>, AppError> {
        self.started.notify_one();
        self.resume.notified().await;
        Ok(titles.iter().map(|_| translations("stale")).collect())
    }
}
#[tokio::test]
async fn late_results_cannot_overwrite_changed_then_reverted_title() {
    let archive = archive().await;
    let now = Utc::now();
    let mut row = article("A", "https://www.federalreserve.gov/a.htm", now);
    archive.ingest(&[row.clone()], now).await.unwrap();
    let paused = Arc::new(Paused {
        started: Default::default(),
        resume: Default::default(),
    });
    let worker = Arc::new(NewsTitleWorker::new(archive.clone(), paused.clone()));
    let task = tokio::spawn(async move { worker.run_once(now).await });
    paused.started.notified().await;
    row.title = "B".into();
    archive.ingest(&[row.clone()], now).await.unwrap();
    row.title = "A".into();
    archive.ingest(&[row.clone()], now).await.unwrap();
    NewsTitleWorker::new(archive.clone(), Arc::new(Fake::default()))
        .run_once(now)
        .await
        .unwrap();
    paused.resume.notify_one();
    assert_eq!(task.await.unwrap().unwrap(), 0);
    assert_eq!(
        archive
            .article(&row.archive_id, now)
            .await
            .unwrap()
            .title_translations,
        translations("A")
    );
}
#[tokio::test]
async fn work_is_bounded_retention_is_enforced_and_pruning_removes_translations() {
    let archive = archive().await;
    let now = Utc::now();
    let mut rows = Vec::new();
    for i in 0..7 {
        rows.push(article(
            &format!("News {i}"),
            &format!("https://www.federalreserve.gov/{i}.htm"),
            now,
        ));
    }
    archive.ingest(&rows, now).await.unwrap();
    let fake = Arc::new(Fake::default());
    let worker = NewsTitleWorker::new(archive.clone(), fake.clone());
    assert_eq!(worker.run_once(now).await.unwrap(), 5);
    assert_eq!(worker.run_once(now).await.unwrap(), 2);
    archive.prune(now + ChronoDuration::days(31)).await.unwrap();
    assert!(
        archive
            .list(0, now + ChronoDuration::days(31))
            .await
            .unwrap()
            .0
            .is_empty()
    );
    assert_eq!(
        worker
            .run_once(now + ChronoDuration::days(31))
            .await
            .unwrap(),
        0
    );
}
#[tokio::test]
async fn migration_from_v27_preserves_existing_news_and_body() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    let mut old = sqlx::migrate!("./migrations");
    old.migrations = std::borrow::Cow::Owned(
        old.migrations
            .iter()
            .filter(|m| m.version <= 27)
            .cloned()
            .collect(),
    );
    old.run(&pool).await.unwrap();
    let now = Utc::now().timestamp();
    sqlx::query("INSERT INTO news_articles(id,original_url,title,source,feed_id,published_at,first_seen_at,last_seen_at,body_text,body_status) VALUES('old','https://www.federalreserve.gov/a','Original','Fed','fed',?,?,?,'Saved body','available')")
        .bind(now).bind(now).bind(now).execute(&pool).await.unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let row = sqlx::query("SELECT * FROM news_articles WHERE id='old'")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(row.get::<String, _>("title"), "Original");
    assert_eq!(row.get::<String, _>("body_text"), "Saved body");
    assert_eq!(row.get::<String, _>("title_translation_status"), "pending");
    assert_eq!(row.get::<String, _>("title_translations"), "{}");
}
fn response(rows: Value) -> String {
    json!({"choices":[{"message":{"content":format!("```json\n{}\n```",json!({"translations":rows}))}}],"usage":{"prompt_tokens":20,"completion_tokens":30,"total_tokens":50}}).to_string()
}
#[test]
fn validates_exact_ids_all_languages_plain_text_and_no_partial_misattribution() {
    let good = json!({"id":0,"en":"English","zhCn":"简体","zhTw":"繁體"});
    let parsed = parse_response(&response(json!([good.clone()])), 1).unwrap();
    assert_eq!(parsed[0]["zh-CN"], "简体");
    for rows in [
        json!([]),
        json!([good.clone(), good.clone()]),
        json!([{"id":1,"en":"e","zhCn":"中","zhTw":"中"}]),
        json!([{"id":0,"en":"e","zhCn":"中"}]),
        json!([{"id":0,"en":"<b>e</b>","zhCn":"中","zhTw":"中"}]),
        json!([{"id":0,"en":"e","zhCn":"","zhTw":"中"}]),
    ] {
        assert!(parse_response(&response(rows), 1).is_err());
    }
    assert!(stored_titles(r#"{"en":"safe","fr":"not supported","zh-CN":"<script>"}"#).len() == 1);
}
#[tokio::test]
async fn http_calls_are_title_only_audited_and_retry_only_compatibility_errors() {
    use axum::{Json, extract::State, http::StatusCode, routing::post};
    let bodies = Arc::new(Mutex::new(Vec::<Value>::new()));
    let app = axum::Router::new()
        .route(
            "/chat/completions",
            post(
                |State(bodies): State<Arc<Mutex<Vec<Value>>>>, Json(body): Json<Value>| async move {
                    let mut saved = bodies.lock().await;
                    saved.push(body);
                    if saved.len() == 1 {
                        (
                            StatusCode::BAD_REQUEST,
                            "response_format unsupported".to_string(),
                        )
                    } else {
                        (
                            StatusCode::OK,
                            response(json!([{"id":0,"en":"Headline","zhCn":"标题","zhTw":"標題"}])),
                        )
                    }
                },
            ),
        )
        .with_state(bodies.clone());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let archive = archive().await;
    let audit = Arc::new(LlmUsageRepository::new(archive.pool.clone(), 30));
    let config = TranslationConfig {
        enabled: true,
        base_url: format!("http://{address}"),
        api_key: "test-only-key".into(),
        model: "test-model".into(),
        ..Default::default()
    };
    let translator = OpenAiNewsTitleTranslator::new(&config, None, Some(audit)).unwrap();
    assert_eq!(
        translator
            .translate(&["Original headline".into()])
            .await
            .unwrap()[0]["en"],
        "Headline"
    );
    let saved = bodies.lock().await;
    assert_eq!(saved.len(), 2);
    assert!(saved[1].get("response_format").is_none());
    let input: Value =
        serde_json::from_str(saved[0]["messages"][1]["content"].as_str().unwrap()).unwrap();
    assert_eq!(
        input,
        json!({"titles":[{"id":0,"title":"Original headline"}]})
    );
    let rows = sqlx::query("SELECT kind,status,total_tokens FROM llm_usage ORDER BY id")
        .fetch_all(&archive.pool)
        .await
        .unwrap();
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0].get::<String, _>("kind"), "news_title_translation");
    assert_eq!(rows[1].get::<i64, _>("total_tokens"), 50);
    server.abort();
}
#[tokio::test]
async fn interrupted_final_attempt_is_marked_unavailable_without_rebilling() {
    let archive = archive().await;
    let now = Utc::now();
    let row = article("Original", "https://www.federalreserve.gov/a.htm", now);
    archive.ingest(&[row.clone()], now).await.unwrap();
    sqlx::query("UPDATE news_articles SET title_translation_attempts=3,title_translation_status='pending',title_translation_claim='interrupted',title_translation_attempted_at=?")
        .bind(now.timestamp()-301).execute(&archive.pool).await.unwrap();
    archive.prune(now).await.unwrap();
    let status: String = sqlx::query_scalar("SELECT title_translation_status FROM news_articles")
        .fetch_one(&archive.pool)
        .await
        .unwrap();
    assert_eq!(status, "unavailable");
    let fake = Arc::new(Fake::default());
    assert_eq!(
        NewsTitleWorker::new(archive, fake.clone())
            .run_once(now)
            .await
            .unwrap(),
        0
    );
    assert_eq!(fake.calls.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn http_errors_are_redacted_and_api_key_headers_do_not_add_bearer_or_retry_429() {
    use axum::{
        Json,
        http::{HeaderMap, StatusCode},
        routing::post,
    };
    let calls = Arc::new(AtomicUsize::new(0));
    let observed = calls.clone();
    let app = axum::Router::new().route(
        "/chat/completions",
        post(move |headers: HeaderMap, Json(_): Json<Value>| {
            let observed = observed.clone();
            async move {
                observed.fetch_add(1, Ordering::SeqCst);
                assert!(headers.get("authorization").is_none());
                assert_eq!(headers.get("api-key").unwrap(), "test-header-key");
                (
                    StatusCode::TOO_MANY_REQUESTS,
                    "untrusted-provider-error-echo",
                )
            }
        }),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let server = tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let archive = archive().await;
    let config = TranslationConfig {
        base_url: format!("http://{address}"),
        api_key: "test-only-key".into(),
        extra_headers: BTreeMap::from([("api-key".into(), "test-header-key".into())]),
        ..Default::default()
    };
    let translator = OpenAiNewsTitleTranslator::new(
        &config,
        None,
        Some(Arc::new(LlmUsageRepository::new(archive.pool.clone(), 30))),
    )
    .unwrap();
    let error = translator.translate(&["Title".into()]).await.unwrap_err();
    assert!(!error.to_string().contains("untrusted-provider-error-echo"));
    assert_eq!(calls.load(Ordering::SeqCst), 1);
    let error: String = sqlx::query_scalar("SELECT error FROM llm_usage")
        .fetch_one(&archive.pool)
        .await
        .unwrap();
    assert_eq!(error, "news_translation_failed");
    server.abort();
}

#[test]
fn server_news_translation_is_enabled_by_default_but_requires_server_translation_config() {
    let config: TranslationConfig = toml::from_str("").unwrap();
    assert!(config.news_titles_enabled);
    assert!(!config.enabled);
    assert!(OpenAiNewsTitleTranslator::new(&config, None, None).is_err());
    let config: TranslationConfig = toml::from_str("news_titles_enabled = false").unwrap();
    assert!(!config.news_titles_enabled);
}
