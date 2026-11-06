//! Scheduled public news metadata and thirty-day text archive. API reads never contact publishers.
use crate::{error::AppError, news_archive::NewsArchive, news_page::PageReader};
use chrono::{DateTime, Utc};
use futures_util::future::join_all;
use futures_util::{StreamExt, stream};
use reqwest::{Client, Url};
use serde::Serialize;
use std::{sync::Arc, time::Duration};
use tokio::{
    sync::{Mutex, RwLock},
    time::Instant,
};

const MAX_BYTES: usize = 512 * 1024;
const MAX_ITEMS: usize = 100;
const TTL: Duration = Duration::from_secs(60);
const FAILURE_TTL: Duration = Duration::from_secs(30);
const STALE_TTL: Duration = Duration::from_secs(86400);

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NewsArticle {
    pub archive_id: String,
    pub title: String,
    pub title_translations: crate::news_translation::Titles,
    pub url: String,
    pub source: String,
    pub feed_id: String,
    pub published_at: DateTime<Utc>,
    pub summary: Option<String>,
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NewsSourceStatus {
    pub id: String,
    pub name: String,
    pub status: &'static str,
    pub updated_at: Option<DateTime<Utc>>,
}
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NewsFeed {
    pub updated_at: DateTime<Utc>,
    pub stale: bool,
    pub partial: bool,
    pub sources: Vec<NewsSourceStatus>,
    pub articles: Vec<NewsArticle>,
    pub next_offset: Option<i64>,
}
#[derive(Clone)]
pub struct NewsSource {
    pub id: String,
    pub name: String,
    pub url: String,
}
impl NewsSource {
    pub fn defaults() -> Vec<Self> {
        [
            (
                "fed",
                "Federal Reserve",
                "https://www.federalreserve.gov/feeds/press_all.xml",
            ),
            (
                "bls",
                "U.S. Bureau of Labor Statistics",
                "https://www.bls.gov/feed/bls_latest.rss",
            ),
            (
                "bea",
                "U.S. Bureau of Economic Analysis",
                "https://apps.bea.gov/rss/rss.xml",
            ),
            (
                "marketwatch",
                "MarketWatch",
                "https://feeds.content.dowjones.io/public/rss/mw_topstories",
            ),
            (
                "bbc",
                "BBC Business",
                "https://feeds.bbci.co.uk/news/business/rss.xml",
            ),
            (
                "chinanews",
                "中新网财经",
                "https://www.chinanews.com.cn/rss/finance.xml",
            ),
        ]
        .into_iter()
        .map(|(id, name, url)| Self {
            id: id.into(),
            name: name.into(),
            url: url.into(),
        })
        .collect()
    }
}
#[derive(Clone)]
struct Snapshot {
    articles: Vec<NewsArticle>,
    at: DateTime<Utc>,
    fetched: Instant,
}
#[derive(Default)]
struct Cache {
    snapshot: Option<Snapshot>,
    attempted: Option<Instant>,
    failed: bool,
}
struct SourceState {
    source: NewsSource,
    refresh: Mutex<()>,
    cache: RwLock<Cache>,
}
pub struct NewsService {
    client: Client,
    sources: Vec<Arc<SourceState>>,
    archive: Option<Arc<NewsArchive>>,
    pages: Option<Arc<PageReader>>,
    body_guard: Mutex<()>,
}

/// Explicit news-only proxy; no cookies, credentials, redirects or environment proxy.
pub fn http_client(proxy: Option<&str>) -> Result<Client, AppError> {
    let mut builder = Client::builder()
        .no_proxy()
        .user_agent("MacroResearch/1.0 (public news reader)")
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(8))
        .redirect(reqwest::redirect::Policy::none());
    if let Some(proxy) = proxy {
        builder = builder.proxy(reqwest::Proxy::all(proxy)?);
    }
    Ok(builder.build()?)
}
impl NewsService {
    pub fn new(client: Client) -> Self {
        Self::with_sources(client, NewsSource::defaults())
    }
    pub fn with_sources(client: Client, sources: Vec<NewsSource>) -> Self {
        Self {
            client,
            archive: None,
            pages: None,
            body_guard: Mutex::new(()),
            sources: sources
                .into_iter()
                .take(6)
                .map(|source| {
                    Arc::new(SourceState {
                        source,
                        refresh: Mutex::new(()),
                        cache: RwLock::new(Cache::default()),
                    })
                })
                .collect(),
        }
    }
    pub fn with_archive(mut self, pool: sqlx::SqlitePool, pages: PageReader) -> Self {
        self.archive = Some(Arc::new(NewsArchive::new(pool)));
        self.pages = Some(Arc::new(pages));
        self
    }
    pub async fn archived_article(
        &self,
        id: &str,
    ) -> Result<crate::news_archive::ArchivedArticle, AppError> {
        self.archive
            .as_ref()
            .ok_or(AppError::NotFound)?
            .article(id, Utc::now())
            .await
    }
    pub async fn archive_bodies(&self) -> Result<(), AppError> {
        let (Some(archive), Some(pages)) = (&self.archive, &self.pages) else {
            return Ok(());
        };
        let Ok(_guard) = self.body_guard.try_lock() else {
            return Ok(());
        };
        archive.prune(Utc::now()).await?;
        let jobs = archive.pending(Utc::now()).await?;
        stream::iter(jobs)
            .for_each_concurrent(2, |job| async move {
                match archive.claim(&job.id, Utc::now()).await {
                    Ok(true) => match pages.read(&job.url).await {
                        Ok(body) => {
                            if archive.save_body(&job.id, &body, Utc::now()).await.is_err() {
                                tracing::warn!("news body persistence failed");
                            }
                        }
                        Err(code) => {
                            if archive.fail_body(&job.id, code).await.is_err() {
                                tracing::warn!("news body failure status persistence failed");
                            }
                        }
                    },
                    Ok(false) => {}
                    Err(_) => tracing::warn!("news body claim failed"),
                }
            })
            .await;
        Ok(())
    }
    pub async fn refresh(&self) {
        join_all(self.sources.iter().map(|state| self.refresh_source(state))).await;
    }
    async fn refresh_source(&self, state: &SourceState) {
        let _single_flight = state.refresh.lock().await;
        {
            let cache = state.cache.read().await;
            // Deduplicate concurrent rounds; keep this below the polling interval so an
            // eight-second fetch does not accidentally skip every second one-minute tick.
            if cache.attempted.is_some_and(|at| at.elapsed() < FAILURE_TTL) {
                return;
            }
        }
        // Do not hold the cache lock across network IO: API readers stay fast during outages.
        let result = tokio::time::timeout(Duration::from_secs(8), self.fetch(&state.source)).await;
        let mut archived = None;
        let mut cache = state.cache.write().await;
        cache.attempted = Some(Instant::now());
        if let Ok(Ok(articles)) = result {
            if cache.failed {
                tracing::info!(source=%state.source.id,"news source recovered");
            }
            archived = Some(articles.clone());
            cache.snapshot = Some(Snapshot {
                articles,
                at: Utc::now(),
                fetched: Instant::now(),
            });
            cache.failed = false;
        } else {
            if !cache.failed {
                tracing::warn!(source=%state.source.id,"news source refresh failed");
            }
            cache.failed = true;
        }
        drop(cache);
        if let Some(archive) = &self.archive {
            let now = Utc::now();
            let persisted = async {
                archive
                    .record_source(&state.source, archived.is_some(), now)
                    .await?;
                if let Some(rows) = archived {
                    archive.ingest(&rows, now).await?;
                }
                Ok::<(), AppError>(())
            }
            .await;
            if persisted.is_err() {
                state.cache.write().await.failed = true;
                tracing::warn!(source=%state.source.id,"news metadata archive write failed");
            }
        }
    }
    /// Cache only. Startup/all-source expiry returns a provider error, never a fake empty success.
    pub async fn latest(&self) -> Result<NewsFeed, AppError> {
        self.page(0).await
    }
    pub async fn page(&self, offset: i64) -> Result<NewsFeed, AppError> {
        if !(0..=100_000).contains(&offset) {
            return Err(AppError::InvalidRequest("Invalid news offset".into()));
        }
        let mut sources = Vec::new();
        let mut articles = Vec::new();
        let mut updated_at = None;
        for state in &self.sources {
            let cache = state.cache.read().await;
            let snapshot = cache
                .snapshot
                .clone()
                .filter(|s| s.fetched.elapsed() <= STALE_TTL);
            let status = match snapshot.as_ref() {
                None => "unavailable",
                Some(s) if cache.failed || s.fetched.elapsed() > Duration::from_secs(120) => {
                    "stale"
                }
                Some(_) => "fresh",
            };
            let source_status = NewsSourceStatus {
                id: state.source.id.clone(),
                name: state.source.name.clone(),
                status,
                updated_at: snapshot.as_ref().map(|s| s.at),
            };
            drop(cache);
            let saved = if snapshot.is_none() {
                if let Some(archive) = &self.archive {
                    archive.saved_source(&state.source.id, Utc::now()).await?
                } else {
                    None
                }
            } else {
                None
            };
            let source_status = saved.unwrap_or(source_status);
            if let Some(at) = source_status.updated_at {
                updated_at =
                    Some(updated_at.map_or(at, |previous: DateTime<Utc>| previous.max(at)));
            }
            sources.push(source_status);
            if let Some(s) = snapshot {
                updated_at = Some(updated_at.map_or(s.at, |at: DateTime<Utc>| at.max(s.at)));
                articles.extend(s.articles.clone());
            }
        }
        let (articles, next_offset) = if let Some(archive) = &self.archive {
            archive.list(offset, Utc::now()).await?
        } else {
            (normalize_articles(articles, Utc::now()), None)
        };
        Ok(NewsFeed {
            updated_at: updated_at
                .ok_or_else(|| AppError::Provider("news_cache_not_ready".into()))?,
            partial: sources.iter().any(|s| s.status != "fresh"),
            stale: sources.iter().any(|s| s.status == "stale"),
            articles,
            next_offset,
            sources,
        })
    }
    async fn fetch(&self, source: &NewsSource) -> Result<Vec<NewsArticle>, AppError> {
        let mut response = self
            .client
            .get(&source.url)
            .header(
                "Accept",
                "application/rss+xml, application/atom+xml, application/xml, text/xml",
            )
            .send()
            .await?
            .error_for_status()?;
        if !response.status().is_success() {
            return Err(AppError::Provider("news_redirect_rejected".into()));
        }
        if response
            .content_length()
            .is_some_and(|size| size > MAX_BYTES as u64)
        {
            return Err(AppError::Provider("news_response_too_large".into()));
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await? {
            if bytes.len() + chunk.len() > MAX_BYTES {
                return Err(AppError::Provider("news_response_too_large".into()));
            }
            bytes.extend_from_slice(&chunk);
        }
        let raw = String::from_utf8(bytes)
            .map_err(|_| AppError::Provider("news_invalid_encoding".into()))?;
        parse_rss(&raw, source)
    }
}

/// Starts immediately, then polls every minute even with no clients; missed ticks are skipped.
pub async fn sync_loop(service: Arc<NewsService>) {
    run_loop(service, TTL).await;
}
async fn run_loop(service: Arc<NewsService>, interval: Duration) {
    let mut timer = tokio::time::interval(interval);
    timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        timer.tick().await;
        service.refresh().await;
    }
}

pub async fn body_loop(service: Arc<NewsService>) {
    let mut timer = tokio::time::interval(TTL);
    timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        timer.tick().await;
        if service.archive_bodies().await.is_err() {
            tracing::warn!("news archive body round failed");
        }
    }
}

pub fn safe_article_url(value: &str) -> Option<String> {
    if value.len() > 4096 {
        return None;
    }
    let mut url = Url::parse(value.trim()).ok()?;
    if url.scheme() != "https"
        || !url.username().is_empty()
        || url.password().is_some()
        || url.port_or_known_default() != Some(443)
    {
        return None;
    }
    let host = url.host_str()?.trim_end_matches('.');
    if host == "localhost"
        || host.ends_with(".localhost")
        || host.ends_with(".local")
        || host.ends_with(".internal")
        || !host.contains('.')
        || host.contains(':')
        || host.chars().all(|c| c.is_ascii_digit() || c == '.')
    {
        return None;
    }
    url.set_fragment(None);
    let normalized = url.to_string();
    (normalized.len() <= 4096).then_some(normalized)
}
fn text(value: &str, max: usize) -> String {
    scraper::Html::parse_fragment(value)
        .root_element()
        .text()
        .collect::<Vec<_>>()
        .join(" ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(max)
        .collect()
}
fn date(value: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(value.trim())
        .or_else(|_| DateTime::parse_from_rfc2822(value.trim()))
        .ok()
        .map(|d| d.with_timezone(&Utc))
}
fn child_text(node: roxmltree::Node<'_, '_>, name: &str) -> String {
    node.children()
        .find(|n| n.is_element() && n.tag_name().name() == name)
        .map(|n| {
            n.descendants()
                .filter(|c| c.is_text())
                .filter_map(|c| c.text())
                .collect::<String>()
        })
        .unwrap_or_default()
}
fn parse_rss(raw: &str, source: &NewsSource) -> Result<Vec<NewsArticle>, AppError> {
    let document = roxmltree::Document::parse_with_options(
        raw,
        roxmltree::ParsingOptions {
            allow_dtd: false,
            nodes_limit: 20_000,
            ..Default::default()
        },
    )
    .map_err(|_| AppError::Provider("news_invalid_xml".into()))?;
    let root = document.root_element();
    if !matches!(root.tag_name().name(), "rss" | "RDF" | "feed")
        || (root.tag_name().name() == "rss"
            && !root
                .children()
                .any(|n| n.is_element() && n.tag_name().name() == "channel"))
    {
        return Err(AppError::Provider("news_invalid_feed".into()));
    }
    Ok(document
        .descendants()
        .filter(|n| n.is_element() && matches!(n.tag_name().name(), "item" | "entry"))
        .take(60)
        .filter_map(|entry| {
            let title = text(&child_text(entry, "title"), 300);
            let link = entry.children().find(|n| {
                n.is_element()
                    && n.tag_name().name() == "link"
                    && n.attribute("rel").is_none_or(|r| r == "alternate")
            })?;
            let url = safe_article_url(
                link.attribute("href")
                    .unwrap_or_else(|| link.text().unwrap_or_default()),
            )?;
            let published_at = ["pubDate", "published", "updated", "date"]
                .into_iter()
                .find_map(|name| date(&child_text(entry, name)))?;
            let summary = ["description", "summary", "encoded", "content"]
                .into_iter()
                .map(|name| child_text(entry, name))
                .find(|v| !v.trim().is_empty())
                .map(|s| text(&s, 700))
                .filter(|s| !s.is_empty());
            (!title.is_empty()).then(|| NewsArticle {
                archive_id: crate::news_archive::article_id(&url),
                title_translations: Default::default(),
                title,
                url,
                source: source.name.clone(),
                feed_id: source.id.clone(),
                published_at,
                summary,
            })
        })
        .collect())
}
fn normalize_articles(mut articles: Vec<NewsArticle>, now: DateTime<Utc>) -> Vec<NewsArticle> {
    articles
        .retain(|a| a.published_at <= now && a.published_at >= now - chrono::Duration::hours(72));
    articles.sort_by(|a, b| {
        b.published_at
            .cmp(&a.published_at)
            .then_with(|| a.url.cmp(&b.url))
    });
    let mut urls = std::collections::HashSet::new();
    articles.retain(|a| urls.insert(a.url.clone()));
    articles.truncate(MAX_ITEMS);
    articles
}

#[cfg(test)]
#[path = "news_tests.rs"]
mod tests;
