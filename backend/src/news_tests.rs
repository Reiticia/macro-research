use super::*;
use axum::{Router, http::StatusCode, routing::get};
use std::sync::atomic::{AtomicBool, AtomicU16, AtomicUsize, Ordering};
use tokio::sync::Notify;
const RSS: &str = include_str!("../tests/fixtures/news/rss.xml");
const ATOM: &str = include_str!("../tests/fixtures/news/atom.xml");
fn source() -> NewsSource {
    NewsSource {
        id: "fixture".into(),
        name: "Fixture Finance".into(),
        url: "https://feeds.example.test/rss".into(),
    }
}

#[test]
fn feeds_decode_dates_and_markup_filter_old_future_unsafe_and_duplicate_rows() {
    let now = DateTime::parse_from_rfc3339("2026-11-04T10:00:00Z")
        .unwrap()
        .with_timezone(&Utc);
    let mut rows = parse_rss(RSS, &source()).unwrap();
    rows.extend(parse_rss(ATOM, &source()).unwrap());
    rows.extend(parse_rss(RSS, &source()).unwrap());
    let rows = normalize_articles(rows, now);
    assert_eq!(
        rows.iter().map(|a| a.title.as_str()).collect::<Vec<_>>(),
        vec!["Jobs and growth", "Markets & inflation outlook"]
    );
    assert_eq!(rows[0].url, "https://news.example.test/jobs");
    assert_eq!(
        rows[1].summary.as_deref(),
        Some("Central banks review rates.")
    );
}
#[test]
fn xml_roots_dtd_credentials_local_urls_and_oversized_links_are_rejected() {
    for raw in [
        "<html><body>Denied</body></html>",
        "<rss/>",
        "<rss><channel>",
        "<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///private'>]><rss><channel>&x;</channel></rss>",
    ] {
        assert!(parse_rss(raw, &source()).is_err());
    }
    for url in [
        "http://example.test/a",
        "https://user:pass@example.test/a",
        "https://localhost./a",
        "https://127.0.0.1/a",
        "https://[::1]/a",
        "https://host.internal/a",
        "https://example.test:8443/a",
    ] {
        assert!(safe_article_url(url).is_none());
    }
    assert!(safe_article_url(&format!("https://example.test/{}", "x".repeat(4096))).is_none());
}
struct Server {
    base: String,
    task: tokio::task::JoinHandle<()>,
    status: Arc<AtomicU16>,
    calls: Arc<AtomicUsize>,
    hold: Arc<AtomicBool>,
    started: Arc<Notify>,
    release: Arc<Notify>,
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
    }
}
async fn setup() -> Server {
    let status = Arc::new(AtomicU16::new(200));
    let calls = Arc::new(AtomicUsize::new(0));
    let hold = Arc::new(AtomicBool::new(false));
    let started = Arc::new(Notify::new());
    let release = Arc::new(Notify::new());
    let (st, ct, h, n, r) = (
        status.clone(),
        calls.clone(),
        hold.clone(),
        started.clone(),
        release.clone(),
    );
    let body = RSS.replace(
        "Wed, 04 Nov 2026 08:45:00 GMT",
        &(Utc::now() - chrono::Duration::minutes(1)).to_rfc2822(),
    );
    let router = Router::new()
        .route(
            "/rss",
            get(move |headers: axum::http::HeaderMap| {
                let (st, ct, h, n, r, body) = (
                    st.clone(),
                    ct.clone(),
                    h.clone(),
                    n.clone(),
                    r.clone(),
                    body.clone(),
                );
                async move {
                    for key in [
                        "authorization",
                        "cookie",
                        "x-installation-id",
                        "x-android-id-hash",
                    ] {
                        assert!(!headers.contains_key(key));
                    }
                    ct.fetch_add(1, Ordering::SeqCst);
                    n.notify_one();
                    if h.load(Ordering::SeqCst) {
                        r.notified().await;
                    }
                    (
                        StatusCode::from_u16(st.load(Ordering::SeqCst)).unwrap(),
                        body,
                    )
                }
            }),
        )
        .route("/large", get(|| async { "x".repeat(MAX_BYTES + 1) }))
        .route(
            "/redirect",
            get(|| async { (StatusCode::FOUND, [("location", "/rss")]) }),
        );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    Server {
        base,
        task,
        status,
        calls,
        hold,
        started,
        release,
    }
}
fn service(server: &Server, path: &str) -> NewsService {
    let mut s = source();
    s.url = format!("{}{path}", server.base);
    NewsService::with_sources(http_client(None).unwrap(), vec![s])
}
async fn expire(service: &NewsService) {
    service.sources[0].cache.write().await.attempted =
        Some(Instant::now() - Duration::from_secs(61));
}
#[tokio::test]
async fn api_is_cache_only_and_refresh_rounds_are_single_flight() {
    let server = setup().await;
    let service = Arc::new(service(&server, "/rss"));
    assert!(service.latest().await.is_err());
    assert_eq!(server.calls.load(Ordering::SeqCst), 0);
    join_all((0..20).map(|_| service.refresh())).await;
    assert_eq!(server.calls.load(Ordering::SeqCst), 1);
    for _ in 0..20 {
        assert!(!service.latest().await.unwrap().stale);
    }
    assert_eq!(server.calls.load(Ordering::SeqCst), 1);
}
#[tokio::test]
async fn api_does_not_wait_for_a_slow_publisher_or_start_its_own_request() {
    let server = setup().await;
    server.hold.store(true, Ordering::SeqCst);
    let service = Arc::new(service(&server, "/rss"));
    let worker = service.clone();
    let task = tokio::spawn(async move { worker.refresh().await });
    tokio::time::timeout(Duration::from_secs(2), server.started.notified())
        .await
        .unwrap();
    assert!(
        tokio::time::timeout(Duration::from_millis(100), service.latest())
            .await
            .unwrap()
            .is_err()
    );
    server.release.notify_one();
    task.await.unwrap();
    assert_eq!(server.calls.load(Ordering::SeqCst), 1);
    assert!(!service.latest().await.unwrap().articles.is_empty());
}
#[tokio::test]
async fn failed_refresh_and_a_stalled_scheduler_do_not_advance_success_time() {
    let server = setup().await;
    let service = service(&server, "/rss");
    service.refresh().await;
    let first = service.latest().await.unwrap();
    {
        let mut cache = service.sources[0].cache.write().await;
        cache.snapshot.as_mut().unwrap().fetched = Instant::now() - Duration::from_secs(121);
    }
    assert!(service.latest().await.unwrap().stale);
    server.status.store(503, Ordering::SeqCst);
    expire(&service).await;
    service.refresh().await;
    let stale = service.latest().await.unwrap();
    assert!(stale.stale && stale.partial);
    assert_eq!(first.updated_at, stale.updated_at);
    service.refresh().await;
    assert_eq!(server.calls.load(Ordering::SeqCst), 2);
    service.sources[0]
        .cache
        .write()
        .await
        .snapshot
        .as_mut()
        .unwrap()
        .fetched = Instant::now() - Duration::from_secs(86401);
    assert!(service.latest().await.is_err());
}
#[tokio::test]
async fn failed_large_and_redirected_sources_never_become_healthy_empty_snapshots() {
    let server = setup().await;
    for path in ["/large", "/redirect", "/missing"] {
        let service = service(&server, path);
        service.refresh().await;
        assert!(service.latest().await.is_err());
    }
    assert_eq!(server.calls.load(Ordering::SeqCst), 0);
}
#[tokio::test]
async fn individual_source_failure_is_isolated() {
    let server = setup().await;
    let mut good = source();
    good.url = format!("{}/rss", server.base);
    let mut bad = source();
    bad.id = "bad".into();
    bad.url = format!("{}/missing", server.base);
    let service = NewsService::with_sources(http_client(None).unwrap(), vec![good, bad]);
    service.refresh().await;
    let feed = service.latest().await.unwrap();
    assert!(feed.partial && !feed.stale);
    assert!(!feed.articles.is_empty());
    assert_eq!(feed.sources[1].status, "unavailable");
}
#[tokio::test]
async fn scheduled_refresh_runs_immediately_and_again_without_api_triggering_fetches() {
    let server = setup().await;
    let service = Arc::new(service(&server, "/rss"));
    let worker = service.clone();
    let task = tokio::spawn(async move { run_loop(worker, Duration::from_millis(30)).await });
    tokio::time::timeout(Duration::from_secs(2), async {
        while service.latest().await.is_err() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    expire(&service).await;
    tokio::time::timeout(Duration::from_secs(2), async {
        while server.calls.load(Ordering::SeqCst) < 2 {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .unwrap();
    task.abort();
    assert_eq!(server.calls.load(Ordering::SeqCst), 2);
}
