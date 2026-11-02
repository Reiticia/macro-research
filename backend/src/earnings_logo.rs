//! Fixed-host public PNG assets with a file-backed cache, independent of calendar refresh.
use crate::{
    earnings::{content_kind, network_failure},
    error::AppError,
};
use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use std::{
    collections::BTreeSet,
    io,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
    time::Duration,
};
use tokio::{
    fs,
    io::{AsyncReadExt, AsyncWriteExt},
    sync::Mutex,
};

pub const LOGO_BASE: &str = "https://images.financialmodelingprep.com/symbol";
pub const DEFAULT_CACHE_DIR: &str = "data/earnings-logos";
pub const MAX_LOGO_BYTES: usize = 256 * 1024;
const MAX_METADATA_BYTES: usize = 1024;
const MAX_CACHE_ENTRIES: usize = 128;

pub fn normalize_symbol(symbol: &str) -> Option<String> {
    let valid = !symbol.is_empty()
        && symbol.len() <= 32
        && symbol.as_bytes()[0].is_ascii_alphanumeric()
        && symbol
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'-'));
    valid.then(|| symbol.to_ascii_uppercase())
}

pub fn valid_png(bytes: &[u8]) -> bool {
    if bytes.len() < 24
        || bytes.len() > MAX_LOGO_BYTES
        || &bytes[..8] != b"\x89PNG\r\n\x1a\n"
        || &bytes[12..16] != b"IHDR"
    {
        return false;
    }
    let width = u32::from_be_bytes(bytes[16..20].try_into().unwrap());
    let height = u32::from_be_bytes(bytes[20..24].try_into().unwrap());
    (1..=1024).contains(&width) && (1..=1024).contains(&height)
}

#[derive(Deserialize, Serialize)]
struct Metadata {
    expires_at: i64,
    last_accessed_at: i64,
    has_image: bool,
}

#[derive(Deserialize, Serialize)]
struct Cooldown {
    blocked_until: i64,
}

#[derive(Deserialize, Serialize)]
struct ProbeAttempt {
    attempted_at: i64,
}

pub struct CompanyLogoService {
    http: reqwest::Client,
    base: String,
    cache_dir: PathBuf,
    // Coalesce requests without retaining images or cache metadata in memory.
    requests: Mutex<()>,
}
impl CompanyLogoService {
    pub fn new(http: reqwest::Client) -> Self {
        Self {
            http,
            base: LOGO_BASE.into(),
            cache_dir: DEFAULT_CACHE_DIR.into(),
            requests: Mutex::new(()),
        }
    }
    pub fn with_base(mut self, base: String) -> Self {
        self.base = base;
        self
    }
    pub fn with_cache_dir(mut self, path: impl Into<PathBuf>) -> Self {
        self.cache_dir = path.into();
        self
    }

    // The prefix also makes Windows device names (e.g. CON) safe as file names.
    fn path(&self, symbol: &str, extension: &str) -> PathBuf {
        self.cache_dir.join(format!("logo-{symbol}.{extension}"))
    }

    pub async fn image(&self, symbol: &str) -> Result<Option<Vec<u8>>, AppError> {
        let symbol = normalize_symbol(symbol)
            .ok_or_else(|| AppError::InvalidRequest("invalid logo symbol".into()))?;
        let _guard = self.requests.lock().await;
        fs::create_dir_all(&self.cache_dir).await?;
        let image_path = self.path(&symbol, "png");
        let metadata_path = self.path(&symbol, "json");
        let previous = read_bounded(&image_path, MAX_LOGO_BYTES)
            .await?
            .filter(|bytes| valid_png(bytes));
        if previous.is_none() {
            remove_if_exists(&image_path).await?;
        }
        if let Some(mut metadata) = read_json::<Metadata>(&metadata_path).await? {
            metadata.last_accessed_at = Utc::now().timestamp_micros();
            write_json(&metadata_path, &metadata).await?;
            // A missing/corrupt image must not become a fresh negative cache hit.
            if metadata.has_image == previous.is_some()
                && metadata.expires_at > Utc::now().timestamp()
            {
                return Ok(previous);
            }
        }
        let cooldown_path = self.cache_dir.join("cooldown.json");
        if read_json::<Cooldown>(&cooldown_path)
            .await?
            .is_some_and(|state| state.blocked_until > Utc::now().timestamp())
        {
            return Ok(previous);
        }
        let mut blocked_until = None;
        let downloaded = match self.download(&symbol, &mut blocked_until).await {
            Ok((image, _)) => image,
            Err(error) => {
                tracing::debug!(%symbol, %error, "company logo unavailable");
                None
            }
        };
        if let Some(blocked_until) = blocked_until {
            write_json(&cooldown_path, &Cooldown { blocked_until }).await?;
        }
        let ttl = if downloaded.is_some() { 86400 } else { 300 };
        // Commit the PNG before its metadata, so an interrupted download never destroys the old PNG.
        if let Some(bytes) = &downloaded {
            write_atomic(&image_path, bytes).await?;
        }
        let image = downloaded.or(previous);
        write_json(
            &metadata_path,
            &Metadata {
                expires_at: Utc::now().timestamp() + ttl,
                last_accessed_at: Utc::now().timestamp_micros(),
                has_image: image.is_some(),
            },
        )
        .await?;
        self.prune().await?;
        Ok(image)
    }

    /// Always checks the live PNG endpoint, never reports a cached/stale logo as network success.
    /// Shares the production lock and persisted Retry-After gate; does not overwrite cached PNGs.
    pub async fn probe(&self, symbol: &str) -> Result<String, AppError> {
        let symbol = normalize_symbol(symbol)
            .ok_or_else(|| AppError::InvalidRequest("invalid logo symbol".into()))?;
        let _guard = self.requests.lock().await;
        fs::create_dir_all(&self.cache_dir).await?;
        let now = Utc::now().timestamp();
        let cooldown_path = self.cache_dir.join("cooldown.json");
        if let Some(state) = read_json::<Cooldown>(&cooldown_path).await?
            && state.blocked_until > now
        {
            return Err(AppError::QuotaExceeded {
                retry_after_seconds: state.blocked_until - now,
            });
        }
        let probe_path = self.cache_dir.join("probe.json");
        if let Some(state) = read_json::<ProbeAttempt>(&probe_path).await?
            && state.attempted_at.saturating_add(60) > now
        {
            return Err(AppError::QuotaExceeded {
                retry_after_seconds: state
                    .attempted_at
                    .saturating_add(60)
                    .saturating_sub(now)
                    .clamp(1, 86400),
            });
        }
        write_json(&probe_path, &ProbeAttempt { attempted_at: now }).await?;
        let mut blocked_until = None;
        let result = self.download(&symbol, &mut blocked_until).await;
        if let Some(blocked_until) = blocked_until {
            write_json(&cooldown_path, &Cooldown { blocked_until }).await?;
        }
        let (image, evidence) = result?;
        if image.is_none() {
            return Err(AppError::Provider(format!(
                "earnings logo unavailable; symbol={symbol}; {evidence}"
            )));
        }
        Ok(format!("symbol={symbol}; live_request=true; {evidence}"))
    }

    async fn prune(&self) -> io::Result<()> {
        // Only scan our own symbol files; never delete unrelated files in the configured directory.
        let mut symbols = BTreeSet::new();
        let mut directory = fs::read_dir(&self.cache_dir).await?;
        while let Some(entry) = directory.next_entry().await? {
            if !entry.file_type().await?.is_file() {
                continue;
            }
            let name = entry.file_name();
            let Some(name) = name.to_str().and_then(|name| name.strip_prefix("logo-")) else {
                continue;
            };
            if let Some(symbol) = name
                .strip_suffix(".json")
                .or_else(|| name.strip_suffix(".png"))
                && normalize_symbol(symbol).as_deref() == Some(symbol)
            {
                symbols.insert(symbol.to_owned());
            }
        }
        let mut entries = Vec::with_capacity(symbols.len());
        for symbol in symbols {
            let touched = read_json::<Metadata>(&self.path(&symbol, "json"))
                .await?
                .map_or(0, |metadata| metadata.last_accessed_at);
            entries.push((touched, symbol));
        }
        entries.sort_unstable_by(|a, b| b.cmp(a));
        for (_, symbol) in entries.into_iter().skip(MAX_CACHE_ENTRIES) {
            remove_if_exists(&self.path(&symbol, "json")).await?;
            remove_if_exists(&self.path(&symbol, "png")).await?;
        }
        Ok(())
    }

    async fn download(
        &self,
        symbol: &str,
        blocked_until: &mut Option<i64>,
    ) -> Result<(Option<Vec<u8>>, String), AppError> {
        let mut response = self
            .http
            .get(format!("{}/{symbol}.png", self.base.trim_end_matches('/')))
            .header("Accept", "image/png")
            .timeout(Duration::from_secs(10))
            .send()
            .await
            .map_err(|error| {
                AppError::Provider(format!(
                    "earnings logo; symbol={symbol}; HTTP=not_received; network={}",
                    network_failure(&error)
                ))
            })?;
        let mut evidence = format!(
            "HTTP={}; content-type={}",
            response.status().as_u16(),
            content_kind(response.headers())
        );
        if matches!(response.status().as_u16(), 403 | 429) {
            let delay = response
                .headers()
                .get("retry-after")
                .and_then(|v| v.to_str().ok())
                .and_then(|value| {
                    value.parse::<u64>().ok().or_else(|| {
                        DateTime::parse_from_rfc2822(value).ok().map(|at| {
                            (at.with_timezone(&Utc) - Utc::now()).num_seconds().max(0) as u64
                        })
                    })
                })
                .unwrap_or(300)
                .clamp(60, 86400);
            *blocked_until = Some(Utc::now().timestamp() + delay as i64);
            evidence.push_str(&format!("; retry_after_seconds={delay}"));
        }
        if !response.status().is_success() {
            return Ok((None, format!("{evidence}; PNG=unavailable")));
        }
        if response
            .content_length()
            .is_some_and(|n| n > MAX_LOGO_BYTES as u64)
        {
            return Ok((None, format!("{evidence}; PNG=too_large")));
        }
        if content_kind(response.headers()) != "image/png" {
            return Ok((None, format!("{evidence}; PNG=content_type_mismatch")));
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|error| {
            AppError::Provider(format!(
                "earnings logo; {evidence}; response_body={}",
                network_failure(&error)
            ))
        })? {
            if bytes.len() + chunk.len() > MAX_LOGO_BYTES {
                return Ok((None, format!("{evidence}; PNG=too_large")));
            }
            bytes.extend_from_slice(&chunk);
        }
        // Decorative images do not gate or change the calendar's health/status.
        tokio::time::sleep(Duration::from_millis(100)).await;
        if !valid_png(&bytes) {
            return Ok((
                None,
                format!("{evidence}; PNG=invalid; bytes={}", bytes.len()),
            ));
        }
        let width = u32::from_be_bytes(bytes[16..20].try_into().unwrap());
        let height = u32::from_be_bytes(bytes[20..24].try_into().unwrap());
        evidence.push_str(&format!(
            "; PNG=valid; bytes={}; dimensions={width}x{height}",
            bytes.len()
        ));
        Ok((Some(bytes), evidence))
    }
}

async fn read_bounded(path: &Path, max_bytes: usize) -> io::Result<Option<Vec<u8>>> {
    let file = match fs::File::open(path).await {
        Ok(file) => file,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(error),
    };
    let mut bytes = Vec::new();
    file.take(max_bytes as u64 + 1)
        .read_to_end(&mut bytes)
        .await?;
    Ok((bytes.len() <= max_bytes).then_some(bytes))
}

async fn read_json<T: DeserializeOwned>(path: &Path) -> io::Result<Option<T>> {
    Ok(read_bounded(path, MAX_METADATA_BYTES)
        .await?
        .and_then(|bytes| serde_json::from_slice(&bytes).ok()))
}

async fn write_json(path: &Path, value: &impl Serialize) -> io::Result<()> {
    let bytes = serde_json::to_vec(value).map_err(io::Error::other)?;
    write_atomic(path, &bytes).await
}

async fn write_atomic(path: &Path, bytes: &[u8]) -> io::Result<()> {
    static NEXT_TEMP: AtomicU64 = AtomicU64::new(0);
    let temporary = path.with_extension(format!(
        "{}.{}-{}.tmp",
        path.extension().unwrap_or_default().to_string_lossy(),
        std::process::id(),
        NEXT_TEMP.fetch_add(1, Ordering::Relaxed)
    ));
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temporary)
        .await?;
    let result = async {
        file.write_all(bytes).await?;
        file.sync_all().await
    }
    .await;
    drop(file);
    let result = match result {
        Ok(()) => fs::rename(&temporary, path).await,
        Err(error) => Err(error),
    };
    if result.is_err() {
        let _ = fs::remove_file(&temporary).await;
    }
    result
}

async fn remove_if_exists(path: &Path) -> io::Result<()> {
    match fs::remove_file(path).await {
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        result => result,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn symbols_cannot_escape_the_fixed_asset_path() {
        assert_eq!(normalize_symbol("brk.b").as_deref(), Some("BRK.B"));
        for symbol in [
            "",
            "..",
            "../AAPL",
            "AAPL?key=x",
            "https://host",
            "中",
            "AAPL%2fX",
        ] {
            assert!(normalize_symbol(symbol).is_none());
        }
    }
    #[test]
    fn only_small_png_headers_are_accepted() {
        let image = include_bytes!("../../android/app/src/test/resources/earnings/logo.png");
        assert!(valid_png(image));
        assert!(!valid_png(b"<html>blocked</html>"));
        let mut huge = image.to_vec();
        huge[16..20].copy_from_slice(&2000u32.to_be_bytes());
        assert!(!valid_png(&huge));
        assert!(!valid_png(&vec![0; MAX_LOGO_BYTES + 1]));
    }
}
