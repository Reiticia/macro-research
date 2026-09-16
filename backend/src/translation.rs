use std::{
    collections::{BTreeMap, HashMap},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
};

use async_trait::async_trait;
use reqwest::Client;
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;

use crate::{
    alert::HealthRegistry,
    error::AppError,
    llm_usage::{LlmKind, LlmScope, LlmUsageEntry, LlmUsageRepository, TokenUsage, endpoint_host},
    model::EconomicEvent,
    openai_compat::{ExtraHeaders, chat_endpoint, error_detail, merge_extra_body, strip_fences},
    repository::EventRepository,
};

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct EventNameTranslation {
    pub source: String,
    pub zh_cn: String,
    pub zh_tw: String,
}

#[async_trait]
pub trait EventNameTranslator: Send + Sync {
    async fn translate(
        &self,
        event_names: &[String],
    ) -> Result<Vec<EventNameTranslation>, AppError>;
}

pub struct OpenAiEventNameTranslator {
    client: Client,
    endpoint: String,
    model: String,
    api_key: String,
    extra_headers: ExtraHeaders,
    /// Free-form request-body parameters (thinking budget, temperature, …).
    extra_body: BTreeMap<String, serde_json::Value>,
    audit: Option<Arc<LlmUsageRepository>>,
    /// Set once the relay rejects `response_format`, so later batches skip the round trip.
    json_mode: AtomicBool,
}

impl OpenAiEventNameTranslator {
    pub fn new(
        client: Client,
        base_url: &str,
        model: impl Into<String>,
        api_key: impl Into<String>,
    ) -> Result<Self, AppError> {
        Self::with_headers(client, base_url, model, api_key, ExtraHeaders::default())
    }

    pub fn with_headers(
        client: Client,
        base_url: &str,
        model: impl Into<String>,
        api_key: impl Into<String>,
        extra_headers: ExtraHeaders,
    ) -> Result<Self, AppError> {
        Self::with_options(
            client,
            base_url,
            model,
            api_key,
            extra_headers,
            BTreeMap::new(),
        )
    }

    pub fn with_options(
        client: Client,
        base_url: &str,
        model: impl Into<String>,
        api_key: impl Into<String>,
        extra_headers: ExtraHeaders,
        extra_body: BTreeMap<String, serde_json::Value>,
    ) -> Result<Self, AppError> {
        let endpoint = chat_endpoint(base_url)
            .map_err(|error| AppError::Config(format!("translation.base_url: {error}")))?;
        let model = model.into();
        if model.trim().is_empty() {
            return Err(AppError::Config(
                "translation.model must not be empty".into(),
            ));
        }
        let api_key = api_key.into();
        if api_key.trim().is_empty() {
            return Err(AppError::Config(
                "translation API key must not be empty".into(),
            ));
        }
        Ok(Self {
            client,
            endpoint,
            model,
            api_key,
            extra_headers,
            extra_body,
            audit: None,
            json_mode: AtomicBool::new(true),
        })
    }

    /// Records every model call in the audit log with its category and token usage.
    pub fn with_audit(mut self, audit: Arc<LlmUsageRepository>) -> Self {
        self.audit = Some(audit);
        self
    }

    /// Audit handle that may be absent (auditing disabled in configuration).
    pub fn with_audit_opt(mut self, audit: Option<Arc<LlmUsageRepository>>) -> Self {
        self.audit = audit;
        self
    }

    /// Records one batch in the audit log. Never fails the request: a broken audit table must not
    /// stop event names from being translated.
    async fn audit_call(
        &self,
        attempts: i64,
        started: std::time::Instant,
        usage: TokenUsage,
        outcome: Result<(), &AppError>,
    ) {
        let Some(audit) = &self.audit else {
            return;
        };
        let mut entry = LlmUsageEntry::ok(
            LlmScope::Translation,
            LlmKind::Translation,
            self.model.clone(),
            endpoint_host(&self.endpoint),
        );
        entry.usage = usage;
        entry.latency_ms = started.elapsed().as_millis() as i64;
        entry.attempts = attempts;
        if let Err(error) = outcome {
            entry = entry.failed(error.to_string());
        }
        audit.record_best_effort(entry).await;
    }

    /// Sends one chat-completions request, adding `response_format` only when `json_mode` is on.
    ///
    /// The relay's own error body is surfaced: a bare `HTTP 401` is useless when the cause is a
    /// relay-specific message such as `invalid api key` or `insufficient quota`.
    async fn send(
        &self,
        payload: &serde_json::Value,
        json_mode: bool,
    ) -> Result<(ChatResponse, TokenUsage), AppError> {
        let mut body = payload.clone();
        if json_mode && let Some(object) = body.as_object_mut() {
            object.insert(
                "response_format".into(),
                serde_json::json!({"type": "json_object"}),
            );
        }
        let mut request = self.client.post(&self.endpoint).json(&body);
        // Azure-style relays authenticate with `api-key` and must not also receive a bearer
        // header; an explicit Authorization entry in extra_headers wins.
        if !self.extra_headers.overrides_authorization() {
            request = request.bearer_auth(&self.api_key);
        }
        let response = self.extra_headers.apply(request).send().await?;
        let status = response.status();
        let text = response.text().await?;
        if !status.is_success() {
            return Err(AppError::Provider(error_detail(status, &text)));
        }
        serde_json::from_str(&text)
            .map(|response| (response, TokenUsage::from_body(&text)))
            .map_err(|error| {
                AppError::Provider(format!(
                    "relay returned a non chat-completions body: {error}"
                ))
            })
    }
}

#[derive(Serialize)]
struct TranslationInput<'a> {
    events: Vec<TranslationInputRow<'a>>,
}

#[derive(Serialize)]
struct TranslationInputRow<'a> {
    id: usize,
    name: &'a str,
}

#[derive(Deserialize)]
struct TranslationOutput {
    translations: Vec<TranslationOutputRow>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct TranslationOutputRow {
    id: usize,
    zh_cn: String,
    zh_tw: String,
}

#[derive(Deserialize)]
struct ChatResponse {
    choices: Vec<ChatChoice>,
}

#[derive(Deserialize)]
struct ChatChoice {
    message: ChatMessage,
}

#[derive(Deserialize)]
struct ChatMessage {
    content: String,
}

#[async_trait]
impl EventNameTranslator for OpenAiEventNameTranslator {
    async fn translate(
        &self,
        event_names: &[String],
    ) -> Result<Vec<EventNameTranslation>, AppError> {
        if event_names.is_empty() {
            return Ok(Vec::new());
        }
        let input = TranslationInput {
            events: event_names
                .iter()
                .enumerate()
                .map(|(id, name)| TranslationInputRow { id, name })
                .collect(),
        };
        let mut payload = serde_json::json!({
            "model": self.model,
            "messages": [
                {
                    "role": "system",
                    "content": "You translate macroeconomic calendar event names. Return concise, standard financial terminology in Simplified Chinese and Traditional Chinese. Preserve numbers, periods, abbreviations, and meanings exactly. Return JSON only, with every input id exactly once, in this schema: {\"translations\":[{\"id\":0,\"zhCn\":\"...\",\"zhTw\":\"...\"}]}"
                },
                {
                    "role": "user",
                    "content": serde_json::to_string(&input).map_err(|error| AppError::Internal(error.to_string()))?
                }
            ]
        });
        // Provider-specific knobs (thinking budget, temperature, …) come from configuration.
        merge_extra_body(&mut payload, &self.extra_body);
        let use_json_mode = self.json_mode.load(Ordering::Relaxed);
        let started = std::time::Instant::now();
        let mut attempts = 1i64;
        let (response, usage) = match self.send(&payload, use_json_mode).await {
            Ok(reply) => reply,
            // `response_format` is optional in the OpenAI schema and many relay stations reject
            // it. Only that specific complaint disables it permanently; any other 400 is a real
            // configuration error and must not be retried or remembered.
            Err(AppError::Provider(detail)) if detail.contains("response_format") => {
                attempts += 1;
                self.json_mode.store(false, Ordering::Relaxed);
                tracing::warn!(
                    "translation relay rejected response_format; retrying in plain mode"
                );
                match self.send(&payload, false).await {
                    Ok(reply) => reply,
                    Err(error) => {
                        self.audit_call(attempts, started, TokenUsage::default(), Err(&error))
                            .await;
                        return Err(error);
                    }
                }
            }
            Err(error) => {
                self.audit_call(attempts, started, TokenUsage::default(), Err(&error))
                    .await;
                return Err(error);
            }
        };
        self.audit_call(attempts, started, usage, Ok(())).await;
        let content = response
            .choices
            .first()
            .ok_or_else(|| AppError::Provider("translation model returned no choices".into()))?
            .message
            .content
            .trim();
        let content = strip_fences(content);
        let output: TranslationOutput = serde_json::from_str(&content).map_err(|error| {
            AppError::Provider(format!("translation model returned invalid JSON: {error}"))
        })?;
        if output.translations.len() != event_names.len() {
            return Err(AppError::Provider(
                "translation model returned an incomplete result".into(),
            ));
        }
        let mut by_id = HashMap::with_capacity(output.translations.len());
        for row in output.translations {
            if row.id >= event_names.len()
                || row.zh_cn.trim().is_empty()
                || row.zh_tw.trim().is_empty()
                || by_id.insert(row.id, row).is_some()
            {
                return Err(AppError::Provider(
                    "translation model returned invalid translation rows".into(),
                ));
            }
        }
        event_names
            .iter()
            .enumerate()
            .map(|(id, source)| {
                let row = by_id.remove(&id).ok_or_else(|| {
                    AppError::Provider("translation model omitted an input id".into())
                })?;
                Ok(EventNameTranslation {
                    source: source.clone(),
                    zh_cn: row.zh_cn.trim().to_owned(),
                    zh_tw: row.zh_tw.trim().to_owned(),
                })
            })
            .collect()
    }
}

/// Health key reported to the alert registry; a failing relay must reach the Telegram admin,
/// otherwise a dead relay silently degrades every event name to English.
pub const RELAY_HEALTH_KEY: &str = "translation.relay";

#[derive(Clone)]
pub struct TranslationService {
    events: EventRepository,
    translator: Arc<dyn EventNameTranslator>,
    batch_size: usize,
    lock: Arc<Mutex<()>>,
    health: Option<Arc<HealthRegistry>>,
}

impl TranslationService {
    pub fn new(
        events: EventRepository,
        translator: Arc<dyn EventNameTranslator>,
        batch_size: usize,
    ) -> Self {
        Self {
            events,
            translator,
            batch_size: batch_size.clamp(1, 100),
            lock: Arc::new(Mutex::new(())),
            health: None,
        }
    }

    pub fn with_health(mut self, health: Arc<HealthRegistry>) -> Self {
        self.health = Some(health);
        self
    }

    pub async fn enrich(&self, events: &mut [EconomicEvent]) -> Result<(), AppError> {
        let names = unique_names(
            events
                .iter()
                .filter(|event| event.event_zh_cn.is_none() || event.event_zh_tw.is_none()),
        );
        let translations = self.translate_missing(&names).await?;
        for event in events {
            if let Some((zh_cn, zh_tw)) = translations.get(&event.event) {
                event.event_zh_cn = Some(zh_cn.clone());
                event.event_zh_tw = Some(zh_tw.clone());
            }
        }
        Ok(())
    }

    pub async fn backfill_existing(&self) -> Result<usize, AppError> {
        let names = self.events.untranslated_event_names().await?;
        let count = names.len();
        self.translate_missing(&names).await?;
        Ok(count)
    }

    async fn translate_missing(
        &self,
        names: &[String],
    ) -> Result<HashMap<String, (String, String)>, AppError> {
        if names.is_empty() {
            return Ok(HashMap::new());
        }
        let _guard = self.lock.lock().await;
        let mut cached = HashMap::new();
        for batch in names.chunks(500) {
            cached.extend(self.events.cached_event_name_translations(batch).await?);
        }
        if !cached.is_empty() {
            let rows: Vec<_> = cached
                .iter()
                .map(|(source, (zh_cn, zh_tw))| (source.clone(), zh_cn.clone(), zh_tw.clone()))
                .collect();
            self.events.save_event_name_translations(&rows).await?;
        }
        let missing: Vec<String> = names
            .iter()
            .filter(|name| !cached.contains_key(*name))
            .cloned()
            .collect();
        for batch in missing.chunks(self.batch_size) {
            let translated = match self.translator.translate(batch).await {
                Ok(translated) => {
                    if let Some(health) = &self.health {
                        health.record_success(RELAY_HEALTH_KEY).await;
                    }
                    translated
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
            let rows: Vec<_> = translated
                .iter()
                .map(|row| (row.source.clone(), row.zh_cn.clone(), row.zh_tw.clone()))
                .collect();
            self.events.save_event_name_translations(&rows).await?;
            for row in translated {
                cached.insert(row.source, (row.zh_cn, row.zh_tw));
            }
        }
        Ok(cached)
    }
}

fn unique_names<'a>(events: impl Iterator<Item = &'a EconomicEvent>) -> Vec<String> {
    let mut names = Vec::new();
    for event in events {
        if !names.contains(&event.event) {
            names.push(event.event.clone());
        }
    }
    names
}
