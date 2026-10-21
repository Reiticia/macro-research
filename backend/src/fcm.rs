use std::{
    path::Path,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use jsonwebtoken::{EncodingKey, Header, encode};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::sync::Mutex;

use crate::{alert::HealthRegistry, error::AppError, model::EconomicEvent};

const TOKEN_URL: &str = "https://oauth2.googleapis.com/token";
const MESSAGING_SCOPE: &str = "https://www.googleapis.com/auth/firebase.messaging";
pub const HEALTH_KEY: &str = "push.fcm";

#[derive(Clone, Debug, Deserialize)]
struct ServiceAccount {
    project_id: String,
    client_email: String,
    private_key: String,
}

#[derive(Clone, Debug, Deserialize)]
struct TokenResponse {
    access_token: String,
    expires_in: u64,
}

#[derive(Clone)]
struct CachedToken {
    value: String,
    expires_at: std::time::Instant,
}

#[derive(Debug, Serialize)]
struct JwtClaims<'a> {
    iss: &'a str,
    scope: &'a str,
    aud: &'a str,
    iat: u64,
    exp: u64,
}

/// Firebase Cloud Messaging HTTP v1 sender.
///
/// Each device subscribes to an event topic when the user stars that event. The backend sends
/// only to the event topic, so it never needs to store device registration tokens or star lists.
pub struct FcmNotifier {
    client: Client,
    account: ServiceAccount,
    signing_key: EncodingKey,
    access_token: Mutex<Option<CachedToken>>,
    timeout: Duration,
    token_url: String,
    send_url: String,
    health: Option<Arc<HealthRegistry>>,
}

impl FcmNotifier {
    pub fn from_file(
        client: Client,
        path: impl AsRef<Path>,
        timeout: Duration,
    ) -> Result<Self, AppError> {
        let path = path.as_ref();
        let contents = std::fs::read_to_string(path).map_err(|error| {
            AppError::Config(format!(
                "cannot read FCM service account {}: {error}",
                path.display()
            ))
        })?;
        let account: ServiceAccount = serde_json::from_str(&contents).map_err(|error| {
            AppError::Config(format!(
                "invalid FCM service account {}: {error}",
                path.display()
            ))
        })?;
        if account.project_id.trim().is_empty()
            || account.client_email.trim().is_empty()
            || account.private_key.trim().is_empty()
        {
            return Err(AppError::Config(format!(
                "FCM service account {} is missing project_id, client_email, or private_key",
                path.display()
            )));
        }
        let signing_key = EncodingKey::from_rsa_pem(account.private_key.as_bytes())
            .map_err(|error| AppError::Config(format!("invalid FCM RSA private key: {error}")))?;
        let send_url = format!(
            "https://fcm.googleapis.com/v1/projects/{}/messages:send",
            account.project_id
        );
        Ok(Self {
            client,
            account,
            signing_key,
            access_token: Mutex::new(None),
            timeout: timeout.max(Duration::from_secs(5)),
            token_url: TOKEN_URL.into(),
            send_url,
            health: None,
        })
    }

    pub fn with_health(mut self, health: Arc<HealthRegistry>) -> Self {
        self.health = Some(health);
        self
    }

    pub async fn send_release(&self, event: &EconomicEvent) -> Result<(), AppError> {
        self.send_payload(release_payload(event)).await
    }

    /// No event means validate-only: exercise OAuth and FCM without delivering a message.
    /// An explicit event uses the same data-only topic route as a real release, but is clearly
    /// marked as a test and carries no fabricated macro values. No event state is changed.
    pub async fn send_test(&self, event: Option<&EconomicEvent>) -> Result<(), AppError> {
        self.send_payload(test_payload(event)).await
    }

    async fn send_payload(&self, payload: Value) -> Result<(), AppError> {
        let result = self.send_payload_inner(payload).await;
        if let Some(health) = &self.health {
            match &result {
                Ok(_) => health.record_success(HEALTH_KEY).await,
                Err(error) => health.record_failure(HEALTH_KEY, error.to_string()).await,
            }
        }
        result
    }

    async fn send_payload_inner(&self, payload: Value) -> Result<(), AppError> {
        let token = self.access_token().await?;
        let response = self
            .client
            .post(&self.send_url)
            .bearer_auth(token)
            .timeout(self.timeout)
            .json(&payload)
            .send()
            .await
            .map_err(|error| transport_error("send", error))?;
        let status = response.status();
        let body = response
            .text()
            .await
            .map_err(|error| transport_error("send response", error))?;
        if !status.is_success() {
            return Err(AppError::Provider(format!(
                "FCM send failed with HTTP {}: {}",
                status,
                response_error_codes(&body)
            )));
        }
        Ok(())
    }

    async fn access_token(&self) -> Result<String, AppError> {
        let mut cached = self.access_token.lock().await;
        if let Some(token) = cached.as_ref()
            && token.expires_at > std::time::Instant::now() + Duration::from_secs(60)
        {
            return Ok(token.value.clone());
        }
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|error| {
                AppError::Internal(format!("system clock is before Unix epoch: {error}"))
            })?
            .as_secs();
        let claims = JwtClaims {
            iss: &self.account.client_email,
            scope: MESSAGING_SCOPE,
            aud: TOKEN_URL,
            iat: now,
            exp: now + 3_600,
        };
        let assertion = encode(
            &Header::new(jsonwebtoken::Algorithm::RS256),
            &claims,
            &self.signing_key,
        )
        .map_err(|error| AppError::Internal(format!("FCM service account JWT failed: {error}")))?;
        let token = self.request_access_token(&assertion).await?;
        let value = token.access_token.clone();
        *cached = Some(CachedToken {
            value,
            expires_at: std::time::Instant::now()
                + Duration::from_secs(token.expires_in.saturating_sub(60).max(60)),
        });
        Ok(token.access_token)
    }

    async fn request_access_token(&self, assertion: &str) -> Result<TokenResponse, AppError> {
        let response = self
            .client
            .post(&self.token_url)
            .timeout(self.timeout)
            .form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
                ("assertion", assertion),
            ])
            .send()
            .await
            .map_err(|error| transport_error("OAuth token request", error))?;
        let status = response.status();
        let body = response
            .text()
            .await
            .map_err(|error| transport_error("OAuth token response", error))?;
        if !status.is_success() {
            return Err(AppError::Provider(format!(
                "FCM OAuth token request failed with HTTP {}: {}",
                status,
                response_error_codes(&body)
            )));
        }
        let token: TokenResponse = serde_json::from_str(&body).map_err(|_| {
            AppError::Provider("FCM OAuth token response was invalid (body omitted)".into())
        })?;
        if token.access_token.trim().is_empty() {
            return Err(AppError::Provider(
                "FCM OAuth token response was empty".into(),
            ));
        }
        Ok(token)
    }

    #[cfg(test)]
    pub(crate) fn for_test(base: &str) -> Self {
        Self {
            client: Client::builder().no_proxy().build().unwrap(),
            account: ServiceAccount {
                project_id: "test-project".into(),
                client_email: "test@example.invalid".into(),
                private_key: String::new(),
            },
            // Tests use a cached dummy token, never a real service account or private key.
            signing_key: EncodingKey::from_secret(b"not-an-rsa-key"),
            access_token: Mutex::new(Some(CachedToken {
                value: "test-access-token".into(),
                expires_at: std::time::Instant::now() + Duration::from_secs(3600),
            })),
            timeout: Duration::from_secs(5),
            token_url: format!("{base}/token"),
            send_url: format!("{base}/messages:send"),
            health: None,
        }
    }
}

pub fn topic_name(event_id: i64) -> String {
    format!("macro_event_{event_id}")
}

fn release_payload(event: &EconomicEvent) -> Value {
    let string_or_empty = |value: Option<String>| value.unwrap_or_default();
    json!({
        "message": {
            "topic": topic_name(event.id),
            "data": {
                "type": "economic_event_released",
                "eventId": event.id.to_string(),
                "event": event.event,
                "eventZhCn": string_or_empty(event.event_zh_cn.clone()),
                "eventZhTw": string_or_empty(event.event_zh_tw.clone()),
                "actual": string_or_empty(event.actual.map(|value| value.to_string())),
                "consensus": string_or_empty(event.consensus.map(|value| value.to_string()))
            },
            "android": {
                "priority": "HIGH"
            }
        }
    })
}

fn test_payload(event: Option<&EconomicEvent>) -> Value {
    let mut payload = json!({
        "message": {
            "topic": topic_name(event.map_or(0, |event| event.id)),
            "data": {
                "type": "economic_event_released",
                "eventId": event.map_or(0, |event| event.id).to_string(),
                "event": "[FCM TEST] Test notification",
                "eventZhCn": "【FCM 测试】测试通知",
                "eventZhTw": "【FCM 測試】測試通知",
                "actual": "",
                "consensus": ""
            },
            "android": { "priority": "HIGH" }
        }
    });
    if event.is_none() {
        payload["validate_only"] = json!(true);
    }
    payload
}

fn transport_error(stage: &str, error: reqwest::Error) -> AppError {
    // Do not include URLs or arbitrary proxy error bodies in logs/Telegram.
    let reason = if error.is_timeout() {
        "request timed out"
    } else if error.is_connect() {
        "connection failed (check DNS, TLS, proxy and Google connectivity)"
    } else if error.is_body() || error.is_decode() {
        "could not read response body"
    } else {
        "HTTP transport error"
    };
    AppError::Provider(format!("FCM {stage} failed: {reason}"))
}

/// Only return structured error codes, never an upstream body that may echo credentials or
/// request data. This text is also persisted in health state and sent to Telegram admins.
fn response_error_codes(body: &str) -> String {
    let body: Value = serde_json::from_str(body).unwrap_or(Value::Null);
    let mut codes = Vec::new();
    let mut add = |value: &Value| {
        if let Some(code) = value.as_str()
            && codes.len() < 8
            && !code.is_empty()
            && code.len() <= 128
            && code.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
            && !codes.iter().any(|existing| existing == code)
        {
            codes.push(code.to_owned());
        }
    };
    add(&body["error"]);
    add(&body["error"]["status"]);
    if let Some(details) = body["error"]["details"].as_array() {
        for detail in details {
            add(&detail["errorCode"]);
            add(&detail["reason"]);
        }
    }
    if codes.is_empty() {
        "unrecognized error response (body omitted)".into()
    } else {
        codes.join(", ")
    }
}

#[cfg(test)]
#[path = "fcm_tests.rs"]
pub(crate) mod tests;
