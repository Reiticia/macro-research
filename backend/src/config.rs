use std::{collections::BTreeMap, env, fs, path::Path};

use serde::Deserialize;

use crate::error::AppError;

#[derive(Clone, Debug, Deserialize)]
pub struct AppConfig {
    pub server: ServerConfig,
    pub database: DatabaseConfig,
    pub calendar: CalendarConfig,
    pub scheduler: SchedulerConfig,
    pub market: MarketConfig,
    #[serde(default)]
    pub network: NetworkConfig,
    #[serde(default)]
    pub auth: AuthConfig,
    #[serde(default)]
    pub translation: TranslationConfig,
    #[serde(default)]
    pub ai: AiConfig,
    #[serde(default)]
    pub telegram: TelegramConfig,
    #[serde(default)]
    pub alerts: AlertConfig,
    #[serde(default)]
    pub limits: LimitConfig,
    #[serde(default)]
    pub backfill: BackfillConfig,
}

#[derive(Clone, Debug, Deserialize)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
    /// Path to a PEM certificate chain. Set together with `tls_key` to serve HTTPS
    /// directly, without a reverse proxy.
    #[serde(default)]
    pub tls_cert: Option<String>,
    /// Path to the matching PEM private key.
    #[serde(default)]
    pub tls_key: Option<String>,
}

impl ServerConfig {
    /// True when the service terminates TLS itself.
    pub fn tls_enabled(&self) -> bool {
        let cert = self.tls_cert.as_deref().map(str::trim).unwrap_or("");
        let key = self.tls_key.as_deref().map(str::trim).unwrap_or("");
        !cert.is_empty() && !key.is_empty()
    }

    /// Rejects a half-configured TLS pair instead of silently serving plain HTTP.
    pub fn validate_tls(&self) -> Result<(), String> {
        let cert = self.tls_cert.as_deref().map(str::trim).unwrap_or("");
        let key = self.tls_key.as_deref().map(str::trim).unwrap_or("");
        if cert.is_empty() != key.is_empty() {
            return Err(
                "[server] tls_cert and tls_key must be set together (or both left empty)".into(),
            );
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Deserialize)]
pub struct DatabaseConfig {
    pub url: String,
}

#[derive(Clone, Debug, Deserialize)]
pub struct CalendarConfig {
    /// TradingView's keyless economic calendar endpoint (primary source).
    #[serde(default = "default_tradingview_url")]
    pub primary_url: String,
    /// Forex Factory weekly JSON, used when the primary source cannot answer.
    #[serde(default = "default_forex_factory_url")]
    pub fallback_url: String,
    /// Legacy Trading Economics calendar page, only used by the historical backfill.
    #[serde(default = "default_trading_economics_url")]
    pub base_url: String,
    pub sync_days: i64,
    pub minimum_importance: u8,
}

fn default_tradingview_url() -> String {
    "https://economic-calendar.tradingview.com/events".into()
}

fn default_forex_factory_url() -> String {
    "https://nfs.faireconomy.media/ff_calendar_thisweek.json".into()
}

fn default_trading_economics_url() -> String {
    "https://tradingeconomics.com/calendar".into()
}

#[derive(Clone, Debug, Deserialize)]
pub struct SchedulerConfig {
    pub calendar_sync_seconds: u64,
    pub watch_scan_seconds: u64,
    pub watch_before_minutes: i64,
    pub release_timeout_minutes: i64,
    pub market_poll_seconds: u64,
    pub market_collect_after_minutes: i64,
}

#[derive(Clone, Debug, Deserialize)]
pub struct MarketConfig {
    pub yahoo_base_url: String,
    pub binance_base_url: String,
    #[serde(default = "default_biquote_base_url")]
    pub biquote_base_url: String,
    #[serde(default = "default_cnbc_quote_url")]
    pub cnbc_quote_url: String,
    #[serde(default = "default_cnbc_chart_url")]
    pub cnbc_chart_url: String,
    #[serde(default = "default_live_quote_cache_seconds")]
    pub live_quote_cache_seconds: u64,
    #[serde(default = "default_live_quote_stale_seconds")]
    pub live_quote_stale_seconds: u64,
    pub symbols: Vec<String>,
}

fn default_biquote_base_url() -> String {
    "https://biquote.io".into()
}

fn default_cnbc_quote_url() -> String {
    "https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol".into()
}

fn default_cnbc_chart_url() -> String {
    "https://ts-api.cnbc.com/harmony/app/charts".into()
}

fn default_live_quote_cache_seconds() -> u64 {
    30
}

fn default_live_quote_stale_seconds() -> u64 {
    300
}

/// Which upstreams must tunnel through `proxy_url`. Domestic hosts cannot reach the
/// blocked calendar, rate and Telegram endpoints directly.
#[derive(Clone, Debug, Deserialize)]
#[serde(default)]
pub struct NetworkConfig {
    /// `http://127.0.0.1:7890` or `socks5://127.0.0.1:1080`. Empty means direct.
    pub proxy_url: String,
    pub proxied: Vec<String>,
    pub request_timeout_seconds: u64,
}

impl Default for NetworkConfig {
    fn default() -> Self {
        Self {
            proxy_url: String::new(),
            proxied: vec![
                "tradingview".into(),
                "forexfactory".into(),
                "cnbc".into(),
                "yahoo".into(),
                "telegram".into(),
            ],
            request_timeout_seconds: 20,
        }
    }
}

impl NetworkConfig {
    /// True when outbound traffic for `source` must be tunnelled through the proxy.
    pub fn proxies(&self, source: &str) -> bool {
        !self.proxy_url.trim().is_empty()
            && self
                .proxied
                .iter()
                .any(|entry| entry.eq_ignore_ascii_case(source))
    }
}

/// Token gate for every `/api/v1` route. The tokens themselves live in the
/// `API_TOKENS` environment variable so they never enter the config file.
#[derive(Clone, Debug, Deserialize)]
#[serde(default)]
pub struct AuthConfig {
    pub enabled: bool,
    /// Environment variable holding `name:token,name:token`.
    pub tokens_env: String,
}

impl Default for AuthConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            tokens_env: "API_TOKENS".into(),
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(default)]
pub struct TranslationConfig {
    pub enabled: bool,
    /// OpenAI-compatible endpoint. A relay may be written as `https://relay/v1` or as the
    /// full `.../v1/chat/completions` URL.
    pub base_url: String,
    pub model: String,
    pub api_key_env: String,
    /// Extra headers for the relay (for example `X-Title`, or `api-key` for Azure-style
    /// gateways). An `Authorization` entry replaces the default bearer header.
    pub extra_headers: BTreeMap<String, String>,
    /// Free-form JSON merged into every request body; `messages` is protected.
    pub extra_body: BTreeMap<String, serde_json::Value>,
    pub batch_size: usize,
    pub backfill_on_startup: bool,
}

impl Default for TranslationConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            base_url: "https://api.openai.com/v1".into(),
            model: "gpt-5-mini".into(),
            api_key_env: "OPENAI_API_KEY".into(),
            extra_headers: BTreeMap::new(),
            extra_body: BTreeMap::new(),
            batch_size: 20,
            backfill_on_startup: true,
        }
    }
}

/// Server-side AI market briefing. The API key is read from the named environment
/// variable, never from this file or the database.
#[derive(Clone, Debug, Deserialize)]
#[serde(default)]
pub struct AiConfig {
    pub enabled: bool,
    /// OpenAI-compatible endpoint; `https://relay/v1` and the full
    /// `.../v1/chat/completions` URL are both accepted.
    pub base_url: String,
    pub model: String,
    pub api_key_env: String,
    /// Extra headers for the relay; an `Authorization` entry replaces the bearer header.
    pub extra_headers: BTreeMap<String, String>,
    /// Free-form JSON merged into every request body: reasoning effort, thinking budget and
    /// any other provider-specific knob the server does not model. `messages` is protected.
    pub extra_body: BTreeMap<String, serde_json::Value>,
    /// Name of the output-token parameter. `max_tokens` is the OpenAI classic; reasoning models
    /// (gpt-5 / o-series) reject it and require `max_completion_tokens`. The server retries with
    /// the other name automatically when a relay says so, so this is only a manual override.
    #[serde(default = "default_max_tokens_param")]
    pub max_tokens_param: String,
    /// 1 = numbers only, 2 = ex-ante then comparison, 3 = single pass.
    pub default_method: u8,
    pub max_tokens: u32,
}

fn default_max_tokens_param() -> String {
    "max_tokens".into()
}

impl Default for AiConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            base_url: "https://api.openai.com/v1".into(),
            model: "gpt-5-mini".into(),
            api_key_env: "OPENAI_API_KEY".into(),
            extra_headers: BTreeMap::new(),
            extra_body: BTreeMap::new(),
            max_tokens_param: default_max_tokens_param(),
            default_method: 2,
            max_tokens: 4096,
        }
    }
}

impl AiConfig {
    pub fn api_key(&self) -> Result<String, AppError> {
        read_api_key(&self.api_key_env, "ai")
    }
}

/// Reads a key from the named environment variable, rejecting an empty value so a half-set
/// deployment fails loudly instead of sending unauthenticated requests to the relay.
pub fn read_api_key(env_name: &str, scope: &str) -> Result<String, AppError> {
    env::var(env_name)
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
        .ok_or_else(|| AppError::Config(format!("{env_name} is required when {scope} is enabled")))
}

#[derive(Clone, Debug, Deserialize)]
#[serde(default)]
pub struct TelegramConfig {
    pub enabled: bool,
    pub api_base: String,
    pub bot_token_env: String,
    pub admin_chat_ids_env: String,
    pub poll_timeout_seconds: u64,
}

impl Default for TelegramConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            api_base: "https://api.telegram.org".into(),
            bot_token_env: "TELEGRAM_BOT_TOKEN".into(),
            admin_chat_ids_env: "TELEGRAM_ADMIN_CHAT_IDS".into(),
            poll_timeout_seconds: 30,
        }
    }
}

impl TelegramConfig {
    pub fn bot_token(&self) -> Option<String> {
        env::var(&self.bot_token_env)
            .ok()
            .map(|value| value.trim().to_owned())
            .filter(|value| !value.is_empty())
    }

    /// Only these chats may read status or decide translation corrections.
    pub fn admin_chat_ids(&self) -> Vec<i64> {
        env::var(&self.admin_chat_ids_env)
            .unwrap_or_default()
            .split(',')
            .filter_map(|value| value.trim().parse::<i64>().ok())
            .collect()
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(default)]
pub struct AlertConfig {
    pub failure_threshold: u32,
    pub cooldown_seconds: i64,
    pub data_missing_after_minutes: i64,
    pub data_missing_cooldown_seconds: i64,
    pub digest_max_events: usize,
    /// `23:00-07:00` suppresses non-critical notifications. Empty disables quiet hours.
    pub quiet_hours: String,
    pub quiet_hours_timezone: String,
}

impl Default for AlertConfig {
    fn default() -> Self {
        Self {
            failure_threshold: 3,
            cooldown_seconds: 3600,
            data_missing_after_minutes: 30,
            data_missing_cooldown_seconds: 21600,
            digest_max_events: 10,
            quiet_hours: String::new(),
            quiet_hours_timezone: "Asia/Shanghai".into(),
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(default)]
pub struct LimitConfig {
    pub ai_analysis_per_token_per_day: u32,
    /// Minimum seconds between two generations of the same (event, method). A caller inside the
    /// window receives the previous result instead of a new model call.
    pub ai_regenerate_cooldown_seconds: i64,
    pub ai_concurrency: usize,
    /// How long model-call audit records are kept.
    pub llm_usage_retention_days: i64,
}

impl Default for LimitConfig {
    fn default() -> Self {
        Self {
            ai_analysis_per_token_per_day: 60,
            ai_regenerate_cooldown_seconds: 600,
            ai_concurrency: 2,
            llm_usage_retention_days: 180,
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(default)]
pub struct BackfillConfig {
    pub calendar_api_base_url: String,
    pub request_delay_ms: u64,
}

impl Default for BackfillConfig {
    fn default() -> Self {
        Self {
            calendar_api_base_url: "https://api.tradingeconomics.com".into(),
            request_delay_ms: 1000,
        }
    }
}

impl AppConfig {
    pub fn load() -> Result<Self, AppError> {
        let path = env::var("APP_CONFIG").unwrap_or_else(|_| "config.toml".to_owned());
        Self::from_path(path)
    }

    pub fn from_path(path: impl AsRef<Path>) -> Result<Self, AppError> {
        let contents = fs::read_to_string(path)?;
        let config: Self = toml::from_str(&contents)?;
        config.server.validate_tls().map_err(AppError::Config)?;
        Ok(config)
    }
}
