use super::*;
use crate::{
    news::NewsService,
    news_page::{PageReader, PageText},
};
use sqlx::{Executor, sqlite::SqlitePoolOptions};

async fn pool() -> SqlitePool {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    pool
}
fn row(url: &str, time: DateTime<Utc>) -> NewsArticle {
    NewsArticle {
        title_translations: Default::default(),
        archive_id: article_id(url),
        url: url.into(),
        title: "Policy announcement".into(),
        source: "Federal Reserve".into(),
        feed_id: "fed".into(),
        published_at: time,
        summary: Some("Public summary".into()),
    }
}
fn body() -> PageText {
    PageText {
        url: "https://www.bbc.com/news/resolved".into(),
        text: "Public text. ".repeat(30),
        truncated: false,
    }
}
fn source() -> NewsSource {
    NewsSource {
        id: "fed".into(),
        name: "Federal Reserve".into(),
        url: "https://www.federalreserve.gov/feeds/press_all.xml".into(),
    }
}

#[tokio::test]
async fn canonical_url_is_unique_and_repeated_ingestion_keeps_body_and_original_link() {
    let archive = NewsArchive::new(pool().await);
    let now = Utc::now();
    let url = "https://www.federalreserve.gov/newsevents/report.htm";
    archive
        .ingest(&[row(&format!("{url}#body"), now)], now)
        .await
        .unwrap();
    let id = article_id(url);
    assert!(archive.claim(&id, now).await.unwrap());
    archive.save_body(&id, &body(), now).await.unwrap();
    let mut updated = row(url, now + chrono::Duration::hours(1));
    updated.title = "Updated title".into();
    archive
        .ingest(&[updated], now + chrono::Duration::hours(1))
        .await
        .unwrap();
    let saved = archive
        .article(&id, now + chrono::Duration::hours(1))
        .await
        .unwrap();
    assert_eq!(saved.url, url);
    assert_eq!(saved.title, "Updated title");
    assert_eq!(saved.text, Some(body().text));
    assert_eq!(saved.body_status, "available");
    assert_eq!(saved.published_at.timestamp(), now.timestamp());
    assert_eq!(
        archive
            .list(0, now + chrono::Duration::hours(1))
            .await
            .unwrap()
            .0
            .len(),
        1
    );
    assert!(
        archive
            .pending(now + chrono::Duration::days(1))
            .await
            .unwrap()
            .is_empty()
    );
}
#[tokio::test]
async fn publication_time_controls_retention_and_reads_hide_expired_rows_before_cleanup() {
    let archive = NewsArchive::new(pool().await);
    let now = Utc::now();
    let near = row("https://www.bbc.com/near", now - chrono::Duration::days(29));
    let old = row("https://www.bbc.com/old", now - chrono::Duration::days(31));
    let future = row(
        "https://www.bbc.com/future",
        now + chrono::Duration::hours(1),
    );
    archive
        .ingest(&[near.clone(), old, future], now)
        .await
        .unwrap();
    archive
        .save_body(&near.archive_id, &body(), now)
        .await
        .unwrap();
    assert_eq!(archive.list(0, now).await.unwrap().0.len(), 1);
    let later = now + chrono::Duration::days(2);
    assert!(archive.list(0, later).await.unwrap().0.is_empty());
    assert!(matches!(
        archive.article(&near.archive_id, later).await,
        Err(AppError::NotFound)
    ));
    assert!(archive.pending(later).await.unwrap().is_empty());
    assert_eq!(archive.prune(later).await.unwrap(), 1);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM news_articles")
            .fetch_one(&archive.pool)
            .await
            .unwrap(),
        0
    );
}
#[tokio::test]
async fn body_failure_retries_have_persistent_cooldown_and_never_fake_summary_as_text() {
    let pool = pool().await;
    let archive = NewsArchive::new(pool.clone());
    let now = Utc::now();
    let r = row("https://www.bbc.com/failing", now);
    archive.ingest(&[r.clone()], now).await.unwrap();
    for i in 0..3 {
        let at = now + chrono::Duration::hours(i);
        assert_eq!(archive.pending(at).await.unwrap().len(), 1);
        assert!(archive.claim(&r.archive_id, at).await.unwrap());
        assert!(!archive.claim(&r.archive_id, at).await.unwrap());
        archive.fail_body(&r.archive_id, "transport").await.unwrap();
        let restarted = NewsArchive::new(pool.clone());
        assert!(
            restarted
                .pending(at + chrono::Duration::minutes(30))
                .await
                .unwrap()
                .is_empty()
        );
    }
    assert!(
        archive
            .pending(now + chrono::Duration::days(1))
            .await
            .unwrap()
            .is_empty()
    );
    let failed = archive
        .article(&r.archive_id, now + chrono::Duration::days(1))
        .await
        .unwrap();
    assert_eq!(failed.body_status, "unavailable");
    assert!(failed.text.is_none());
    assert!(failed.fetched_at.is_none());
    assert_eq!(failed.summary, Some("Public summary".into()));
}
#[tokio::test]
async fn concurrent_body_claims_are_atomic() {
    let archive = NewsArchive::new(pool().await);
    let now = Utc::now();
    let r = row("https://www.bbc.com/claim", now);
    archive.ingest(&[r.clone()], now).await.unwrap();
    let (a, b) = tokio::join!(
        archive.claim(&r.archive_id, now),
        archive.claim(&r.archive_id, now)
    );
    assert_ne!(a.unwrap(), b.unwrap());
}
#[tokio::test]
async fn pagination_is_stable_bounded_and_archive_reads_do_not_modify_the_queue() {
    let archive = NewsArchive::new(pool().await);
    let now = Utc::now();
    let rows: Vec<_> = (0..105)
        .map(|i| {
            row(
                &format!("https://www.bbc.com/report/{i}"),
                now - chrono::Duration::seconds(i),
            )
        })
        .collect();
    for batch in rows.chunks(50) {
        archive.ingest(batch, now).await.unwrap();
    }
    let (first, next) = archive.list(0, now).await.unwrap();
    assert_eq!(first.len(), 100);
    assert_eq!(next, Some(100));
    let (second, next) = archive.list(100, now).await.unwrap();
    assert_eq!(second.len(), 5);
    assert_eq!(next, None);
    assert!(first.iter().all(|a| !second.iter().any(|b| a.url == b.url)));
    assert!(archive.list(-1, now).await.is_err());
    assert!(archive.list(100001, now).await.is_err());
    assert_eq!(archive.pending(now).await.unwrap().len(), 12);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT SUM(body_attempts) FROM news_articles")
            .fetch_one(&archive.pool)
            .await
            .unwrap(),
        0
    );
}
#[tokio::test]
async fn process_restart_restores_archive_and_marks_saved_source_health_stale() {
    let path = std::env::temp_dir().join(format!(
        "macro-news-{}-{}.sqlite",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let options = sqlx::sqlite::SqliteConnectOptions::new()
        .filename(&path)
        .create_if_missing(true);
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(options.clone())
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    let now = Utc::now();
    let article = row(
        "https://www.federalreserve.gov/newsevents/saved.htm",
        now - chrono::Duration::days(5),
    );
    let archive = NewsArchive::new(pool.clone());
    archive.record_source(&source(), true, now).await.unwrap();
    archive.ingest(&[article.clone()], now).await.unwrap();
    archive
        .save_body(&article.archive_id, &body(), now)
        .await
        .unwrap();
    pool.close().await;
    let reopened = SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(options)
        .await
        .unwrap();
    let service =
        NewsService::with_sources(crate::news::http_client(None).unwrap(), vec![source()])
            .with_archive(reopened.clone(), PageReader::new(None).unwrap());
    let feed = service.latest().await.unwrap();
    assert!(feed.stale);
    assert_eq!(feed.articles.len(), 1);
    assert_eq!(feed.articles[0].url, article.url);
    assert_eq!(
        service
            .archived_article(&article.archive_id)
            .await
            .unwrap()
            .text,
        Some(body().text)
    );
    reopened.close().await;
    drop(service);
    std::fs::remove_file(&path).unwrap();
}
#[tokio::test]
async fn interrupted_last_attempt_is_recovered_as_unavailable_not_permanently_pending() {
    let archive = NewsArchive::new(pool().await);
    let now = Utc::now();
    let r = row("https://www.bbc.com/interrupted", now);
    archive.ingest(&[r.clone()], now).await.unwrap();
    sqlx::query("UPDATE news_articles SET body_attempts=3,body_attempted_at=?,body_status='pending' WHERE id=?")
        .bind(now.timestamp()-301).bind(&r.archive_id).execute(&archive.pool).await.unwrap();
    archive.prune(now).await.unwrap();
    assert_eq!(
        archive
            .article(&r.archive_id, now)
            .await
            .unwrap()
            .body_status,
        "unavailable"
    );
}
#[tokio::test]
async fn scheduled_metadata_creates_durable_jobs_and_restricted_body_failure_never_hides_news() {
    let pool = pool().await;
    let raw = include_str!("../tests/fixtures/news/rss.xml").replace(
        "Wed, 04 Nov 2026 08:45:00 GMT",
        &(Utc::now() - chrono::Duration::minutes(1)).to_rfc2822(),
    );
    let router = axum::Router::new().route(
        "/rss",
        axum::routing::get(move || {
            let raw = raw.clone();
            async move { raw }
        }),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/rss", listener.local_addr().unwrap());
    let server = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    let service = NewsService::with_sources(
        crate::news::http_client(None).unwrap(),
        vec![NewsSource {
            id: "fixture".into(),
            name: "Fixture".into(),
            url,
        }],
    )
    .with_archive(pool.clone(), PageReader::new(None).unwrap());
    service.refresh().await;
    let metadata = service.latest().await.unwrap();
    assert_eq!(metadata.articles.len(), 1);
    let id = &metadata.articles[0].archive_id;
    assert_eq!(
        service.archived_article(id).await.unwrap().body_status,
        "pending"
    );
    // Fixture URLs are deliberately outside the publisher allowlist: no external request occurs.
    service.archive_bodies().await.unwrap();
    service.archive_bodies().await.unwrap();
    let saved = service.archived_article(id).await.unwrap();
    assert_eq!(saved.body_status, "unavailable");
    assert!(saved.text.is_none());
    assert!(saved.summary.is_some());
    assert_eq!(service.latest().await.unwrap().articles.len(), 1);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT body_attempts FROM news_articles WHERE id=?")
            .bind(id)
            .fetch_one(&pool)
            .await
            .unwrap(),
        1
    );
    server.abort();
}

#[tokio::test]
async fn migration_026_applies_to_the_previous_schema_without_deleting_earnings_or_other_data() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    for migration in sqlx::migrate!("./migrations")
        .iter()
        .filter(|m| m.version < 26)
    {
        pool.execute(migration.sql.as_ref()).await.unwrap();
    }
    sqlx::query("INSERT INTO earnings_calendar(date,payload) VALUES('2026-11-05','preserved')")
        .execute(&pool)
        .await
        .unwrap();
    pool.execute(include_str!("../migrations/026_news_archive.sql"))
        .await
        .unwrap();
    assert_eq!(
        sqlx::query_scalar::<_, String>(
            "SELECT payload FROM earnings_calendar WHERE date='2026-11-05'"
        )
        .fetch_one(&pool)
        .await
        .unwrap(),
        "preserved"
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM news_articles")
            .fetch_one(&pool)
            .await
            .unwrap(),
        0
    );
}
