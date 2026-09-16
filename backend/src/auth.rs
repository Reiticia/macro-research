use std::{env, sync::Arc};

use axum::{
    Json,
    extract::{Request, State},
    http::StatusCode,
    middleware::Next,
    response::{IntoResponse, Response},
};
use serde_json::json;

use crate::{config::AuthConfig, error::AppError, quota::QuotaService};

/// A validated caller. Handlers use it for per-token quotas and audit fields.
#[derive(Clone, Debug)]
pub struct TokenId(pub String);

#[derive(Clone)]
pub struct AuthState {
    pub store: Arc<TokenStore>,
    pub quota: Arc<QuotaService>,
}

#[derive(Debug, Default)]
pub struct TokenStore {
    enabled: bool,
    entries: Vec<(String, String)>,
}

impl TokenStore {
    /// Reads `name:token,name:token` from the configured environment variable.
    pub fn from_config(config: &AuthConfig) -> Result<Self, AppError> {
        if !config.enabled {
            return Ok(Self {
                enabled: false,
                entries: Vec::new(),
            });
        }
        let raw = env::var(&config.tokens_env).unwrap_or_default();
        let entries: Vec<(String, String)> = raw
            .split(',')
            .filter_map(|entry| {
                let (name, token) = entry.trim().split_once(':')?;
                let name = name.trim();
                let token = token.trim();
                (!name.is_empty() && !token.is_empty()).then(|| (name.to_owned(), token.to_owned()))
            })
            .collect();
        if entries.is_empty() {
            return Err(AppError::Config(format!(
                "auth is enabled but {} does not contain any name:token entries",
                config.tokens_env
            )));
        }
        Ok(Self {
            enabled: true,
            entries,
        })
    }

    pub fn enabled(&self) -> bool {
        self.enabled
    }

    /// Returns the token's name when the bearer value matches, comparing in constant time.
    pub fn authenticate(&self, authorization: Option<&str>) -> Option<String> {
        if !self.enabled {
            return Some("anonymous".to_owned());
        }
        let header = authorization?.trim();
        let token = header
            .strip_prefix("Bearer ")
            .or_else(|| header.strip_prefix("bearer "))?
            .trim();
        self.entries
            .iter()
            .find(|(_, candidate)| constant_time_eq(candidate.as_bytes(), token.as_bytes()))
            .map(|(name, _)| name.clone())
    }
}

/// Length-independent comparison so a caller cannot learn a token prefix from timing.
fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    if left.len() != right.len() {
        return false;
    }
    let mut difference = 0u8;
    for (a, b) in left.iter().zip(right.iter()) {
        difference |= a ^ b;
    }
    difference == 0
}

/// Middleware for every `/api/v1` route except `meta`.
pub async fn require_token(
    State(state): State<AuthState>,
    mut request: Request,
    next: Next,
) -> Response {
    let authorization = request
        .headers()
        .get(axum::http::header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok());
    match state.store.authenticate(authorization) {
        Some(token_id) => {
            request.extensions_mut().insert(TokenId(token_id));
            next.run(request).await
        }
        None => (
            StatusCode::UNAUTHORIZED,
            Json(json!({
                "error": { "code": "unauthorized", "message": "missing or invalid access token" }
            })),
        )
            .into_response(),
    }
}
