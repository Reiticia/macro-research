use std::{collections::BTreeMap, env, fs, path::Path};

use serde::Deserialize;

use crate::error::AppError;

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
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
#[serde(deny_unknown_fields)]
pub struct ServerConfig {
    pub host: String,
    pub port: u16,
    /// Tracing filter when `RUST_LOG` is unset, for example
    /// `market_event_analyzer=info,tower_http=info`.
    #[serde(default)]
    pub log_level: String,
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
#[serde(deny_unknown_fields)]
pub struct DatabaseConfig {
    pub url: String,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
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
#[serde(deny_unknown_fields)]
pub struct SchedulerConfig {
    pub calendar_sync_seconds: u64,
    pub watch_scan_seconds: u64,
    pub watch_before_minutes: i64,
    pub release_timeout_minutes: i64,
    /// Point-quote sampling cadence for event bars; persisted rows are aggregated to one minute.
    /// This is independent from the client-facing live quote refresh cadence.
    pub market_poll_seconds: u64,
    pub market_collect_after_minutes: i64,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MarketConfig {
    pub yahoo_base_url: String,
    pub binance_base_url: String,
    #[serde(default = "default_biquote_base_url")]
    pub biquote_base_url: String,
    #[serde(default = "default_cnbc_quote_url")]
    pub cnbc_quote_url: String,
    #[serde(default = "default_cnbc_chart_url")]
    pub cnbc_chart_url: String,
    #[serde(default = "default_live_quote_refresh_seconds")]
    pub live_quote_refresh_seconds: u64,
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

fn default_live_quote_refresh_seconds() -> u64 {
    5
}

fn default_live_quote_stale_seconds() -> u64 {
    300
}

/// Which upstreams must tunnel through `proxy_url`. Domestic hosts cannot reach the
/// blocked calendar, rate and Telegram endpoints directly.
#[derive(Clone, Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
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

/// Token gate for every `/api/v1` route. The tokens live in this file; the deployment script
/// writes them with `0640 root:market` permissions.
#[derive(Clone, Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct AuthConfig {
    pub enabled: bool,
    /// Access tokens as `name:token,name:token`, one name per device so the audit can tell
    /// callers apart.
    pub tokens: String,
}

impl Default for AuthConfig {
    fn default() -> Self {
        Self {
            enabled: true,
            tokens: String::new(),
        }
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct TranslationConfig {
    pub enabled: bool,
    /// OpenAI-compatible endpoint. A relay may be written as `https://relay/v1` or as the
    /// full `.../v1/chat/completions` URL.
    pub base_url: String,
    pub model: String,
    /// Key for the relay, written straight into this file.
    pub api_key: String,
    /// Extra headers for the relay (for example `X-Title`, or `api-key` for Azure-style
    /// gateways). An `Authorization` entry replaces the default bearer header.
    pub extra_headers: BTreeMap<String, String>,
    /// Free-form JSON merged into every request body; `messages` is protected.
    pub extra_body: BTreeMap<String, serde_json::Value>,
    pub batch_size: usize,
    /// Rounds of "translate, review, retry the rejected ones" per batch. A name that keeps
    /// failing review is left untranslated and retried by the next sync.
    pub max_rounds: usize,
    pub backfill_on_startup: bool,
}

impl Default for TranslationConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            base_url: "https://api.openai.com/v1".into(),
            model: "gpt-5-mini".into(),
            api_key: String::new(),
            extra_headers: BTreeMap::new(),
            extra_body: BTreeMap::new(),
            batch_size: 20,
            max_rounds: 3,
            backfill_on_startup: true,
        }
    }
}

/// Server-side AI market briefing. The API key is read from this configuration file and
/// never from the database.
#[derive(Clone, Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct AiConfig {
    pub enabled: bool,
    /// OpenAI-compatible endpoint; `https://relay/v1` and the full
    /// `.../v1/chat/completions` URL are both accepted.
    pub base_url: String,
    pub model: String,
    /// Key for the relay, written straight into this file.
    pub api_key: String,
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
            api_key: String::new(),
            extra_headers: BTreeMap::new(),
            extra_body: BTreeMap::new(),
            max_tokens_param: default_max_tokens_param(),
            default_method: 2,
            max_tokens: 4096,
        }
    }
}

impl TranslationConfig {
    pub fn api_key(&self) -> Result<String, AppError> {
        require_secret(&self.api_key, "translation.api_key")
    }
}

impl AiConfig {
    pub fn api_key(&self) -> Result<String, AppError> {
        require_secret(&self.api_key, "ai.api_key")
    }
}

/// Rejects an empty credential with the exact config key that has to be filled.
pub fn require_secret(value: &str, what: &str) -> Result<String, AppError> {
    let trimmed = value.trim();
    if !trimmed.is_empty() {
        return Ok(trimmed.to_owned());
    }
    Err(AppError::Config(format!(
        "{what} is empty: set it in the config file"
    )))
}

#[derive(Clone, Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct TelegramConfig {
    pub enabled: bool,
    pub api_base: String,
    /// Bot token written straight into this file.
    pub bot_token: String,
    /// Comma-separated admin chat ids.
    pub admin_chat_ids: String,
    pub poll_timeout_seconds: u64,
}

impl Default for TelegramConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            api_base: "https://api.telegram.org".into(),
            bot_token: String::new(),
            admin_chat_ids: String::new(),
            poll_timeout_seconds: 30,
        }
    }
}

impl TelegramConfig {
    pub fn bot_token(&self) -> Option<String> {
        let value = self.bot_token.trim();
        (!value.is_empty()).then(|| value.to_owned())
    }

    /// Only these chats may read status, usage and the analysis-feedback buttons.
    pub fn admin_chat_ids(&self) -> Vec<i64> {
        self.admin_chat_ids
            .split(',')
            .filter_map(|value| value.trim().parse::<i64>().ok())
            .collect()
    }
}

#[derive(Clone, Debug, Deserialize)]
#[serde(default, deny_unknown_fields)]
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
#[serde(default, deny_unknown_fields)]
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
#[serde(default, deny_unknown_fields)]
pub struct BackfillConfig {
    pub calendar_api_base_url: String,
    pub request_delay_ms: u64,
    /// Trading Economics credential for the `--backfill` CLI, written straight into this file.
    pub te_api_key: String,
}

impl Default for BackfillConfig {
    fn default() -> Self {
        Self {
            calendar_api_base_url: "https://api.tradingeconomics.com".into(),
            request_delay_ms: 1000,
            te_api_key: String::new(),
        }
    }
}

impl AppConfig {
    /// Loads the configuration from `APP_CONFIG` when set, otherwise from a `config.toml` next
    /// to the executable, falling back to the working directory for `cargo run`.
    pub fn load() -> Result<Self, AppError> {
        let path = Self::resolve_path()?;
        if !path.is_file() {
            return Err(AppError::Config(format!(
                "没有读到配置文件 {}；请先复制模板：cp config.example.toml config.toml（或用 APP_CONFIG 指定路径）",
                path.display()
            )));
        }
        Self::from_path(path)
    }

    /// The configuration file that will be loaded, so logs and the startup notice can name it.
    pub fn resolve_path() -> Result<std::path::PathBuf, AppError> {
        if let Ok(explicit) = env::var("APP_CONFIG")
            && !explicit.trim().is_empty()
        {
            return Ok(std::path::PathBuf::from(explicit.trim()));
        }
        if let Ok(exe) = env::current_exe()
            && let Some(dir) = exe.parent()
        {
            let beside_binary = dir.join("config.toml");
            if beside_binary.is_file() {
                return Ok(beside_binary);
            }
        }
        Ok(std::path::PathBuf::from("config.toml"))
    }

    /// Directory of the loaded file; relative companions such as `rules.toml` resolve against it.
    pub fn dir(&self, path: &Path) -> std::path::PathBuf {
        path.parent()
            .filter(|parent| !parent.as_os_str().is_empty())
            .map(std::path::Path::to_path_buf)
            .unwrap_or_else(|| std::path::PathBuf::from("."))
    }

    pub fn from_path(path: impl AsRef<Path>) -> Result<Self, AppError> {
        let contents = fs::read_to_string(path)?;
        let config: Self = toml::from_str(&contents)?;
        config.server.validate_tls().map_err(AppError::Config)?;
        Ok(config)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::TokenStore;

    /// The one-file deployment story: everything including credentials lives in the file, and
    /// the executable's own directory is a valid default location.
    #[test]
    fn a_single_config_file_carries_the_credentials() {
        let dir = std::env::temp_dir().join(format!("macro-config-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("config.toml");
        std::fs::write(
            &path,
            r#"
[server]
host = "0.0.0.0"
port = 8443
tls_cert = "/etc/tls/fullchain.pem"
tls_key = "/etc/tls/privkey.pem"
log_level = "market_event_analyzer=debug"

[database]
url = "sqlite://data/market.db"

[calendar]
sync_days = 7
minimum_importance = 3

[network]
proxy_url = ""

[auth]
enabled = true
tokens = "pixel:abc123,emulator:def456"

[translation]
enabled = true
base_url = "https://relay.example.com/v1"
model = "deepseek-flash"
api_key = "relay-translation-key"

[ai]
enabled = true
base_url = "https://relay.example.com/v1"
model = "deepseek-flash"
api_key = "relay-ai-key"

[telegram]
enabled = true
bot_token = "123456:ABC"
admin_chat_ids = "111,222"

[alerts]

[market]
yahoo_base_url = "https://x"
binance_base_url = "https://x"
symbols = ["gold"]

[scheduler]
calendar_sync_seconds = 43200
watch_scan_seconds = 15
watch_before_minutes = 5
release_timeout_minutes = 30
market_poll_seconds = 15
market_collect_after_minutes = 60
"#,
        )
        .unwrap();

        let config = AppConfig::from_path(&path).unwrap();
        assert_eq!(config.server.log_level, "market_event_analyzer=debug");
        assert_eq!(config.market.live_quote_refresh_seconds, 5);
        // The rules file is looked up next to the configuration file.
        assert_eq!(config.dir(&path), dir);

        // Credentials resolve from the configuration file.
        assert_eq!(
            TokenStore::from_config(&config.auth)
                .unwrap()
                .authenticate(Some("Bearer abc123")),
            Some("pixel".to_owned())
        );
        assert_eq!(
            config.translation.api_key().unwrap(),
            "relay-translation-key"
        );
        assert_eq!(config.ai.api_key().unwrap(), "relay-ai-key");
        assert_eq!(config.telegram.bot_token().as_deref(), Some("123456:ABC"));
        assert_eq!(config.telegram.admin_chat_ids(), vec![111, 222]);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn a_missing_credential_names_the_config_key() {
        let config = TranslationConfig::default();
        let error = config.api_key().unwrap_err().to_string();
        assert!(error.contains("translation.api_key"), "{error}");
    }

    #[test]
    fn unknown_market_options_are_rejected() {
        let error = toml::from_str::<MarketConfig>(
            r#"
            yahoo_base_url = "https://example.com"
            binance_base_url = "https://example.com"
            symbols = ["gold"]
            live_quote_cache_seconds = 30
            "#,
        )
        .unwrap_err();
        assert!(error.to_string().contains("unknown field"), "{error}");
    }
}
