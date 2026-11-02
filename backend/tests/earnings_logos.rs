use axum::{
    Router,
    body::Body,
    extract::State,
    http::{Response, StatusCode},
    routing::get,
};
use market_event_analyzer::earnings_logo::CompanyLogoService;
use serde_json::{Value, json};
use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};
use tokio::sync::Mutex;

#[path = "support/logo_cache.rs"]
mod logo_cache;
use logo_cache::CacheDir;

const PNG: &[u8] = include_bytes!("../../android/app/src/test/resources/earnings/logo.png");
#[derive(Clone)]
struct Fixture {
    calls: Arc<AtomicUsize>,
    reply: Arc<Mutex<(StatusCode, &'static str, Vec<u8>)>>,
}
struct Server {
    url: String,
    state: Fixture,
    task: tokio::task::JoinHandle<()>,
    cache_dir: CacheDir,
}
impl Drop for Server {
    fn drop(&mut self) {
        self.task.abort();
    }
}
async fn image(State(f): State<Fixture>) -> Response<Body> {
    f.calls.fetch_add(1, Ordering::SeqCst);
    let (status, mime, body) = f.reply.lock().await.clone();
    Response::builder()
        .status(status)
        .header("content-type", mime)
        .header("retry-after", "900")
        .body(Body::from(body))
        .unwrap()
}
async fn server(status: StatusCode, mime: &'static str, bytes: Vec<u8>) -> Server {
    let state = Fixture {
        calls: Arc::new(AtomicUsize::new(0)),
        reply: Arc::new(Mutex::new((status, mime, bytes))),
    };
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}/symbol", listener.local_addr().unwrap());
    let router = Router::new()
        .route("/symbol/{symbol}", get(image))
        .with_state(state.clone());
    let task = tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    Server {
        url,
        state,
        task,
        cache_dir: CacheDir::new(),
    }
}
fn service(server: &Server) -> CompanyLogoService {
    CompanyLogoService::new(reqwest::Client::builder().no_proxy().build().unwrap())
        .with_base(server.url.clone())
        .with_cache_dir(server.cache_dir.path())
}
fn metadata(server: &Server, symbol: &str) -> Value {
    serde_json::from_slice(
        &std::fs::read(server.cache_dir.path().join(format!("logo-{symbol}.json"))).unwrap(),
    )
    .unwrap()
}
fn expire(server: &Server, symbol: &str) {
    let mut entry = metadata(server, symbol);
    entry["expires_at"] = json!(0);
    std::fs::write(
        server.cache_dir.path().join(format!("logo-{symbol}.json")),
        serde_json::to_vec(&entry).unwrap(),
    )
    .unwrap();
}

#[tokio::test]
async fn valid_png_requests_coalesce_and_save_raw_files_in_the_configured_directory() {
    let server = server(StatusCode::OK, "image/png", PNG.to_vec()).await;
    let logos = service(&server);
    assert!(!server.cache_dir.path().exists());
    let (a, b) = tokio::join!(logos.image("aapl"), logos.image("AAPL"));
    assert_eq!(a.unwrap().unwrap(), PNG);
    assert_eq!(b.unwrap().unwrap(), PNG);
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 1);
    assert_eq!(
        std::fs::read(server.cache_dir.path().join("logo-AAPL.png")).unwrap(),
        PNG
    );
    assert!(metadata(&server, "AAPL")["has_image"].as_bool().unwrap());
    for entry in std::fs::read_dir(server.cache_dir.path()).unwrap() {
        assert_ne!(entry.unwrap().path().extension().unwrap(), "tmp");
    }
}

#[tokio::test]
async fn missing_images_are_negative_cached_without_a_fake_png_file() {
    let server = server(StatusCode::NOT_FOUND, "text/html", b"missing".to_vec()).await;
    let logos = service(&server);
    assert!(logos.image("UNKNOWN").await.unwrap().is_none());
    assert!(logos.image("UNKNOWN").await.unwrap().is_none());
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 1);
    assert!(!server.cache_dir.path().join("logo-UNKNOWN.png").exists());
    assert_eq!(metadata(&server, "UNKNOWN")["has_image"], false);
}

#[tokio::test]
async fn images_negative_results_and_cooldown_survive_service_restart() {
    let server = server(StatusCode::OK, "image/png", PNG.to_vec()).await;
    let logos = service(&server);
    assert_eq!(logos.image("AAPL").await.unwrap().unwrap(), PNG);
    *server.state.reply.lock().await = (StatusCode::NOT_FOUND, "text/html", vec![]);
    assert!(logos.image("MISSING").await.unwrap().is_none());
    *server.state.reply.lock().await = (StatusCode::TOO_MANY_REQUESTS, "text/plain", vec![]);
    assert!(logos.image("LIMITED").await.unwrap().is_none());
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 3);
    drop(logos);

    let logos = service(&server);
    assert_eq!(logos.image("aapl").await.unwrap().unwrap(), PNG);
    assert!(logos.image("MISSING").await.unwrap().is_none());
    assert!(logos.image("MSFT").await.unwrap().is_none());
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 3);
    std::fs::write(
        server.cache_dir.path().join("cooldown.json"),
        br#"{"blocked_until":0}"#,
    )
    .unwrap();
    *server.state.reply.lock().await = (StatusCode::OK, "image/png", PNG.to_vec());
    assert!(logos.image("MISSING").await.unwrap().is_none());
    assert_eq!(logos.image("MSFT").await.unwrap().unwrap(), PNG);
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 4);
}

#[tokio::test]
async fn expired_success_refreshes_and_failed_refresh_keeps_the_png_file() {
    let server = server(StatusCode::OK, "image/png", PNG.to_vec()).await;
    let logos = service(&server);
    assert_eq!(logos.image("AAPL").await.unwrap().unwrap(), PNG);
    let ttl =
        metadata(&server, "AAPL")["expires_at"].as_i64().unwrap() - chrono::Utc::now().timestamp();
    assert!((86395..=86400).contains(&ttl));
    expire(&server, "AAPL");
    *server.state.reply.lock().await = (StatusCode::BAD_GATEWAY, "text/html", vec![]);
    assert_eq!(logos.image("AAPL").await.unwrap().unwrap(), PNG);
    assert_eq!(logos.image("AAPL").await.unwrap().unwrap(), PNG);
    assert_eq!(
        std::fs::read(server.cache_dir.path().join("logo-AAPL.png")).unwrap(),
        PNG
    );
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 2);
    let ttl =
        metadata(&server, "AAPL")["expires_at"].as_i64().unwrap() - chrono::Utc::now().timestamp();
    assert!((295..=300).contains(&ttl));
    expire(&server, "AAPL");
    let mut updated_png = PNG.to_vec();
    updated_png.push(0); // Distinct fixture that still passes the bounded PNG validation.
    *server.state.reply.lock().await = (StatusCode::OK, "image/png", updated_png.clone());
    assert_eq!(logos.image("AAPL").await.unwrap().unwrap(), updated_png);
    assert_eq!(
        std::fs::read(server.cache_dir.path().join("logo-AAPL.png")).unwrap(),
        updated_png
    );
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 3);
}

#[tokio::test]
async fn expired_negative_entries_retry_and_hits_read_the_file_not_memory() {
    let server = server(StatusCode::NOT_FOUND, "text/html", vec![]).await;
    let logos = service(&server);
    assert!(logos.image("AAPL").await.unwrap().is_none());
    expire(&server, "AAPL");
    *server.state.reply.lock().await = (StatusCode::OK, "image/png", PNG.to_vec());
    assert_eq!(logos.image("AAPL").await.unwrap().unwrap(), PNG);
    let mut changed = PNG.to_vec();
    changed.push(0);
    let path = server.cache_dir.path().join("logo-AAPL.png");
    std::fs::write(&path, &changed).unwrap();
    assert_eq!(logos.image("AAPL").await.unwrap().unwrap(), changed);
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 2);
    // Oversized or corrupt disk bytes must never be served or used as stale fallback.
    for bytes in [vec![0u8; 256 * 1024 + 1], vec![0u8; 24]] {
        std::fs::write(&path, bytes).unwrap();
        std::fs::write(
            server.cache_dir.path().join("logo-AAPL.json"),
            json!({"expires_at":0,"last_accessed_at":0,"has_image":true}).to_string(),
        )
        .unwrap();
        *server.state.reply.lock().await = (StatusCode::BAD_GATEWAY, "text/html", vec![]);
        assert!(logos.image("AAPL").await.unwrap().is_none());
        assert!(!path.exists());
    }
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 4);
}

#[tokio::test]
async fn missing_or_corrupt_metadata_recovers_and_a_missing_png_is_redownloaded() {
    let server = server(StatusCode::OK, "image/png", PNG.to_vec()).await;
    let logos = service(&server);
    assert_eq!(logos.image("AAPL").await.unwrap().unwrap(), PNG);
    let metadata_path = server.cache_dir.path().join("logo-AAPL.json");
    std::fs::remove_file(&metadata_path).unwrap();
    *server.state.reply.lock().await = (StatusCode::BAD_GATEWAY, "text/html", vec![]);
    assert_eq!(logos.image("AAPL").await.unwrap().unwrap(), PNG);
    for broken in [b"{".to_vec(), vec![0; 1025]] {
        std::fs::write(&metadata_path, broken).unwrap();
        assert_eq!(logos.image("AAPL").await.unwrap().unwrap(), PNG);
    }
    std::fs::remove_file(server.cache_dir.path().join("logo-AAPL.png")).unwrap();
    *server.state.reply.lock().await = (StatusCode::OK, "image/png", PNG.to_vec());
    assert_eq!(logos.image("AAPL").await.unwrap().unwrap(), PNG);
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 5);
}

#[tokio::test]
async fn disk_cache_evicts_lru_files_at_128_items_without_removing_unrelated_files() {
    let server = server(StatusCode::OK, "image/png", PNG.to_vec()).await;
    let dir = server.cache_dir.path();
    std::fs::create_dir_all(dir).unwrap();
    for index in 0..128 {
        std::fs::write(dir.join(format!("logo-S{index}.png")), PNG).unwrap();
        std::fs::write(
            dir.join(format!("logo-S{index}.json")),
            json!({"expires_at": chrono::Utc::now().timestamp() + 86400,
                   "last_accessed_at": index, "has_image": true})
            .to_string(),
        )
        .unwrap();
    }
    std::fs::write(dir.join("keep.txt"), "unrelated").unwrap();
    std::fs::write(dir.join("cooldown.json"), br#"{"blocked_until":0}"#).unwrap();
    let logos = service(&server);
    assert_eq!(logos.image("S0").await.unwrap().unwrap(), PNG);
    assert_eq!(logos.image("AAPL").await.unwrap().unwrap(), PNG);
    let pngs = std::fs::read_dir(dir)
        .unwrap()
        .filter(|entry| {
            entry
                .as_ref()
                .unwrap()
                .path()
                .extension()
                .is_some_and(|extension| extension == "png")
        })
        .count();
    assert_eq!(pngs, 128);
    assert!(dir.join("logo-S0.png").exists());
    assert!(dir.join("logo-AAPL.png").exists());
    assert!(!dir.join("logo-S1.png").exists());
    assert!(!dir.join("logo-S1.json").exists());
    assert_eq!(
        std::fs::read_to_string(dir.join("keep.txt")).unwrap(),
        "unrelated"
    );
    assert!(dir.join("cooldown.json").exists());
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn invalid_symbols_cannot_escape_the_cache_directory_or_inject_urls() {
    let server = server(StatusCode::OK, "image/png", PNG.to_vec()).await;
    let logos = service(&server);
    for symbol in [
        "../aapl",
        "AAPL?secret=x",
        "https://evil",
        "%2f",
        "..",
        "A/B",
        "A\\B",
    ] {
        assert!(logos.image(symbol).await.is_err());
    }
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 0);
    assert!(!server.cache_dir.path().exists());
    for symbol in ["BRK.B", "BRK-B", "CON"] {
        assert_eq!(logos.image(symbol).await.unwrap().unwrap(), PNG);
        assert!(
            server
                .cache_dir
                .path()
                .join(format!("logo-{symbol}.png"))
                .exists()
        );
    }
}

#[tokio::test]
async fn invalid_cache_directory_reports_io_errors_without_fetching() {
    let server = server(StatusCode::OK, "image/png", PNG.to_vec()).await;
    std::fs::write(server.cache_dir.path(), "not a directory").unwrap();
    assert!(service(&server).image("AAPL").await.is_err());
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 0);
    std::fs::remove_file(server.cache_dir.path()).unwrap();
}

#[tokio::test]
async fn rate_limits_apply_across_different_company_symbols() {
    for code in [StatusCode::TOO_MANY_REQUESTS, StatusCode::FORBIDDEN] {
        let server = server(code, "text/plain", vec![]).await;
        let logos = service(&server);
        assert!(logos.image("AAPL").await.unwrap().is_none());
        assert!(logos.image("MSFT").await.unwrap().is_none());
        assert_eq!(server.state.calls.load(Ordering::SeqCst), 1);
    }
}

#[tokio::test]
async fn html_oversize_and_extreme_dimensions_are_not_saved_or_forwarded_as_images() {
    let mut giant = PNG.to_vec();
    giant[16..20].copy_from_slice(&10000u32.to_be_bytes());
    for (mime, body) in [
        ("text/html", PNG.to_vec()),
        ("image/png", b"<html>blocked</html>".to_vec()),
        ("image/png", vec![0; 256 * 1024 + 1]),
        ("image/png", giant),
    ] {
        let server = server(StatusCode::OK, mime, body).await;
        assert!(service(&server).image("AAPL").await.unwrap().is_none());
        assert!(!server.cache_dir.path().join("logo-AAPL.png").exists());
    }
}
