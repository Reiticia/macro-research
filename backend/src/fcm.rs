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
        Ok(Self {
            client,
            account,
            signing_key,
            access_token: Mutex::new(None),
            timeout: timeout.max(Duration::from_secs(5)),
            health: None,
        })
    }

    pub fn with_health(mut self, health: Arc<HealthRegistry>) -> Self {
        self.health = Some(health);
        self
    }

    pub async fn send_release(&self, event: &EconomicEvent) -> Result<(), AppError> {
        let result = self.send_release_inner(event).await;
        if let Some(health) = &self.health {
            match &result {
                Ok(_) => health.record_success(HEALTH_KEY).await,
                Err(error) => health.record_failure(HEALTH_KEY, error.to_string()).await,
            }
        }
        result
    }

    async fn send_release_inner(&self, event: &EconomicEvent) -> Result<(), AppError> {
        let token = self.access_token().await?;
        let endpoint = format!(
            "https://fcm.googleapis.com/v1/projects/{}/messages:send",
            self.account.project_id
        );
        let response = self
            .client
            .post(endpoint)
            .bearer_auth(token)
            .timeout(self.timeout)
            .json(&release_payload(event))
            .send()
            .await?;
        let status = response.status();
        let body = response.text().await?;
        if !status.is_success() {
            return Err(AppError::Provider(format!(
                "FCM send failed with HTTP {}: {}",
                status,
                response_preview(&body)
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
        let response = self
            .client
            .post(TOKEN_URL)
            .timeout(self.timeout)
            .form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"),
                ("assertion", assertion.as_str()),
            ])
            .send()
            .await?;
        let status = response.status();
        let body = response.text().await?;
        if !status.is_success() {
            return Err(AppError::Provider(format!(
                "FCM OAuth token request failed with HTTP {}: {}",
                status,
                response_preview(&body)
            )));
        }
        let token: TokenResponse = serde_json::from_str(&body).map_err(|error| {
            AppError::Provider(format!("FCM OAuth token response was invalid: {error}"))
        })?;
        if token.access_token.trim().is_empty() {
            return Err(AppError::Provider(
                "FCM OAuth token response was empty".into(),
            ));
        }
        let value = token.access_token.clone();
        *cached = Some(CachedToken {
            value,
            expires_at: std::time::Instant::now()
                + Duration::from_secs(token.expires_in.saturating_sub(60).max(60)),
        });
        Ok(token.access_token)
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

fn response_preview(body: &str) -> String {
    let mut preview: String = body.chars().take(512).collect();
    if body.chars().count() > 512 {
        preview.push('…');
    }
    preview
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{EconomicEvent, EventStatus};
    use rust_decimal::Decimal;

    fn event() -> EconomicEvent {
        EconomicEvent {
            id: 143,
            provider: "test".into(),
            provider_id: "test-143".into(),
            release_group_id: None,
            country: "United States".into(),
            currency: Some("USD".into()),
            category: "employment".into(),
            event: "Nonfarm Payrolls".into(),
            event_zh_cn: Some("非农就业人数".into()),
            event_zh_tw: Some("非農就業人數".into()),
            event_time: chrono::Utc::now(),
            importance: 3,
            actual: Some(Decimal::new(100, 0)),
            previous: None,
            consensus: Some(Decimal::new(90, 0)),
            forecast: None,
            unit: None,
            status: EventStatus::Released,
            time_exact: true,
        }
    }

    #[test]
    fn topic_names_are_stable_and_scoped() {
        assert_eq!(topic_name(143), "macro_event_143");
    }

    #[test]
    fn release_payload_is_data_only_and_localizable() {
        let payload = release_payload(&event());
        assert_eq!(payload["message"]["topic"], "macro_event_143");
        assert_eq!(
            payload["message"]["data"]["type"],
            "economic_event_released"
        );
        assert_eq!(payload["message"]["data"]["eventZhCn"], "非农就业人数");
        assert_eq!(payload["message"]["android"]["priority"], "HIGH");
        assert!(payload["message"].get("notification").is_none());
    }
}
