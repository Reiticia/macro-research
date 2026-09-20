use std::collections::BTreeMap;

use crate::error::AppError;

/// Resolves the chat-completions endpoint from a user-supplied base URL.
///
/// Relay stations document their address in several shapes, so all of them are accepted:
///
/// - `https://relay.example.com/v1` → `.../v1/chat/completions`
/// - `https://relay.example.com/v1/chat/completions` → used verbatim
/// - `https://relay.example.com` → `.../chat/completions` (non-standard but common for
///   self-hosted one-api/new-api instances mounted at the root)
pub fn chat_endpoint(base_url: &str) -> Result<String, AppError> {
    let base = base_url.trim().trim_end_matches('/');
    if base.is_empty() {
        return Err(AppError::Config("base_url must not be empty".into()));
    }
    if !base.starts_with("http://") && !base.starts_with("https://") {
        return Err(AppError::Config(format!(
            "base_url must start with http:// or https:// (got {base})"
        )));
    }
    if base.ends_with("/chat/completions") {
        return Ok(base.to_owned());
    }
    Ok(format!("{base}/chat/completions"))
}

/// Headers a relay may require beyond `Authorization: Bearer`.
///
/// OpenRouter-style gateways want `HTTP-Referer` / `X-Title`; Azure-style deployments use
/// `api-key` instead of the bearer header. An entry named `Authorization` therefore wins over
/// the default bearer header instead of being sent twice.
#[derive(Clone, Debug, Default)]
pub struct ExtraHeaders(Vec<(String, String)>);

impl ExtraHeaders {
    pub fn from_map(map: &BTreeMap<String, String>) -> Self {
        Self(
            map.iter()
                .map(|(name, value)| (name.trim().to_owned(), value.trim().to_owned()))
                .filter(|(name, value)| !name.is_empty() && !value.is_empty())
                .collect(),
        )
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    pub fn overrides_authorization(&self) -> bool {
        self.0
            .iter()
            .any(|(name, _)| name.eq_ignore_ascii_case("authorization"))
    }

    pub fn apply(&self, mut builder: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
        for (name, value) in &self.0 {
            builder = builder.header(name.as_str(), value.as_str());
        }
        builder
    }
}

/// Turns a failed relay response into a message an administrator can act on.
///
/// A bare "HTTP 401" is useless when the cause is a relay-specific body such as
/// `{"error":{"message":"invalid api key"}}`, so the body is included, truncated.
pub fn error_detail(status: reqwest::StatusCode, body: &str) -> String {
    let detail = body.trim();
    if detail.is_empty() {
        return format!("HTTP {}", status.as_u16());
    }
    let mut snippet: String = detail.chars().take(300).collect();
    if detail.chars().count() > 300 {
        snippet.push('…');
    }
    format!("HTTP {}: {snippet}", status.as_u16())
}

/// Merges provider-specific parameters into a request body.
///
/// This is how a reasoning budget, thinking toggle or sampling parameter reaches the model
/// without the server having to model every vendor's schema. `messages` is protected: letting a
/// config file replace the prompt would silently produce garbage analyses.
pub fn merge_extra_body(
    payload: &mut serde_json::Value,
    extra: &BTreeMap<String, serde_json::Value>,
) {
    if extra.is_empty() {
        return;
    }
    let Some(object) = payload.as_object_mut() else {
        return;
    };
    for (key, value) in extra {
        if key.eq_ignore_ascii_case("messages") {
            tracing::warn!("extra_body.messages is ignored; the prompt is owned by the server");
            continue;
        }
        object.insert(key.clone(), value.clone());
    }
}

/// Rewrites the output-token parameter when a relay rejects the classic name.
///
/// gpt-5 and the o-series answer `Unsupported parameter: 'max_tokens' ... Use
/// 'max_completion_tokens' instead`, so the request is retried once with the other name.
pub fn switch_token_parameter(payload: &mut serde_json::Value, detail: &str) -> bool {
    if !mentions_output_tokens(detail) {
        return false;
    }
    let Some(object) = payload.as_object_mut() else {
        return false;
    };
    let (from, to) = if object.contains_key("max_tokens") {
        ("max_tokens", "max_completion_tokens")
    } else if object.contains_key("max_completion_tokens") {
        ("max_completion_tokens", "max_tokens")
    } else {
        return false;
    };
    let Some(value) = object.remove(from) else {
        return false;
    };
    object.insert(to.to_owned(), value);
    true
}

/// True when the relay's error talks about the output-token limit at all.
///
/// The name varies (`max_tokens`, `max_completion_tokens`, some relays say `max_output_tokens`),
/// and substring-matching only the classic name would miss the very error that names the fix.
fn mentions_output_tokens(detail: &str) -> bool {
    let lowered = detail.to_ascii_lowercase();
    lowered.contains("max_") && lowered.contains("token")
}

/// True when the relay rejected `response_format`, which is optional in the OpenAI schema and
/// frequently unsupported by relay stations. The request is retried without it.
pub fn rejects_json_mode(status: reqwest::StatusCode) -> bool {
    matches!(status.as_u16(), 400 | 404 | 422)
}

/// Models often wrap JSON in a markdown fence; the fence is not part of the payload.
pub fn strip_fences(raw: &str) -> String {
    let trimmed = raw.trim();
    let without_open = trimmed
        .strip_prefix("```")
        .map(|rest| rest.split_once('\n').map(|(_, body)| body).unwrap_or(rest))
        .unwrap_or(trimmed);
    without_open
        .trim_end()
        .strip_suffix("```")
        .unwrap_or(without_open)
        .trim()
        .to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_documented_relay_address_shape_resolves() {
        for (input, expected) in [
            (
                "https://relay.example.com/v1",
                "https://relay.example.com/v1/chat/completions",
            ),
            (
                "https://relay.example.com/v1/",
                "https://relay.example.com/v1/chat/completions",
            ),
            (
                "https://relay.example.com/v1/chat/completions",
                "https://relay.example.com/v1/chat/completions",
            ),
            (
                "https://relay.example.com",
                "https://relay.example.com/chat/completions",
            ),
            (
                "  https://relay.example.com/openai/v1  ",
                "https://relay.example.com/openai/v1/chat/completions",
            ),
        ] {
            assert_eq!(chat_endpoint(input).unwrap(), expected, "input: {input}");
        }
        assert!(chat_endpoint("").is_err());
        assert!(chat_endpoint("relay.example.com/v1").is_err());
    }

    #[test]
    fn extra_headers_can_replace_the_bearer_header() {
        let mut map = BTreeMap::new();
        map.insert("api-key".to_owned(), "azure-key".to_owned());
        map.insert("  ".to_owned(), "ignored".to_owned());
        let headers = ExtraHeaders::from_map(&map);
        assert!(!headers.overrides_authorization());
        assert!(!headers.is_empty());
        assert_eq!(headers.0.len(), 1);

        let mut override_map = BTreeMap::new();
        override_map.insert("Authorization".to_owned(), "Basic abc".to_owned());
        assert!(ExtraHeaders::from_map(&override_map).overrides_authorization());
    }

    #[test]
    fn extra_body_is_merged_but_never_replaces_the_prompt() {
        let mut extra = BTreeMap::new();
        extra.insert("reasoning_effort".to_owned(), serde_json::json!("low"));
        extra.insert(
            "thinking".to_owned(),
            serde_json::json!({"type": "enabled", "budget_tokens": 2048}),
        );
        extra.insert("messages".to_owned(), serde_json::json!([{"role": "user"}]));
        extra.insert("max_tokens".to_owned(), serde_json::json!(8192));

        let mut payload = serde_json::json!({
            "model": "m",
            "messages": [{"role": "system", "content": "keep me"}],
            "max_tokens": 1024,
        });
        merge_extra_body(&mut payload, &extra);

        assert_eq!(payload["reasoning_effort"], "low");
        assert_eq!(payload["thinking"]["budget_tokens"], 2048);
        // An explicit value wins over the server default.
        assert_eq!(payload["max_tokens"], 8192);
        // The prompt is never replaced by configuration.
        assert_eq!(payload["messages"][0]["content"], "keep me");
    }

    #[test]
    fn the_output_token_parameter_follows_the_relay_error() {
        let mut payload = serde_json::json!({"max_tokens": 4096});
        assert!(switch_token_parameter(
            &mut payload,
            "Unsupported parameter: 'max_tokens' is not supported with this model"
        ));
        assert!(payload.get("max_tokens").is_none());
        assert_eq!(payload["max_completion_tokens"], 4096);

        // And back, for a relay that wants the classic name.
        assert!(switch_token_parameter(
            &mut payload,
            "unknown field max_completion_tokens"
        ));
        assert_eq!(payload["max_tokens"], 4096);

        // Unrelated errors must not rewrite the payload.
        let mut untouched = serde_json::json!({"max_tokens": 1});
        assert!(!switch_token_parameter(&mut untouched, "quota exceeded"));
        assert_eq!(untouched["max_tokens"], 1);
    }

    #[test]
    fn failure_details_include_the_relay_body() {
        let detail = error_detail(reqwest::StatusCode::UNAUTHORIZED, "{\"error\":\"bad key\"}");
        assert!(detail.contains("401"), "{detail}");
        assert!(detail.contains("bad key"), "{detail}");
        assert_eq!(
            error_detail(reqwest::StatusCode::BAD_GATEWAY, "  "),
            "HTTP 502"
        );
        assert!(rejects_json_mode(reqwest::StatusCode::BAD_REQUEST));
        assert!(!rejects_json_mode(reqwest::StatusCode::TOO_MANY_REQUESTS));
    }

    #[test]
    fn fences_are_stripped_like_the_device_parser_does() {
        assert_eq!(strip_fences("```json\n{\"a\":1}\n```"), "{\"a\":1}");
        assert_eq!(strip_fences("```\n[1]\n```"), "[1]");
        assert_eq!(strip_fences("  {\"a\":1}  "), "{\"a\":1}");
    }
}
