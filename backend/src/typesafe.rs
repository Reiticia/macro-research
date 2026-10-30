use std::{
    sync::Arc,
    time::{Duration, Instant},
};

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

/// Retry only transient transport/upstream failures, within the existing 60s total budget.
/// A successful response is never retried (including malformed JSON or missing answers).
/// Retried POSTs may still be billed upstream if the response was lost in transit.
pub(crate) async fn system_one_request(
    client: &Client,
    endpoint: &str,
    api_key: &str,
    payload: &Value,
) -> Result<(reqwest::StatusCode, String), AppError> {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(60);
    for attempt in 1..=3 {
        let result = async {
            let response = client
                .post(endpoint)
                .bearer_auth(api_key)
                .header("accept", "application/json")
                .timeout(deadline.saturating_duration_since(tokio::time::Instant::now()))
                .json(payload)
                .send()
                .await?;
            let status = response.status();
            let retry_after = response
                .headers()
                .get(reqwest::header::RETRY_AFTER)
                .and_then(|value| value.to_str().ok())
                .and_then(|value| {
                    value
                        .parse::<u64>()
                        .ok()
                        .map(Duration::from_secs)
                        .or_else(|| {
                            chrono::DateTime::parse_from_rfc2822(value)
                                .ok()
                                .map(|date| {
                                    (date.with_timezone(&chrono::Utc) - chrono::Utc::now())
                                        .to_std()
                                        .unwrap_or_default()
                                })
                        })
                });
            let body = response.text().await?;
            Ok::<_, reqwest::Error>((status, body, retry_after))
        }
        .await;
        let retryable = match &result {
            Ok((status, _, _)) => matches!(status.as_u16(), 429 | 500 | 502 | 503 | 504),
            Err(error) => {
                error.is_connect()
                    || error.is_timeout()
                    || error.is_request()
                    || error.is_body()
                    || error.is_decode()
            }
        };
        let delay = result
            .as_ref()
            .ok()
            .and_then(|(_, _, delay)| *delay)
            .unwrap_or_default()
            .max(Duration::from_millis(500 * attempt));
        if !retryable
            || attempt == 3
            || delay >= deadline.saturating_duration_since(tokio::time::Instant::now())
        {
            return result
                .map(|(status, body, _)| (status, body))
                .map_err(AppError::Http);
        }
        match &result {
            Ok((status, _, _)) => {
                tracing::warn!(attempt, %status, delay_ms = delay.as_millis() as u64,
                "TypeSafe transient upstream failure; retrying")
            }
            Err(error) => {
                tracing::warn!(attempt, error = ?error, delay_ms = delay.as_millis() as u64,
                "TypeSafe transport failure; retrying")
            }
        }
        tokio::time::sleep(delay).await;
    }
    unreachable!("the final attempt always returns")
}

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
        let (status, text) =
            system_one_request(&self.client, &self.endpoint, &self.api_key, payload).await?;
        if !status.is_success() {
            tracing::error!(%status, body = %text, "TypeSafe AI returned an error response");
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

    async fn review_once(
        &self,
        translations: &[EventNameTranslation],
    ) -> Result<Vec<TranslationVerdict>, AppError> {
        let started = Instant::now();
        let payload = build_payload(&self.model, translations);
        match self.request(&payload).await {
            Ok((root, usage)) => {
                let parsed = parse_verdicts(&root, translations, self.threshold);
                if let Ok(verdicts) = &parsed {
                    for (id, verdict) in verdicts.iter().enumerate() {
                        if let Some(probability) =
                            root["answers"][format!("item_{id}")]["noul"].as_f64()
                        {
                            tracing::info!(
                                source = %verdict.source,
                                zh_cn = %translations[id].zh_cn,
                                zh_tw = %translations[id].zh_tw,
                                model = %self.model,
                                review_scope = if translations.len() == 1 { "single" } else { "batch" },
                                probability,
                                threshold = self.threshold,
                                approved = verdict.approved,
                                "TypeSafe translation review verdict"
                            );
                        }
                    }
                }
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
        }
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
        // Jev scores can be influenced by other rows in the same batch. Only recheck
        // rejected rows in isolation: uncontested batches still cost a single request.
        let result: Result<Vec<TranslationVerdict>, AppError> = async {
            let mut verdicts = self.review_once(translations).await?;
            for index in 0..verdicts.len() {
                if translations.len() > 1 && !verdicts[index].approved {
                    verdicts[index] = self
                        .review_once(&translations[index..index + 1])
                        .await?
                        .remove(0);
                }
            }
            Ok(verdicts)
        }
        .await;
        if let Some(health) = &self.health {
            match &result {
                Ok(_) => health.record_success(HEALTH_KEY).await,
                Err(error) => {
                    health
                        .record_ai_failure(HEALTH_KEY, error.to_string())
                        .await
                }
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

    use std::sync::atomic::{AtomicUsize, Ordering};

    async fn mock_responses(
        statuses: Vec<u16>,
        retry_after: Option<&'static str>,
        body: &'static str,
    ) -> (String, Arc<AtomicUsize>, tokio::task::JoinHandle<()>) {
        use axum::{Router, http::StatusCode, response::IntoResponse, routing::post};
        let calls = Arc::new(AtomicUsize::new(0));
        let captured = calls.clone();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = format!("http://{}/v1/systemone", listener.local_addr().unwrap());
        let app = Router::new().route(
            "/v1/systemone",
            post(move || {
                let index = captured.fetch_add(1, Ordering::SeqCst);
                let status = statuses[index.min(statuses.len() - 1)];
                async move {
                    let mut response =
                        (StatusCode::from_u16(status).unwrap(), body).into_response();
                    if let Some(delay) = retry_after {
                        response
                            .headers_mut()
                            .insert("retry-after", delay.parse().unwrap());
                    }
                    response
                }
            }),
        );
        let server = tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        (endpoint, calls, server)
    }

    fn direct_client() -> Client {
        Client::builder().no_proxy().build().unwrap()
    }

    #[tokio::test]
    async fn transient_upstream_failure_is_retried() {
        let (endpoint, calls, server) = mock_responses(vec![503, 200], None, "{}").await;
        let (status, _) = system_one_request(&direct_client(), &endpoint, "key", &json!({}))
            .await
            .unwrap();
        assert!(status.is_success());
        assert_eq!(calls.load(Ordering::SeqCst), 2);
        server.abort();
    }

    #[tokio::test]
    async fn permanent_errors_and_successful_invalid_json_are_not_retried() {
        for status in [400, 401, 403, 404, 200] {
            let (endpoint, calls, server) = mock_responses(vec![status], None, "not JSON").await;
            let verifier =
                TypeSafeVerifier::new(direct_client(), &endpoint, "key", "jev", 0.85).unwrap();
            assert!(verifier.request(&json!({})).await.is_err());
            assert_eq!(calls.load(Ordering::SeqCst), 1);
            server.abort();
        }
    }

    #[tokio::test]
    async fn persistent_transient_failure_stops_after_three_attempts() {
        let (endpoint, calls, server) = mock_responses(vec![502], None, "unavailable").await;
        let verifier =
            TypeSafeVerifier::new(direct_client(), &endpoint, "key", "jev", 0.85).unwrap();
        assert!(matches!(
            verifier.request(&json!({})).await,
            Err(AppError::Relay { status: 502, .. })
        ));
        assert_eq!(calls.load(Ordering::SeqCst), 3);
        server.abort();
    }

    #[tokio::test]
    async fn retry_after_beyond_total_budget_returns_without_retrying() {
        let (endpoint, calls, server) =
            mock_responses(vec![429], Some("120"), "rate limited").await;
        let (status, _) = system_one_request(&direct_client(), &endpoint, "key", &json!({}))
            .await
            .unwrap();
        assert_eq!(status.as_u16(), 429);
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        server.abort();
    }

    #[tokio::test]
    async fn dropped_connection_is_retried() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let endpoint = format!("http://{}/v1/systemone", listener.local_addr().unwrap());
        let server = tokio::spawn(async move {
            for attempt in 0..2 {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut buffer = [0; 4096];
                // Only wait for request bytes; the first request is intentionally not processed.
                let received = socket.read(&mut buffer).await.unwrap();
                assert!(received > 0, "client closed before sending request bytes");
                if attempt == 1 {
                    socket
                        .write_all(
                            b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}",
                        )
                        .await
                        .unwrap();
                }
                // First connection deliberately closes without HTTP response headers.
            }
        });
        let (status, body) = system_one_request(&direct_client(), &endpoint, "key", &json!({}))
            .await
            .unwrap();
        assert!(status.is_success());
        assert_eq!(body, "{}");
        server.await.unwrap();
    }

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

    #[tokio::test]
    async fn rejected_batch_is_rechecked_one_name_at_a_time() {
        use axum::{Json, Router, routing::post};
        use std::sync::{
            Arc,
            atomic::{AtomicUsize, Ordering},
        };

        let requests = Arc::new(AtomicUsize::new(0));
        let captured = requests.clone();
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let server = tokio::spawn(async move {
            axum::serve(
                listener,
                Router::new().route(
                    "/v1/systemone",
                    post(move |Json(body): Json<Value>| {
                        let count = captured.clone();
                        async move {
                            count.fetch_add(1, Ordering::SeqCst);
                            let rows = body["state"].as_array().unwrap();
                            let answers: serde_json::Map<String, Value> = rows
                                .iter()
                                .enumerate()
                                .map(|(id, row)| {
                                    let score = if rows.len() > 1 {
                                        0.2
                                    } else if row["source"] == "Nonfarm Payrolls" {
                                        0.91
                                    } else {
                                        0.1
                                    };
                                    (format!("item_{id}"), json!({"noul": score}))
                                })
                                .collect();
                            Json(json!({"answers": answers}))
                        }
                    }),
                ),
            )
            .await
            .unwrap();
        });
        let verifier =
            TypeSafeVerifier::new(reqwest::Client::new(), &base, "key", "jev", 0.85).unwrap();
        let bad = EventNameTranslation {
            source: "CPI".into(),
            zh_cn: "香蕉".into(),
            zh_tw: "香蕉".into(),
        };
        let verdicts = verifier.verify(&[translation(), bad]).await.unwrap();
        assert!(verdicts[0].approved);
        assert!(!verdicts[1].approved);
        assert_eq!(requests.load(Ordering::SeqCst), 3);
        server.abort();
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
