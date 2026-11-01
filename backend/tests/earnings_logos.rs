use axum::{
    Router,
    body::Body,
    extract::State,
    http::{Response, StatusCode},
    routing::get,
};
use market_event_analyzer::earnings_logo::CompanyLogoService;
use std::sync::{
    Arc,
    atomic::{AtomicUsize, Ordering},
};
use tokio::sync::Mutex;

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
    Server { url, state, task }
}
fn service(server: &Server) -> CompanyLogoService {
    CompanyLogoService::new(reqwest::Client::builder().no_proxy().build().unwrap())
        .with_base(server.url.clone())
}

#[tokio::test]
async fn valid_png_requests_coalesce_and_cache_without_touching_calendar_data() {
    let server = server(StatusCode::OK, "image/png", PNG.to_vec()).await;
    let service = service(&server);
    let (a, b) = tokio::join!(service.image("aapl"), service.image("AAPL"));
    assert_eq!(a.unwrap().unwrap(), PNG);
    assert_eq!(b.unwrap().unwrap(), PNG);
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn missing_images_are_negative_cached() {
    let server = server(StatusCode::NOT_FOUND, "text/html", b"missing".to_vec()).await;
    let service = service(&server);
    assert!(service.image("UNKNOWN").await.unwrap().is_none());
    assert!(service.image("UNKNOWN").await.unwrap().is_none());
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn invalid_symbols_cannot_inject_urls_or_make_upstream_requests() {
    let server = server(StatusCode::OK, "image/png", PNG.to_vec()).await;
    let service = service(&server);
    for symbol in ["../aapl", "AAPL?secret=x", "https://evil", "%2f"] {
        assert!(service.image(symbol).await.is_err());
    }
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 0);
}

#[tokio::test]
async fn rate_limits_apply_across_different_company_symbols() {
    let server = server(StatusCode::TOO_MANY_REQUESTS, "text/plain", vec![]).await;
    let service = service(&server);
    assert!(service.image("AAPL").await.unwrap().is_none());
    assert!(service.image("MSFT").await.unwrap().is_none());
    assert_eq!(server.state.calls.load(Ordering::SeqCst), 1);
}

#[tokio::test]
async fn html_oversize_and_extreme_dimensions_are_not_forwarded_as_images() {
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
    }
}
