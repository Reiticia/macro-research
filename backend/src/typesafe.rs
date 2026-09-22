use std::{sync::Arc, time::Instant};

use async_trait::async_trait;
use reqwest::Client;
use serde_json::{Value, json};

use crate::{
    alert::HealthRegistry,
    error::AppError,
    llm_usage::{LlmKind, LlmScope, LlmUsageEntry, LlmUsageRepository, TokenUsage, endpoint_host},
    translation::{EventNameTranslation, TranslationVerdict, TranslationVerifier},
};

/// TypeSafe System One client used as a low-cost, structured translation reviewer.
///
/// Jev does not generate translations. The existing chat model still generates zh-CN/zh-TW;
/// Jev only returns one Noul probability per item indicating whether both translations pass the
/// review rubric.
pub struct TypeSafeVerifier {
    client: Client,
    endpoint: String,
    api_key: String,
    model: String,
    threshold: f64,
    audit: Option<Arc<LlmUsageRepository>>,
    health: Option<Arc<HealthRegistry>>,
}

pub const HEALTH_KEY: &str = "translation.typesafe";

impl TypeSafeVerifier {
    pub fn new(
        client: Client,
        base_url: &str,
        api_key: impl Into<String>,
        model: impl Into<String>,
        threshold: f64,
    ) -> Result<Self, AppError> {
        let api_key = api_key.into();
        if api_key.trim().is_empty() {
            return Err(AppError::Config(
                "typesafe.api_key must not be empty".into(),
            ));
        }
        let model = model.into();
        if model.trim().is_empty() {
            return Err(AppError::Config("typesafe.model must not be empty".into()));
        }
        if !threshold.is_finite() || !(0.0..=1.0).contains(&threshold) {
            return Err(AppError::Config(
                "typesafe.review_threshold must be between 0 and 1".into(),
            ));
        }
        Ok(Self {
            client,
            endpoint: system_one_endpoint(base_url)?,
            api_key,
            model,
            threshold,
            audit: None,
            health: None,
        })
    }

    pub fn with_audit_opt(mut self, audit: Option<Arc<LlmUsageRepository>>) -> Self {
        self.audit = audit;
        self
    }

    pub fn with_health(mut self, health: Arc<HealthRegistry>) -> Self {
        self.health = Some(health);
        self
    }

    async fn audit_call(
        &self,
        started: Instant,
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
        if let Err(error) = outcome {
            entry = entry.failed(error.to_string());
        }
        audit.record_best_effort(entry).await;
    }

    async fn request(&self, payload: &Value) -> Result<(Value, TokenUsage), AppError> {
        let response = self
            .client
            .post(&self.endpoint)
            .bearer_auth(&self.api_key)
            .header("accept", "application/json")
            .timeout(std::time::Duration::from_secs(60))
            .json(payload)
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
            AppError::Provider(format!("TypeSafe returned invalid JSON: {error}"))
        })?;
        let usage = typesafe_usage(&root);
        Ok((root, usage))
    }
}

#[async_trait]
impl TranslationVerifier for TypeSafeVerifier {
    async fn verify(
        &self,
        translations: &[EventNameTranslation],
    ) -> Result<Vec<TranslationVerdict>, AppError> {
        if translations.is_empty() {
            return Ok(Vec::new());
        }
        let started = Instant::now();
        let payload = build_payload(&self.model, translations);
        let result = match self.request(&payload).await {
            Ok((root, usage)) => {
                let parsed = parse_verdicts(&root, translations, self.threshold);
                match &parsed {
                    Ok(_) => self.audit_call(started, usage, Ok(())).await,
                    Err(error) => self.audit_call(started, usage, Err(error)).await,
                }
                parsed
            }
            Err(error) => {
                self.audit_call(started, TokenUsage::default(), Err(&error))
                    .await;
                Err(error)
            }
        };
        if let Some(health) = &self.health {
            match &result {
                Ok(_) => health.record_success(HEALTH_KEY).await,
                Err(error) => health.record_failure(HEALTH_KEY, error.to_string()).await,
            }
        }
        result
    }
}

fn system_one_endpoint(base_url: &str) -> Result<String, AppError> {
    let base = base_url.trim().trim_end_matches('/');
    if !base.starts_with("http://") && !base.starts_with("https://") {
        return Err(AppError::Config(format!(
            "typesafe.base_url must start with http:// or https:// (got {base})"
        )));
    }
    if base.ends_with("/systemone") {
        Ok(base.to_owned())
    } else if base.ends_with("/v1") {
        Ok(format!("{base}/systemone"))
    } else {
        Ok(format!("{base}/v1/systemone"))
    }
}

fn typesafe_usage(root: &Value) -> TokenUsage {
    let standard = TokenUsage::from_response(root);
    let usage = root.get("usage").unwrap_or(&Value::Null);
    let input = usage.get("input_tokens").and_then(as_i64);
    let output = usage.get("output_tokens").and_then(as_i64);
    TokenUsage {
        prompt_tokens: standard.prompt_tokens.or(input),
        completion_tokens: standard.completion_tokens.or(output),
        total_tokens: standard.total_tokens.or_else(|| match (input, output) {
            (Some(input), Some(output)) => Some(input + output),
            _ => None,
        }),
        reasoning_tokens: standard.reasoning_tokens,
        cached_tokens: standard.cached_tokens,
    }
}

fn as_i64(value: &Value) -> Option<i64> {
    match value {
        Value::Number(number) => number.as_i64(),
        Value::String(text) => text.trim().parse().ok(),
        _ => None,
    }
}

fn build_payload(model: &str, translations: &[EventNameTranslation]) -> Value {
    let state: Vec<Value> = translations
        .iter()
        .enumerate()
        .map(|(id, row)| {
            json!({
                "id": id,
                "source": row.source,
                "zhCn": row.zh_cn,
                "zhTw": row.zh_tw,
            })
        })
        .collect();
    let questions: serde_json::Map<String, Value> = translations
        .iter()
        .enumerate()
        .map(|(id, _)| {
            (
                format!("item_{id}"),
                json!({
                    "type": "noul",
                    "instructions": "Are both Chinese translations accurate, complete, idiomatic financial terminology, and equivalent to the English source?",
                    "criteria": {
                        "true": "Both translations are acceptable.",
                        "false": "At least one translation is wrong, incomplete, or not equivalent."
                    }
                }),
            )
        })
        .collect();
    json!({
        "state": state,
        "model": model,
        "questions": questions,
    })
}

fn parse_verdicts(
    root: &Value,
    translations: &[EventNameTranslation],
    threshold: f64,
) -> Result<Vec<TranslationVerdict>, AppError> {
    let answers = root
        .get("answers")
        .and_then(Value::as_object)
        .ok_or_else(|| AppError::Provider("TypeSafe response has no answers object".into()))?;
    translations
        .iter()
        .enumerate()
        .map(|(id, row)| {
            let probability = answers
                .get(&format!("item_{id}"))
                .and_then(|answer| answer.get("noul"))
                .and_then(Value::as_f64)
                .filter(|value| value.is_finite())
                .ok_or_else(|| {
                    AppError::Provider(format!(
                        "TypeSafe response is missing Noul answer for item {id}"
                    ))
                })?;
            let approved = probability >= threshold;
            Ok(TranslationVerdict {
                source: row.source.clone(),
                approved,
                reason: (!approved).then(|| {
                    format!(
                        "TypeSafe review probability {probability:.3} is below threshold {threshold:.3}"
                    )
                }),
            })
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn translation() -> EventNameTranslation {
        EventNameTranslation {
            source: "Nonfarm Payrolls".into(),
            zh_cn: "非农就业人数".into(),
            zh_tw: "非農就業人數".into(),
        }
    }

    #[test]
    fn resolves_system_one_endpoint_shapes() {
        assert_eq!(
            system_one_endpoint("https://api.typesafe.ai").unwrap(),
            "https://api.typesafe.ai/v1/systemone"
        );
        assert_eq!(
            system_one_endpoint("https://api.typesafe.ai/v1").unwrap(),
            "https://api.typesafe.ai/v1/systemone"
        );
        assert_eq!(
            system_one_endpoint("https://api.typesafe.ai/v1/systemone").unwrap(),
            "https://api.typesafe.ai/v1/systemone"
        );
    }

    #[test]
    fn parses_thresholded_noul_answers() {
        let root = json!({
            "answers": {
                "item_0": {"type": "noul", "noul": 0.91}
            }
        });
        let verdicts = parse_verdicts(&root, &[translation()], 0.85).unwrap();
        assert!(verdicts[0].approved);
        assert!(verdicts[0].reason.is_none());
    }

    #[test]
    fn parses_typesafe_usage_fields() {
        let root = json!({
            "usage": {"input_tokens": 368, "output_tokens": 22}
        });
        assert_eq!(
            typesafe_usage(&root),
            TokenUsage {
                prompt_tokens: Some(368),
                completion_tokens: Some(22),
                total_tokens: Some(390),
                reasoning_tokens: None,
                cached_tokens: None,
            }
        );
    }
}
