use axum::{
    Json,
    http::StatusCode,
    response::{IntoResponse, Response},
};
use serde_json::json;

#[derive(Debug, thiserror::Error)]
pub enum AppError {
    #[error("configuration error: {0}")]
    Config(String),
    #[error("database error: {0}")]
    Database(#[from] sqlx::Error),
    #[error("http provider error: {}", http_error_detail(.0))]
    Http(#[from] reqwest::Error),
    #[error("provider data error: {0}")]
    Provider(String),
    #[error("resource not found")]
    NotFound,
    #[error("invalid request: {0}")]
    InvalidRequest(String),
    #[error("unauthorized")]
    Unauthorized,
    #[error("quota exceeded")]
    QuotaExceeded { retry_after_seconds: i64 },
    #[error("analysis is not available: {0}")]
    AnalysisUnavailable(String),
    /// The OpenAI-compatible relay answered with an error. The detail is exposed to the caller
    /// on purpose: it names the real cause (bad key, quota, unknown model) and the API is
    /// token-protected, so only the operator and their users can read it.
    #[error("relay error HTTP {status}: {detail}")]
    Relay { status: u16, detail: String },
    #[error("internal error: {0}")]
    Internal(String),
}

/// reqwest's Display omits the source (DNS, TLS, proxy, connection reset, etc.).
/// Keep that chain in operator logs/health alerts; public API errors remain generic.
fn http_error_detail(error: &reqwest::Error) -> String {
    use std::error::Error;

    let kind = if error.is_timeout() {
        "timeout"
    } else if error.is_connect() {
        "connection"
    } else {
        "transport"
    };
    let mut detail = format!("{kind}: {error}");
    let mut source = error.source();
    for _ in 0..8 {
        let Some(cause) = source else { break };
        detail.push_str(&format!(": {cause}"));
        source = cause.source();
    }
    detail
}

impl AppError {
    /// Stable machine-readable code so the Android client can localize its own message.
    pub fn code(&self) -> &'static str {
        match self {
            Self::Config(_) => "config_error",
            Self::Database(_) => "database_error",
            Self::Http(_) => "upstream_error",
            Self::Provider(_) => "provider_error",
            Self::NotFound => "not_found",
            Self::InvalidRequest(_) => "invalid_request",
            Self::Unauthorized => "unauthorized",
            Self::QuotaExceeded { .. } => "quota_exceeded",
            Self::AnalysisUnavailable(_) => "analysis_unavailable",
            Self::Relay { .. } => "relay_error",
            Self::Internal(_) => "internal_error",
        }
    }

    fn public_message(&self) -> String {
        match self {
            Self::Config(_)
            | Self::Database(_)
            | Self::Http(_)
            | Self::Provider(_)
            | Self::Internal(_) => "internal server error".to_owned(),
            other => other.to_string(),
        }
    }
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        let status = match &self {
            Self::NotFound => StatusCode::NOT_FOUND,
            Self::InvalidRequest(_) => StatusCode::BAD_REQUEST,
            Self::Unauthorized => StatusCode::UNAUTHORIZED,
            Self::QuotaExceeded { .. } => StatusCode::TOO_MANY_REQUESTS,
            Self::AnalysisUnavailable(_) => StatusCode::CONFLICT,
            Self::Relay { .. } => StatusCode::BAD_GATEWAY,
            Self::Config(_)
            | Self::Database(_)
            | Self::Http(_)
            | Self::Provider(_)
            | Self::Internal(_) => {
                tracing::error!(error = %self, "request failed");
                StatusCode::INTERNAL_SERVER_ERROR
            }
        };
        let retry_after = match &self {
            Self::QuotaExceeded {
                retry_after_seconds,
            } => Some((*retry_after_seconds).max(1)),
            _ => None,
        };
        let body = Json(json!({
            "error": {
                "code": self.code(),
                "message": self.public_message(),
            }
        }));
        match retry_after {
            Some(seconds) => (status, [("retry-after", seconds.to_string())], body).into_response(),
            None => (status, body).into_response(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::error::Error;

    #[tokio::test]
    async fn http_alert_includes_timeout_and_underlying_cause_but_public_message_does_not() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        // Keep the listener open without responding to force a request timeout.
        let error = reqwest::Client::builder()
            .no_proxy()
            .build()
            .unwrap()
            .get(format!("http://{}", listener.local_addr().unwrap()))
            .timeout(std::time::Duration::from_millis(50))
            .send()
            .await
            .unwrap_err();
        assert!(error.is_timeout());
        let cause = error.source().unwrap().to_string();
        let error = AppError::Http(error);
        assert!(error.to_string().contains("timeout:"));
        assert!(error.to_string().contains(&cause));
        assert_eq!(error.public_message(), "internal server error");
        assert_eq!(error.code(), "upstream_error");
    }
}

impl From<std::io::Error> for AppError {
    fn from(value: std::io::Error) -> Self {
        Self::Internal(value.to_string())
    }
}

impl From<toml::de::Error> for AppError {
    fn from(value: toml::de::Error) -> Self {
        Self::Config(value.to_string())
    }
}
