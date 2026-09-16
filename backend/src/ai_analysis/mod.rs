pub mod parse;

use std::{sync::Arc, time::Duration};

use chrono::{DateTime, Utc};
use chrono_tz::Tz;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sqlx::{Row, SqlitePool};

use crate::{
    alert::HealthRegistry,
    config::AiConfig,
    error::AppError,
    llm_usage::{LlmKind, LlmScope, LlmUsageEntry, LlmUsageRepository, TokenUsage, endpoint_host},
    model::{EconomicEvent, MacroSignal, MarketReaction},
    openai_compat::{ExtraHeaders, chat_endpoint},
    repository::{AnalysisRepository, EventRepository, MarketRepository},
};

pub use parse::Draft;

/// One link of the AI-generated transmission chain.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct TransmissionStep {
    pub from: String,
    pub to: String,
    pub direction: String,
    pub rationale: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub verdict: Option<String>,
}

/// Server-generated market briefing for one released event.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AiAnalysis {
    pub event_id: i64,
    /// Which of the three methods produced this row. The three never overwrite each other.
    pub method: u8,
    pub revision: i64,
    pub chain: Vec<TransmissionStep>,
    pub data_analysis: String,
    pub market_outlook: String,
    pub risks: Option<String>,
    pub model: String,
    pub generated_at: DateTime<Utc>,
    /// Token usage of the model call(s) that produced this row, when the relay reported it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub usage: Option<LlmUsageTotals>,
}

/// Tokens spent producing one briefing. A two-pass method counts both passes.
#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct LlmUsageTotals {
    pub prompt_tokens: i64,
    pub completion_tokens: i64,
    pub total_tokens: i64,
    pub calls: i64,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AnalysisMethod {
    /// 1: numbers and rules only, market moves are never revealed.
    NumbersOnly,
    /// 2 (default): an ex-ante pass, then a comparison pass that may only validate it.
    TwoPass,
    /// 3: numbers, rules and observed moves in a single request.
    SinglePass,
}

impl AnalysisMethod {
    pub fn from_u8(value: u8) -> Self {
        match value {
            1 => Self::NumbersOnly,
            3 => Self::SinglePass,
            _ => Self::TwoPass,
        }
    }

    pub fn as_u8(self) -> u8 {
        match self {
            Self::NumbersOnly => 1,
            Self::TwoPass => 2,
            Self::SinglePass => 3,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Stage {
    Expectation,
    Comparison,
    SinglePass,
}

/// Everything the model needs to brief one released event.
struct AnalysisInput {
    event: EconomicEvent,
    macro_signal: MacroSignal,
    raw_surprise: Option<String>,
    expected_reactions: Vec<(String, String)>,
    observed_reactions: Vec<MarketReaction>,
    language: String,
    timezone: String,
    /// API token that triggered the run, recorded in the audit log.
    caller: String,
}

/// Generates and caches post-release briefings on the server's own model key.
pub struct AiAnalysisService {
    http: reqwest::Client,
    config: AiConfig,
    api_key: String,
    endpoint: String,
    extra_headers: ExtraHeaders,
    pool: SqlitePool,
    events: EventRepository,
    analyses: AnalysisRepository,
    market: MarketRepository,
    health: Option<Arc<HealthRegistry>>,
    audit: Option<Arc<LlmUsageRepository>>,
    /// Minimum seconds between two generations of the same (event, method).
    regenerate_cooldown_seconds: i64,
}

/// Health key reported to the alert registry when the relay fails.
pub const RELAY_HEALTH_KEY: &str = "analysis.ai";

/// What the endpoint returns: the briefing plus why it was served.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AiAnalysisResponse {
    #[serde(flatten)]
    pub analysis: AiAnalysis,
    /// True when no model call was made for this request.
    pub from_cache: bool,
    /// True when the caller asked for a fresh run but the rate limit served the previous one.
    pub rate_limited: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub retry_after_seconds: Option<i64>,
}

impl AiAnalysisResponse {
    /// A plain cache hit: nothing was throttled, the caller simply already had this.
    pub fn cached(analysis: AiAnalysis) -> Self {
        Self {
            analysis,
            from_cache: true,
            rate_limited: false,
            retry_after_seconds: None,
        }
    }

    pub fn generated(analysis: AiAnalysis) -> Self {
        Self {
            analysis,
            from_cache: false,
            rate_limited: false,
            retry_after_seconds: None,
        }
    }

    /// The rate limit fired: the previous result is returned instead of an error.
    pub fn throttled(analysis: AiAnalysis, retry_after_seconds: i64) -> Self {
        Self {
            analysis,
            from_cache: true,
            rate_limited: true,
            retry_after_seconds: Some(retry_after_seconds),
        }
    }
}

impl AiAnalysisService {
    pub fn new(
        http: reqwest::Client,
        config: AiConfig,
        pool: SqlitePool,
        events: EventRepository,
        analyses: AnalysisRepository,
        market: MarketRepository,
    ) -> Result<Self, AppError> {
        let api_key = config.api_key()?;
        let endpoint = chat_endpoint(&config.base_url)
            .map_err(|error| AppError::Config(format!("ai.base_url: {error}")))?;
        let extra_headers = ExtraHeaders::from_map(&config.extra_headers);
        if !extra_headers.is_empty() {
            tracing::info!(
                "ai relay: {} extra header(s) configured",
                config.extra_headers.len()
            );
        }
        Ok(Self {
            http,
            config,
            api_key,
            endpoint,
            extra_headers,
            pool,
            events,
            analyses,
            market,
            health: None,
            audit: None,
            regenerate_cooldown_seconds: 0,
        })
    }

    pub fn with_health(mut self, health: Arc<HealthRegistry>) -> Self {
        self.health = Some(health);
        self
    }

    pub fn with_audit(mut self, audit: Arc<LlmUsageRepository>) -> Self {
        self.audit = Some(audit);
        self
    }

    /// Audit handle that may be absent (auditing disabled in configuration).
    pub fn with_audit_opt(mut self, audit: Option<Arc<LlmUsageRepository>>) -> Self {
        self.audit = audit;
        self
    }

    /// Sets the minimum interval between two generations of the same (event, method).
    pub fn with_regenerate_cooldown(mut self, seconds: i64) -> Self {
        self.regenerate_cooldown_seconds = seconds.max(0);
        self
    }

    pub async fn cached(
        &self,
        event_id: i64,
        language: &str,
        method: AnalysisMethod,
        timezone: &str,
    ) -> Result<Option<AiAnalysis>, AppError> {
        let row = sqlx::query(
            "SELECT * FROM ai_analysis WHERE event_id = ? AND language = ? AND method = ? AND timezone = ?",
        )
        .bind(event_id)
        .bind(normalize_language(language))
        .bind(i64::from(method.as_u8()))
        .bind(timezone)
        .fetch_optional(&self.pool)
        .await?;
        row.map(row_to_analysis).transpose()
    }

    /// Returns the cached briefing unless a fresh run is allowed.
    ///
    /// The three methods never overwrite each other: the cache key is
    /// `(event, language, method, timezone)`. Regenerating one method only replaces that row and
    /// bumps its revision.
    ///
    /// Two rate limits apply, and both fall back to the previous result instead of failing:
    /// the per-(event, method) cooldown here, and the caller's daily budget in the handler.
    pub async fn generate(
        &self,
        event_id: i64,
        language: &str,
        method: AnalysisMethod,
        timezone: &str,
        regenerate: bool,
        caller: &str,
    ) -> Result<AiAnalysisResponse, AppError> {
        let language = normalize_language(language);
        let timezone = normalize_timezone(timezone);
        let cached = self.cached(event_id, &language, method, &timezone).await?;
        if let Some(cached) = cached {
            if !regenerate {
                return Ok(AiAnalysisResponse::cached(cached));
            }
            if let Some(remaining) = self.cooldown_remaining(&cached) {
                tracing::info!(
                    event_id,
                    method = method.as_u8(),
                    remaining,
                    "AI regeneration throttled; serving the previous analysis"
                );
                return Ok(AiAnalysisResponse::throttled(cached, remaining));
            }
        }
        let event = self.events.get(event_id).await?;
        let report = self.analyses.get(event_id).await?;
        let observed = self.market.reactions(event_id).await?;
        let input = AnalysisInput {
            event,
            macro_signal: report.macro_signal,
            raw_surprise: report.raw_surprise.map(|value| value.to_string()),
            expected_reactions: report
                .expected_reactions
                .iter()
                .map(|reaction| {
                    (
                        reaction.symbol.to_string(),
                        reaction.direction.as_str().to_owned(),
                    )
                })
                .collect(),
            observed_reactions: if observed.is_empty() {
                report.observed_reactions
            } else {
                observed
            },
            language,
            timezone,
            caller: caller.to_owned(),
        };
        let (draft, usage) = match self.run(&input, method).await {
            Ok(result) => {
                if let Some(health) = &self.health {
                    health.record_success(RELAY_HEALTH_KEY).await;
                }
                result
            }
            Err(error) => {
                if let Some(health) = &self.health {
                    health
                        .record_failure(RELAY_HEALTH_KEY, error.to_string())
                        .await;
                }
                return Err(error);
            }
        };
        Ok(AiAnalysisResponse::generated(
            self.persist(event_id, &input, method, &draft, usage)
                .await?,
        ))
    }

    /// Seconds left before this (event, method) may be regenerated, or None when it is due.
    fn cooldown_remaining(&self, cached: &AiAnalysis) -> Option<i64> {
        if self.regenerate_cooldown_seconds <= 0 {
            return None;
        }
        let elapsed = (Utc::now() - cached.generated_at).num_seconds();
        let remaining = self.regenerate_cooldown_seconds - elapsed;
        (remaining > 0).then_some(remaining)
    }

    async fn run(
        &self,
        input: &AnalysisInput,
        method: AnalysisMethod,
    ) -> Result<(Draft, LlmUsageTotals), AppError> {
        let has_moves = input.observed_reactions.iter().any(has_any_change);
        let mut totals = LlmUsageTotals::default();
        // Without usable moves every method degrades to the numbers-and-rules briefing.
        if !has_moves || method == AnalysisMethod::NumbersOnly {
            let (raw, usage) = self.request(input, Stage::Expectation, false, None).await?;
            accumulate(&mut totals, usage);
            let draft = parse::parse_draft(&raw).map_err(AppError::Provider)?;
            return Ok((draft, totals));
        }
        if method == AnalysisMethod::SinglePass {
            let (raw, usage) = self.request(input, Stage::SinglePass, true, None).await?;
            accumulate(&mut totals, usage);
            let draft = parse::parse_draft(&raw).map_err(AppError::Provider)?;
            return Ok((draft, totals));
        }
        let (expectation_raw, usage) = self.request(input, Stage::Expectation, false, None).await?;
        accumulate(&mut totals, usage);
        let expectation = parse::parse_draft(&expectation_raw).map_err(AppError::Provider)?;
        let (comparison_raw, usage) = self
            .request(input, Stage::Comparison, true, Some(&expectation))
            .await?;
        accumulate(&mut totals, usage);
        let comparison = parse::parse_draft(&comparison_raw).map_err(AppError::Provider)?;
        // The numbers read and the chain were produced before the moves were revealed, so the
        // second pass may only validate them; it never rewrites the ex-ante reasoning.
        Ok((
            Draft {
                chain: if comparison.chain.is_empty() {
                    expectation.chain
                } else {
                    comparison.chain
                },
                data_analysis: if comparison.data_analysis.is_empty() {
                    expectation.data_analysis
                } else {
                    comparison.data_analysis
                },
                market_outlook: comparison.market_outlook,
                risks: comparison.risks.or(expectation.risks),
            },
            totals,
        ))
    }

    async fn request(
        &self,
        input: &AnalysisInput,
        stage: Stage,
        include_moves: bool,
        expectation: Option<&Draft>,
    ) -> Result<(String, TokenUsage), AppError> {
        let mut payload = self.payload(input, stage, include_moves, expectation);
        let mut dropped_json_mode = false;
        let mut switched_tokens = false;
        let mut attempts = 1i64;
        let started = std::time::Instant::now();
        // Relays and models disagree on two optional fields, and both announce the fix in their
        // own error body. Each is retried once, then the third failure is reported as-is.
        let outcome = loop {
            match self.execute(&payload).await {
                Ok(text) => break Ok((text, attempts, started)),
                Err(AppError::Relay { status, detail }) if matches!(status, 400 | 404 | 422) => {
                    if !dropped_json_mode && detail.contains("response_format") {
                        dropped_json_mode = true;
                        attempts += 1;
                        if let Some(object) = payload.as_object_mut() {
                            object.remove("response_format");
                        }
                        tracing::warn!("relay rejected response_format; retrying in plain mode");
                        continue;
                    }
                    if !switched_tokens
                        && crate::openai_compat::switch_token_parameter(&mut payload, &detail)
                    {
                        switched_tokens = true;
                        attempts += 1;
                        tracing::warn!(
                            "relay rejected the output-token parameter; retrying with the other name"
                        );
                        continue;
                    }
                    break Err(AppError::Relay { status, detail });
                }
                Err(other) => break Err(other),
            }
        };
        match outcome {
            Ok((text, attempts, started)) => {
                let usage = TokenUsage::from_body(&text);
                self.audit_call(input, stage, attempts, started, &text, Ok(()))
                    .await;
                Ok((text, usage))
            }
            Err(error) => {
                self.audit_call(input, stage, attempts, started, "", Err(&error))
                    .await;
                Err(error)
            }
        }
    }

    /// Appends one category-tagged record for a model call.
    ///
    /// Auditing must never break the feature, so failures here are logged and dropped.
    async fn audit_call(
        &self,
        input: &AnalysisInput,
        stage: Stage,
        attempts: i64,
        started: std::time::Instant,
        body: &str,
        outcome: Result<(), &AppError>,
    ) {
        let Some(audit) = &self.audit else {
            return;
        };
        let kind = match stage {
            Stage::Expectation => LlmKind::ExAnte,
            Stage::Comparison => LlmKind::Comparison,
            Stage::SinglePass => LlmKind::SinglePass,
        };
        let mut entry = LlmUsageEntry::ok(
            LlmScope::AiAnalysis,
            kind,
            self.config.model.clone(),
            endpoint_host(&self.endpoint),
        );
        entry.token_id = input.caller.clone();
        entry.event_id = Some(input.event.id);
        entry.language = Some(input.language.clone());
        entry.method = Some(match kind {
            LlmKind::ExAnte | LlmKind::Comparison => 2,
            LlmKind::SinglePass => 3,
            LlmKind::Translation => 0,
        });
        entry.usage = TokenUsage::from_body(body);
        entry.latency_ms = started.elapsed().as_millis() as i64;
        entry.attempts = attempts;
        if let Err(error) = outcome {
            entry = entry.failed(error.to_string());
        }
        audit.record_best_effort(entry).await;
    }

    async fn execute(&self, payload: &Value) -> Result<String, AppError> {
        let mut request = self.http.post(&self.endpoint).json(payload);
        // An `Authorization` entry in extra_headers replaces the bearer header rather than
        // being sent alongside it.
        if !self.extra_headers.overrides_authorization() {
            request = request.header("authorization", format!("Bearer {}", self.api_key));
        }
        let response = self
            .extra_headers
            .apply(request)
            .header("accept", "application/json")
            .timeout(Duration::from_secs(180))
            .send()
            .await?;
        let status = response.status();
        let text = response.text().await?;
        if !status.is_success() {
            // Keep the relay's own message: it names the real cause (bad key, quota, model).
            return Err(AppError::Relay {
                status: status.as_u16(),
                detail: crate::openai_compat::error_detail(status, &text),
            });
        }
        Ok(text)
    }

    fn payload(
        &self,
        input: &AnalysisInput,
        stage: Stage,
        include_moves: bool,
        expectation: Option<&Draft>,
    ) -> Value {
        let zone: Tz = normalize_timezone(&input.timezone)
            .parse()
            .unwrap_or(chrono_tz::UTC);
        let released_local = input
            .event
            .event_time
            .with_timezone(&zone)
            .format("%Y-%m-%d %H:%M")
            .to_string();
        let event = json!({
            "name": input.event.event,
            "country": input.event.country,
            "currency": input.event.currency,
            "importance": input.event.importance,
            "releasedAt": input.event.event_time.to_rfc3339(),
            "releasedAtLocal": released_local,
            "timeZone": zone.name(),
            "status": input.event.status.as_str(),
            "unit": input.event.unit,
            "actual": input.event.actual.map(|value| value.to_string()),
            "consensus": input.event.consensus.map(|value| value.to_string()),
            "forecast": input.event.forecast.map(|value| value.to_string()),
            "previous": input.event.previous.map(|value| value.to_string()),
        });
        let expected: Vec<Value> = input
            .expected_reactions
            .iter()
            .map(|(symbol, direction)| json!({"symbol": symbol, "ruleDirection": direction}))
            .collect();
        let observed: Vec<Value> = input
            .observed_reactions
            .iter()
            .map(|reaction| {
                json!({
                    "symbol": reaction.symbol.to_string(),
                    "baseline": reaction.baseline_price,
                    "unit": reaction.reaction_unit,
                    "change1m": reaction.change_1m,
                    "change5m": reaction.change_5m,
                    "change15m": reaction.change_15m,
                    "change30m": reaction.change_30m,
                    "change60m": reaction.change_60m,
                })
            })
            .collect();
        let mut user = serde_json::Map::new();
        user.insert("event".into(), event);
        user.insert("ruleSignal".into(), json!(input.macro_signal.as_str()));
        user.insert("rawSurprise".into(), json!(input.raw_surprise));
        user.insert("expectedReactions".into(), Value::Array(expected));
        if include_moves {
            user.insert("observedReactions".into(), Value::Array(observed));
        }
        if let Some(expectation) = expectation {
            user.insert(
                "exAnteExpectation".into(),
                json!({
                    "chain": expectation.chain,
                    "dataAnalysis": expectation.data_analysis,
                    "marketOutlook": expectation.market_outlook,
                    "risks": expectation.risks,
                }),
            );
        }
        let mut payload = serde_json::Map::new();
        payload.insert("model".into(), json!(self.config.model));
        payload.insert(
            "messages".into(),
            json!([
                {"role": "system", "content": system_prompt(&input.language, stage)},
                {"role": "user", "content": Value::Object(user).to_string()},
            ]),
        );
        payload.insert("stream".into(), json!(false));
        // Reasoning-capable models spend tokens on their thinking before the answer, so a
        // small budget truncates the JSON mid-object.
        payload.insert(
            self.config.max_tokens_param.clone(),
            json!(self.config.max_tokens),
        );
        payload.insert("response_format".into(), json!({"type": "json_object"}));
        // Provider-specific knobs last, so an explicit `extra_body.max_tokens` or
        // `reasoning_effort` wins over the defaults above.
        let mut payload = Value::Object(payload);
        crate::openai_compat::merge_extra_body(&mut payload, &self.config.extra_body);
        payload
    }

    async fn persist(
        &self,
        event_id: i64,
        input: &AnalysisInput,
        method: AnalysisMethod,
        draft: &Draft,
        usage: LlmUsageTotals,
    ) -> Result<AiAnalysis, AppError> {
        let chain_json = serde_json::to_string(&draft.chain)
            .map_err(|error| AppError::Internal(error.to_string()))?;
        let now = Utc::now();
        sqlx::query(
            r#"INSERT INTO ai_analysis
                 (event_id, language, method, timezone, revision, chain_json, data_analysis,
                  market_outlook, risks, model, generated_at,
                  prompt_tokens, completion_tokens, total_tokens, call_count)
               VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(event_id, language, method, timezone) DO UPDATE SET
                 revision = ai_analysis.revision + 1,
                 chain_json = excluded.chain_json,
                 data_analysis = excluded.data_analysis,
                 market_outlook = excluded.market_outlook,
                 risks = excluded.risks,
                 model = excluded.model,
                 generated_at = excluded.generated_at,
                 prompt_tokens = excluded.prompt_tokens,
                 completion_tokens = excluded.completion_tokens,
                 total_tokens = excluded.total_tokens,
                 call_count = excluded.call_count"#,
        )
        .bind(event_id)
        .bind(&input.language)
        .bind(i64::from(method.as_u8()))
        .bind(&input.timezone)
        .bind(&chain_json)
        .bind(&draft.data_analysis)
        .bind(&draft.market_outlook)
        .bind(&draft.risks)
        .bind(&self.config.model)
        .bind(now.to_rfc3339())
        .bind(usage.prompt_tokens)
        .bind(usage.completion_tokens)
        .bind(usage.total_tokens)
        .bind(usage.calls)
        .execute(&self.pool)
        .await?;
        Ok(AiAnalysis {
            event_id,
            method: method.as_u8(),
            revision: self
                .cached(event_id, &input.language, method, &input.timezone)
                .await?
                .map(|analysis| analysis.revision)
                .unwrap_or(1),
            chain: draft.chain.clone(),
            data_analysis: draft.data_analysis.clone(),
            market_outlook: draft.market_outlook.clone(),
            risks: draft.risks.clone(),
            model: self.config.model.clone(),
            generated_at: now,
            usage: Some(usage),
        })
    }
}

fn row_to_analysis(row: sqlx::sqlite::SqliteRow) -> Result<AiAnalysis, AppError> {
    let chain_json: String = row.try_get("chain_json")?;
    let generated_at: String = row.try_get("generated_at")?;
    let calls: i64 = row.try_get("call_count").unwrap_or(0);
    Ok(AiAnalysis {
        event_id: row.try_get("event_id")?,
        method: row.try_get::<i64, _>("method")? as u8,
        revision: row.try_get("revision")?,
        chain: serde_json::from_str(&chain_json).unwrap_or_default(),
        data_analysis: row.try_get("data_analysis")?,
        market_outlook: row.try_get("market_outlook")?,
        risks: row.try_get("risks")?,
        model: row.try_get("model")?,
        generated_at: generated_at.parse().unwrap_or_else(|_| Utc::now()),
        usage: (calls > 0).then(|| LlmUsageTotals {
            prompt_tokens: row.try_get("prompt_tokens").unwrap_or(0),
            completion_tokens: row.try_get("completion_tokens").unwrap_or(0),
            total_tokens: row.try_get("total_tokens").unwrap_or(0),
            calls,
        }),
    })
}

/// One minimal chat-completion used by `--check-ai` to verify a relay without touching the
/// database or a real event.
///
/// Returns the model's reply so the operator can see that the address, key and model all work,
/// and surfaces the relay's own error body when they do not.
pub async fn probe(http: &reqwest::Client, config: &AiConfig) -> Result<String, AppError> {
    let api_key = config.api_key()?;
    let endpoint = chat_endpoint(&config.base_url)
        .map_err(|error| AppError::Config(format!("ai.base_url: {error}")))?;
    let extra_headers = ExtraHeaders::from_map(&config.extra_headers);
    let payload = json!({
        "model": config.model,
        "messages": [{
            "role": "user",
            "content": "Reply with the single word: ok"
        }],
        "max_tokens": 16,
        "stream": false,
    });
    let mut request = http.post(&endpoint).json(&payload);
    if !extra_headers.overrides_authorization() {
        request = request.header("authorization", format!("Bearer {api_key}"));
    }
    let response = extra_headers
        .apply(request)
        .header("accept", "application/json")
        .timeout(std::time::Duration::from_secs(60))
        .send()
        .await?;
    let status = response.status();
    let text = response.text().await?;
    if !status.is_success() {
        return Err(AppError::Relay {
            status: status.as_u16(),
            detail: crate::openai_compat::error_detail(status, &text),
        });
    }
    let root: Value = serde_json::from_str(&text).map_err(|error| {
        AppError::Provider(format!(
            "relay returned a non chat-completions body: {error}"
        ))
    })?;
    let content = root
        .get("choices")
        .and_then(Value::as_array)
        .and_then(|choices| choices.first())
        .and_then(|choice| choice.get("message"))
        .and_then(|message| message.get("content"))
        .and_then(Value::as_str)
        .unwrap_or("")
        .trim();
    Ok(content.to_owned())
}

fn has_any_change(reaction: &MarketReaction) -> bool {
    [
        reaction.change_1m,
        reaction.change_5m,
        reaction.change_15m,
        reaction.change_30m,
        reaction.change_60m,
    ]
    .into_iter()
    .any(|value| value.is_some())
}

/// Adds one call's usage to the running total for a briefing; a two-pass method makes two calls.
fn accumulate(totals: &mut LlmUsageTotals, usage: TokenUsage) {
    totals.calls += 1;
    totals.prompt_tokens += usage.prompt_tokens.unwrap_or(0);
    totals.completion_tokens += usage.completion_tokens.unwrap_or(0);
    totals.total_tokens += usage.total_tokens.unwrap_or(0);
}

/// `zh`, `zh-CN`, `zh-Hans` collapse onto one cache entry, and so do the traditional variants.
fn normalize_language(language: &str) -> String {
    let value = language.trim().to_lowercase();
    if value.starts_with("zh-hant") || value.starts_with("zh-tw") || value.starts_with("zh-hk") {
        "zh-TW".to_owned()
    } else if value.starts_with("zh") {
        "zh-CN".to_owned()
    } else {
        "en".to_owned()
    }
}

fn normalize_timezone(timezone: &str) -> String {
    let value = timezone.trim();
    if value.is_empty() {
        return "UTC".to_owned();
    }
    match value.parse::<Tz>() {
        Ok(zone) => zone.name().to_owned(),
        Err(_) => "UTC".to_owned(),
    }
}

fn output_language(language: &str) -> &'static str {
    match language {
        "zh-TW" => "Traditional Chinese",
        "zh-CN" => "Simplified Chinese",
        _ => "English",
    }
}

/// A faithful port of the device-side prompt, so a briefing generated by the server reads
/// exactly like one the app used to generate itself.
fn system_prompt(language: &str, stage: Stage) -> String {
    let mut prompt = String::new();
    prompt.push_str(
        "You are a macro market analyst writing a post-release briefing for one economic event. ",
    );
    prompt.push_str("Use only the numbers and observations in the payload; never invent data. ");
    match stage {
        Stage::Expectation => prompt.push_str(
            "No market reaction data is provided and none exists yet in your reading: build the chain \
             and the outlook from the released numbers and the rule signal only, and never state or \
             guess what prices did. Frame the outlook as conditional expectations and say what would \
             confirm or invalidate each link. ",
        ),
        Stage::SinglePass => prompt.push_str(
            "Explain the causal transmission from the data surprise to asset prices step by step, and \
             treat the observed moves as evidence of which links held. ",
        ),
        Stage::Comparison => prompt.push_str(
            "You already produced an ex-ante expectation without seeing any prices; it is supplied as \
             exAnteExpectation. The payload now also carries the observed post-release moves. Reuse \
             the ex-ante chain in its original order, add a verdict to every link (confirmed, \
             contradicted or unobserved) based only on those moves, and you may add a link only when \
             the data requires it. Never rewrite the ex-ante reasoning to look prescient. ",
        ),
    }
    prompt.push_str("Reply with JSON only, no reasoning, no plan, no markdown fence, no text before or after: {");
    prompt.push_str("\"chain\":[{\"from\":\"...\",\"to\":\"...\",\"direction\":\"up|down|flat\",\"rationale\":\"...\"");
    if stage == Stage::Comparison {
        prompt.push_str(",\"verdict\":\"confirmed|contradicted|unobserved\"");
    }
    prompt.push_str("}],");
    prompt.push_str("\"dataAnalysis\":\"...\",\"marketOutlook\":\"...\",\"risks\":\"...\"}. ");
    prompt.push_str(
        "chain is ordered from the surprise to the final asset reaction using short node names ",
    );
    prompt.push_str("(for example \"CPI surprise\", \"real yields\", \"US dollar\", \"gold\"). ");
    prompt.push_str(
        "dataAnalysis: 2-4 sentences comparing actual with consensus, forecast and previous, ",
    );
    prompt.push_str("including revisions or caveats. ");
    match stage {
        Stage::Comparison => prompt.push_str(
            "marketOutlook: 3-5 sentences in one paragraph covering, in order, the ex-ante expectation, \
             what the observed moves actually showed (naming the numbers), and the revised view with \
             its invalidation condition. ",
        ),
        _ => prompt.push_str(
            "marketOutlook: 2-4 sentences on how rates, the dollar and risk assets are likely to trade \
             next and what would invalidate the view. ",
        ),
    }
    prompt.push_str(
        "risks: 1-2 sentences on the main risk to this chain. State uncertainty explicitly. ",
    );
    prompt.push_str("Quote release times in the reader's local time zone given by timeZone. ");
    prompt.push_str("Write in ");
    prompt.push_str(output_language(language));
    prompt.push_str(". ");
    prompt.push_str("This is descriptive analysis, not investment advice.");
    prompt
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn language_and_timezone_normalization_is_stable() {
        assert_eq!(normalize_language("zh-Hant-TW"), "zh-TW");
        assert_eq!(normalize_language("zh-rCN"), "zh-CN");
        assert_eq!(normalize_language("en-US"), "en");
        assert_eq!(normalize_timezone("Asia/Shanghai"), "Asia/Shanghai");
        assert_eq!(normalize_timezone("Nowhere/Nothing"), "UTC");
        assert_eq!(normalize_timezone(""), "UTC");
    }

    #[test]
    fn comparison_prompt_requires_a_verdict_per_link() {
        let prompt = system_prompt("zh-CN", Stage::Comparison);
        assert!(prompt.contains("verdict"));
        assert!(prompt.contains("Simplified Chinese"));
        let expectation = system_prompt("en", Stage::Expectation);
        assert!(!expectation.contains("verdict"));
    }
}
