use std::{
    collections::{BTreeMap, HashMap},
    sync::{Arc, Weak},
    time::Instant,
};

use reqwest::Client;
use serde_json::{Map, Value, json};
use tokio::sync::Mutex;

use crate::{
    alert::HealthRegistry,
    config::{MarketSelectionConfig, TypeSafeConfig},
    error::AppError,
    llm_usage::{LlmKind, LlmScope, LlmUsageEntry, LlmUsageRepository, TokenUsage, endpoint_host},
    model::{EconomicEvent, MarketSymbol},
    repository::EventRepository,
};

pub const HEALTH_KEY: &str = "market_selection.typesafe";

#[derive(Clone, Debug, PartialEq)]
pub struct MarketSelection {
    pub symbols: Vec<MarketSymbol>,
    pub probabilities: BTreeMap<String, f64>,
    pub source: &'static str,
    pub model: String,
}

#[derive(Clone)]
struct JevAnswers {
    probabilities: BTreeMap<String, f64>,
    usage: TokenUsage,
    partial: bool,
}

pub struct MarketSelector {
    client: Client,
    endpoint: String,
    api_key: Option<String>,
    model: String,
    config: MarketSelectionConfig,
    candidates: Vec<MarketSymbol>,
    events: EventRepository,
    audit: Option<Arc<LlmUsageRepository>>,
    health: Option<Arc<HealthRegistry>>,
    in_flight: Mutex<HashMap<i64, Weak<Mutex<()>>>>,
}

impl MarketSelector {
    pub fn new(
        client: Client,
        typesafe: &TypeSafeConfig,
        config: MarketSelectionConfig,
        candidates: Vec<MarketSymbol>,
        events: EventRepository,
        audit: Option<Arc<LlmUsageRepository>>,
        health: Option<Arc<HealthRegistry>>,
        api_key: Option<String>,
    ) -> Result<Self, AppError> {
        config.validate()?;
        let endpoint = system_one_endpoint(&typesafe.base_url)?;
        if typesafe.model.trim().is_empty() {
            return Err(AppError::Config(
                "typesafe.model must not be empty for market selection".into(),
            ));
        }
        Ok(Self {
            client,
            endpoint,
            api_key,
            model: typesafe.model.clone(),
            config,
            candidates: unique_symbols(candidates),
            events,
            audit,
            health,
            in_flight: Mutex::new(HashMap::new()),
        })
    }

    /// Return the persisted selection or create one once for this event. The per-event lock
    /// prevents the watcher and collector from issuing duplicate calls inside this process;
    /// INSERT OR IGNORE in the repository is the final cross-task idempotency guard.
    pub async fn selection_for(&self, event: &EconomicEvent) -> Result<MarketSelection, AppError> {
        if !self.config.enabled {
            return Ok(MarketSelection {
                symbols: self.candidates.clone(),
                probabilities: BTreeMap::new(),
                source: "all_symbols_fallback",
                model: self.model.clone(),
            });
        }
        if let Some(saved) = self.events.market_selection(event.id).await? {
            return Ok(self.sanitize_saved(saved));
        }
        let event_lock = {
            let mut locks = self.in_flight.lock().await;
            // Keep only weak references so completed event IDs do not accumulate for the
            // lifetime of this server process.
            locks.retain(|_, lock| lock.strong_count() > 0);
            if let Some(lock) = locks.get(&event.id).and_then(Weak::upgrade) {
                lock
            } else {
                let lock = Arc::new(Mutex::new(()));
                locks.insert(event.id, Arc::downgrade(&lock));
                lock
            }
        };
        let _guard = event_lock.lock().await;
        if let Some(saved) = self.events.market_selection(event.id).await? {
            return Ok(self.sanitize_saved(saved));
        }

        let selection = self.select_with_jev(event).await;
        match selection {
            Ok(selection) => {
                match self
                    .events
                    .save_market_selection(event.id, &selection)
                    .await
                {
                    Ok(saved) => Ok(saved),
                    Err(error) => {
                        tracing::error!(event_id = event.id, %error, "market selection could not be persisted; using all configured symbols for this run");
                        Ok(MarketSelection {
                            symbols: self.candidates.clone(),
                            probabilities: BTreeMap::new(),
                            source: "all_symbols_fallback",
                            model: self.model.clone(),
                        })
                    }
                }
            }
            Err(error) => {
                tracing::warn!(event_id = event.id, %error, "market symbol selection failed; applying safe fallback");
                let fallback = self.failure_fallback(event);
                match self.events.save_market_selection(event.id, &fallback).await {
                    Ok(saved) => Ok(saved),
                    Err(persist_error) => {
                        tracing::error!(event_id = event.id, %persist_error, "fallback market selection could not be persisted; using all configured symbols for this run");
                        Ok(MarketSelection {
                            symbols: self.candidates.clone(),
                            probabilities: BTreeMap::new(),
                            source: "all_symbols_fallback",
                            model: self.model.clone(),
                        })
                    }
                }
            }
        }
    }

    fn sanitize_saved(&self, mut selection: MarketSelection) -> MarketSelection {
        selection
            .symbols
            .retain(|symbol| self.candidates.contains(symbol));
        if selection.symbols.is_empty() {
            selection.symbols = self.candidates.clone();
            selection.source = "all_symbols_fallback";
        }
        selection
    }

    async fn select_with_jev(&self, event: &EconomicEvent) -> Result<MarketSelection, AppError> {
        if self.candidates.is_empty() {
            return Err(AppError::Config(
                "market.symbols has no valid candidates".into(),
            ));
        }
        let started = Instant::now();
        let request = build_request(&self.model, event, &self.candidates);
        let response = match self.api_key.as_deref().filter(|key| !key.trim().is_empty()) {
            Some(api_key) => self
                .client
                .post(&self.endpoint)
                .bearer_auth(api_key)
                .header("accept", "application/json")
                .timeout(std::time::Duration::from_secs(60))
                .json(&request)
                .send()
                .await
                .map_err(AppError::Http),
            None => Err(AppError::Config(
                "typesafe.api_key is required for market selection".into(),
            )),
        };
        let (answers, outcome) = match response {
            Ok(response) => {
                let status = response.status();
                match response.text().await {
                    Err(error) => (
                        None,
                        Err(AppError::Provider(format!(
                            "TypeSafe response body could not be read: {error}"
                        ))),
                    ),
                    Ok(body) if !status.is_success() => (
                        None,
                        Err(AppError::Relay {
                            status: status.as_u16(),
                            detail: crate::openai_compat::error_detail(status, &body),
                        }),
                    ),
                    Ok(body) => match serde_json::from_str::<Value>(&body) {
                        Ok(root) => match parse_answers(&root, &self.candidates) {
                            Ok(parsed) => (Some(parsed), Ok(())),
                            Err(error) => (None, Err(error)),
                        },
                        Err(error) => (
                            None,
                            Err(AppError::Provider(format!(
                                "TypeSafe returned invalid JSON: {error}"
                            ))),
                        ),
                    },
                }
            }
            Err(error) => (None, Err(error)),
        };
        let usage = answers
            .as_ref()
            .map(|answers| answers.usage)
            .unwrap_or_default();
        self.audit_call(
            event.id,
            started,
            usage,
            outcome.as_ref().map(|_| ()).map_err(|e| e),
        )
        .await;
        if answers.as_ref().is_some_and(|answer| answer.partial) {
            if let Some(health) = &self.health {
                health
                    .record_degraded(HEALTH_KEY, "Jev omitted one or more market symbol answers")
                    .await;
            }
            tracing::warn!(
                event_id = event.id,
                "Jev response omitted candidates; combining valid answers with category fallback"
            );
        } else if outcome.is_ok() {
            if let Some(health) = &self.health {
                health.record_success(HEALTH_KEY).await;
            }
        } else if let (Some(health), Err(error)) = (&self.health, &outcome) {
            health.record_failure(HEALTH_KEY, error.to_string()).await;
        }
        let answers = outcome.map(|_| answers.expect("successful parse contains answers"))?;
        let mut selected = threshold_symbols(
            &answers.probabilities,
            self.config.threshold,
            &self.candidates,
        );
        if !category_is_known(event) {
            return Ok(MarketSelection {
                symbols: self.candidates.clone(),
                probabilities: answers.probabilities,
                source: "all_symbols_fallback",
                model: self.model.clone(),
            });
        }
        let category_symbols = category_fallback(event, &self.candidates);
        let used_category_fallback = !category_symbols.is_empty();
        selected.extend(category_symbols);
        let source = if used_category_fallback {
            "category_fallback"
        } else {
            "jev"
        };
        if selected.is_empty() {
            selected.extend(self.candidates.iter().copied());
            return Ok(MarketSelection {
                symbols: unique_symbols(selected),
                probabilities: answers.probabilities,
                source: "all_symbols_fallback",
                model: self.model.clone(),
            });
        }
        Ok(MarketSelection {
            symbols: unique_symbols(selected),
            probabilities: answers.probabilities,
            source,
            model: self.model.clone(),
        })
    }

    fn failure_fallback(&self, event: &EconomicEvent) -> MarketSelection {
        let category = category_fallback(event, &self.candidates);
        let all = self.config.fallback_to_all_on_jev_error || category.is_empty();
        MarketSelection {
            symbols: if all {
                self.candidates.clone()
            } else {
                category
            },
            probabilities: BTreeMap::new(),
            source: if all {
                "all_symbols_fallback"
            } else {
                "category_fallback"
            },
            model: self.model.clone(),
        }
    }

    async fn audit_call(
        &self,
        event_id: i64,
        started: Instant,
        usage: TokenUsage,
        outcome: Result<(), &AppError>,
    ) {
        let Some(audit) = &self.audit else { return };
        let mut entry = LlmUsageEntry::ok(
            LlmScope::MarketSelection,
            LlmKind::MarketSelection,
            self.model.clone(),
            endpoint_host(&self.endpoint),
        );
        entry.event_id = Some(event_id);
        entry.usage = usage;
        entry.latency_ms = started.elapsed().as_millis() as i64;
        if let Err(error) = outcome {
            entry = entry.failed(error.to_string());
        }
        audit.record_best_effort(entry).await;
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

fn build_request(model: &str, event: &EconomicEvent, symbols: &[MarketSymbol]) -> Value {
    let mut questions = Map::new();
    for symbol in symbols {
        questions.insert(
            symbol.as_str().into(),
            json!({
                "type": "noul",
                "instructions": format!(
                    "Is collecting {} useful for measuring the immediate market reaction to this economic release?",
                    symbol.as_str()
                ),
                "criteria": {
                    "true": "The release can plausibly move this instrument directly or through a well-established macro transmission channel.",
                    "false": "The instrument is not meaningfully related, or collecting it would add little information about this release."
                }
            }),
        );
    }
    json!({
        "model": model,
        "state": {
            "event": event.event,
            "country": event.country,
            "currency": event.currency,
            "category": event.category,
            "importance": event.importance,
            "previous": event.previous.map(|value| value.to_string()),
            "consensus": event.consensus.map(|value| value.to_string()),
            "candidateSymbols": symbols.iter().map(|symbol| symbol.as_str()).collect::<Vec<_>>(),
        },
        "questions": questions,
    })
}

fn parse_answers(root: &Value, candidates: &[MarketSymbol]) -> Result<JevAnswers, AppError> {
    let answers = root
        .get("answers")
        .and_then(Value::as_object)
        .ok_or_else(|| AppError::Provider("TypeSafe response has no answers object".into()))?;
    let mut probabilities = BTreeMap::new();
    let mut partial = false;
    for symbol in candidates {
        let Some(answer) = answers.get(symbol.as_str()) else {
            partial = true;
            continue;
        };
        let Some(probability) = answer
            .get("noul")
            .and_then(Value::as_f64)
            .filter(|value| value.is_finite() && (0.0..=1.0).contains(value))
        else {
            partial = true;
            continue;
        };
        probabilities.insert(symbol.as_str().to_owned(), probability);
    }
    if probabilities.is_empty() {
        return Err(AppError::Provider(
            "TypeSafe response has no valid market symbol answers".into(),
        ));
    }
    Ok(JevAnswers {
        probabilities,
        usage: typesafe_usage(root),
        partial,
    })
}

fn typesafe_usage(root: &Value) -> TokenUsage {
    let usage = root.get("usage").unwrap_or(&Value::Null);
    let input = usage.get("input_tokens").and_then(as_i64);
    let output = usage.get("output_tokens").and_then(as_i64);
    TokenUsage {
        prompt_tokens: input,
        completion_tokens: output,
        total_tokens: match (input, output) {
            (Some(input), Some(output)) => Some(input + output),
            _ => None,
        },
        ..TokenUsage::default()
    }
}

fn as_i64(value: &Value) -> Option<i64> {
    match value {
        Value::Number(number) => number.as_i64(),
        Value::String(text) => text.trim().parse().ok(),
        _ => None,
    }
}

fn threshold_symbols(
    probabilities: &BTreeMap<String, f64>,
    threshold: f64,
    candidates: &[MarketSymbol],
) -> Vec<MarketSymbol> {
    probabilities
        .iter()
        .filter(|(_, probability)| **probability >= threshold)
        .filter_map(|(symbol, _)| symbol.parse().ok())
        .filter(|symbol| candidates.contains(symbol))
        .collect()
}

fn category_is_known(event: &EconomicEvent) -> bool {
    let category = normalize(&event.category);
    let name = normalize(&event.event);
    [
        "energy",
        "oil",
        "crude",
        "inventory",
        "crypto",
        "bitcoin",
        "ethereum",
        "employment",
        "inflation",
        "gdp",
        "businessconfidence",
        "interestrate",
        "centralbank",
        "retail",
        "trade",
    ]
    .iter()
    .any(|key| category.contains(key) || name.contains(key))
}

fn category_fallback(event: &EconomicEvent, candidates: &[MarketSymbol]) -> Vec<MarketSymbol> {
    use MarketSymbol::*;
    let category = normalize(&event.category);
    let name = normalize(&event.event);
    let wanted: &[MarketSymbol] = if ["energy", "oil", "crude", "inventory"]
        .iter()
        .any(|key| category.contains(key) || name.contains(key))
    {
        &[Wti, Brent]
    } else if ["crypto", "bitcoin", "ethereum"]
        .iter()
        .any(|key| category.contains(key) || name.contains(key))
    {
        &[Bitcoin, Ethereum]
    } else if [
        "employment",
        "inflation",
        "gdp",
        "businessconfidence",
        "interestrate",
        "centralbank",
        "retail",
        "trade",
    ]
    .iter()
    .any(|key| category.contains(key) || name.contains(key))
    {
        &[Dxy, Us2y, Us10y, Gold, Sp500, Nasdaq100, Bitcoin]
    } else {
        return Vec::new();
    };
    wanted
        .iter()
        .copied()
        .filter(|symbol| candidates.contains(symbol))
        .collect()
}

fn normalize(value: &str) -> String {
    value
        .chars()
        .filter(|c| c.is_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect()
}

fn unique_symbols(symbols: Vec<MarketSymbol>) -> Vec<MarketSymbol> {
    let mut unique = Vec::new();
    for symbol in symbols {
        if !unique.contains(&symbol) {
            unique.push(symbol);
        }
    }
    unique
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{config::TypeSafeConfig, model::EventStatus};
    use axum::{Json, Router, extract::State, routing::post};
    use chrono::Utc;
    use rust_decimal::Decimal;
    use sqlx::sqlite::SqlitePoolOptions;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn event(category: &str, name: &str) -> EconomicEvent {
        EconomicEvent {
            id: 3,
            provider: "test".into(),
            provider_id: "3".into(),
            release_group_id: None,
            country: "United States".into(),
            currency: Some("USD".into()),
            category: category.into(),
            event: name.into(),
            event_zh_cn: None,
            event_zh_tw: None,
            event_time: Utc::now(),
            importance: 3,
            actual: None,
            previous: Some(Decimal::new(15, 1)),
            consensus: Some(Decimal::new(18, 1)),
            forecast: None,
            unit: None,
            status: EventStatus::Watching,
            time_exact: true,
        }
    }

    #[test]
    fn jev_request_has_one_noul_per_candidate_symbol() {
        let candidates = [MarketSymbol::Dxy, MarketSymbol::Us10y];
        let payload = build_request(
            "jev-latest",
            &event("employment", "Nonfarm Payrolls"),
            &candidates,
        );
        assert_eq!(payload["questions"]["dxy"]["type"], "noul");
        assert_eq!(payload["questions"]["us10y"]["type"], "noul");
        assert_eq!(payload["state"]["candidateSymbols"][0], "dxy");
    }

    #[test]
    fn parses_threshold_inputs_and_marks_partial_answers() {
        let candidates = [MarketSymbol::Dxy, MarketSymbol::Gold];
        let parsed = parse_answers(&json!({"answers":{"dxy":{"noul":0.65}}}), &candidates).unwrap();
        assert!(parsed.partial);
        assert_eq!(parsed.probabilities["dxy"], 0.65);
        assert!(parse_answers(&json!({"answers":{"dxy":{"noul":1.1}}}), &candidates).is_err());
    }

    #[test]
    fn threshold_selection_includes_exact_boundary_and_only_candidates() {
        let probabilities = [
            ("dxy".to_owned(), 0.65),
            ("gold".to_owned(), 0.649),
            ("wti".to_owned(), 1.0),
        ]
        .into_iter()
        .collect();
        assert_eq!(
            threshold_symbols(
                &probabilities,
                0.65,
                &[MarketSymbol::Dxy, MarketSymbol::Gold]
            ),
            vec![MarketSymbol::Dxy],
        );
        assert_eq!(
            threshold_symbols(&probabilities, 1.0, &[MarketSymbol::Wti]),
            vec![MarketSymbol::Wti],
        );
    }

    #[tokio::test]
    async fn jev_selection_is_persisted_and_reused_without_second_request() {
        async fn mock_system_one(
            State(calls): State<Arc<AtomicUsize>>,
            Json(request): Json<Value>,
        ) -> Json<Value> {
            calls.fetch_add(1, Ordering::SeqCst);
            let questions = request["questions"].as_object().unwrap();
            let answers: Map<String, Value> = questions
                .keys()
                .map(|symbol| {
                    let probability = if symbol == "dxy" { 0.91 } else { 0.10 };
                    (symbol.clone(), json!({"type":"noul", "noul":probability}))
                })
                .collect();
            Json(
                json!({"model":"jev-1.13.0", "answers":answers, "usage":{"input_tokens":100,"output_tokens":20}}),
            )
        }

        let pool = SqlitePoolOptions::new()
            .max_connections(1)
            .connect("sqlite::memory:")
            .await
            .unwrap();
        sqlx::migrate!("./migrations").run(&pool).await.unwrap();
        let events = EventRepository::new(pool);
        let sample = event("employment", "Nonfarm Payrolls");
        let event_id = events
            .save_events(std::slice::from_ref(&sample))
            .await
            .unwrap()[0];
        let stored_event = events.get(event_id).await.unwrap();
        let calls = Arc::new(AtomicUsize::new(0));
        let app = Router::new()
            .route("/v1/systemone", post(mock_system_one))
            .with_state(calls.clone());
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base_url = format!("http://{}", listener.local_addr().unwrap());
        let server = tokio::spawn(async move {
            axum::serve(listener, app).await.unwrap();
        });
        let selector = MarketSelector::new(
            Client::new(),
            &TypeSafeConfig {
                enabled: true,
                base_url,
                api_key: "test-key".into(),
                model: "jev-latest".into(),
                review_threshold: 0.85,
            },
            MarketSelectionConfig {
                enabled: true,
                threshold: 0.65,
                fallback_to_all_on_jev_error: true,
            },
            vec![MarketSymbol::Dxy, MarketSymbol::Wti],
            events,
            None,
            None,
            Some("test-key".into()),
        )
        .unwrap();
        let selected = selector.selection_for(&stored_event).await.unwrap();
        assert_eq!(selected.symbols, vec![MarketSymbol::Dxy]);
        assert_eq!(selected.source, "category_fallback");
        let reused = selector.selection_for(&stored_event).await.unwrap();
        assert_eq!(reused, selected);
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        server.abort();
    }

    #[test]
    fn category_fallbacks_are_restricted_to_configured_candidates() {
        let candidates = [MarketSymbol::Wti, MarketSymbol::Gold];
        assert_eq!(
            category_fallback(&event("energy", "Crude Oil Stocks Change"), &candidates),
            vec![MarketSymbol::Wti]
        );
        assert!(category_fallback(&event("unknown", "Some event"), &candidates).is_empty());
    }
}
