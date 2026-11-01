//! Fixed-host public PNG assets, independent of calendar refresh and financial forecasts.
use crate::error::AppError;
use chrono::{DateTime, Utc};
use std::{collections::HashMap, time::Duration};
use tokio::{sync::Mutex, time::Instant};

pub const LOGO_BASE: &str = "https://images.financialmodelingprep.com/symbol";
pub const MAX_LOGO_BYTES: usize = 256 * 1024;

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

struct Entry {
    image: Option<Vec<u8>>,
    expires: Instant,
    touched: u64,
}
#[derive(Default)]
struct Cache {
    entries: HashMap<String, Entry>,
    tick: u64,
    blocked_until: Option<Instant>,
}

pub struct CompanyLogoService {
    http: reqwest::Client,
    base: String,
    cache: Mutex<Cache>,
}
impl CompanyLogoService {
    pub fn new(http: reqwest::Client) -> Self {
        Self {
            http,
            base: LOGO_BASE.into(),
            cache: Mutex::new(Cache::default()),
        }
    }
    pub fn with_base(mut self, base: String) -> Self {
        self.base = base;
        self
    }

    pub async fn image(&self, symbol: &str) -> Result<Option<Vec<u8>>, AppError> {
        let symbol = normalize_symbol(symbol)
            .ok_or_else(|| AppError::InvalidRequest("invalid logo symbol".into()))?;
        // Separate lock from calendar requests: duplicate images coalesce without delaying data.
        let mut cache = self.cache.lock().await;
        cache.tick = cache.tick.wrapping_add(1);
        let tick = cache.tick;
        let previous = if let Some(entry) = cache.entries.get_mut(&symbol) {
            entry.touched = tick;
            if entry.expires > Instant::now() {
                return Ok(entry.image.clone());
            }
            entry.image.clone()
        } else {
            None
        };
        if cache.blocked_until.is_some_and(|at| at > Instant::now()) {
            return Ok(previous);
        }
        let image = match self.download(&symbol, &mut cache).await {
            Ok(image) => image,
            Err(error) => {
                tracing::debug!(%symbol, %error, "company logo unavailable");
                None
            }
        };
        let ttl = if image.is_some() { 86400 } else { 300 };
        let image = image.or(previous);
        if cache.entries.len() >= 128
            && !cache.entries.contains_key(&symbol)
            && let Some(oldest) = cache
                .entries
                .iter()
                .min_by_key(|(_, entry)| entry.touched)
                .map(|(key, _)| key.clone())
        {
            cache.entries.remove(&oldest);
        }
        cache.entries.insert(
            symbol,
            Entry {
                image: image.clone(),
                expires: Instant::now() + Duration::from_secs(ttl),
                touched: tick,
            },
        );
        Ok(image)
    }

    async fn download(&self, symbol: &str, cache: &mut Cache) -> Result<Option<Vec<u8>>, AppError> {
        let mut response = self
            .http
            .get(format!("{}/{symbol}.png", self.base.trim_end_matches('/')))
            .header("Accept", "image/png")
            .timeout(Duration::from_secs(10))
            .send()
            .await?;
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
            cache.blocked_until = Some(Instant::now() + Duration::from_secs(delay));
        }
        if !response.status().is_success()
            || response
                .content_length()
                .is_some_and(|n| n > MAX_LOGO_BYTES as u64)
            || !response
                .headers()
                .get("content-type")
                .and_then(|v| v.to_str().ok())
                .is_some_and(|v| {
                    v.split(';')
                        .next()
                        .unwrap_or("")
                        .trim()
                        .eq_ignore_ascii_case("image/png")
                })
        {
            return Ok(None);
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await? {
            if bytes.len() + chunk.len() > MAX_LOGO_BYTES {
                return Ok(None);
            }
            bytes.extend_from_slice(&chunk);
        }
        // Decorative images do not gate or change the calendar's health/status.
        tokio::time::sleep(Duration::from_millis(100)).await;
        Ok(valid_png(&bytes).then_some(bytes))
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
