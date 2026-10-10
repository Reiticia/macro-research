use std::sync::Arc;

use axum::{
    extract::{Request, State},
    middleware::Next,
    response::{IntoResponse, Response},
};
use sqlx::{Row, SqlitePool};

use crate::{access_keys, error::AppError, quota::QuotaService};

/// A stable database key id, never a credential or a device identifier.
#[derive(Clone, Debug)]
pub struct TokenId(pub String);

#[derive(Clone)]
pub struct AuthState {
    pub store: Arc<TokenStore>,
    pub quota: Arc<QuotaService>,
}

#[derive(Clone)]
pub struct TokenStore {
    enabled: bool,
    pool: SqlitePool,
}
impl TokenStore {
    pub fn new(enabled: bool, pool: SqlitePool) -> Self {
        Self { enabled, pool }
    }
    pub fn enabled(&self) -> bool {
        self.enabled
    }

    /// Empty databases remain closed. Conditional binding admits exactly one first device.
    pub async fn authenticate(
        &self,
        authorization: Option<&str>,
        installation: Option<&str>,
        android_hash: Option<&str>,
    ) -> Result<Option<String>, AppError> {
        if !self.enabled {
            return Ok(Some("anonymous".into()));
        }
        let Some(token) = authorization
            .and_then(|header| {
                header
                    .strip_prefix("Bearer ")
                    .or_else(|| header.strip_prefix("bearer "))
            })
            .map(str::trim)
            .filter(|token| !token.is_empty() && token.len() <= 128)
        else {
            return Ok(None);
        };
        let hash = access_keys::hash(token);
        let device = access_keys::device_hash(installation, android_hash);
        // Invalid bearer traffic should not contend for SQLite's writer lock.
        let kind = sqlx::query_scalar::<_, String>(
            "SELECT kind FROM api_key WHERE token_hash = ? AND status = 'active'",
        )
        .bind(&hash)
        .fetch_optional(&self.pool)
        .await?;
        match kind.as_deref() {
            Some("general") => {}
            Some("device") if device.is_some() => {}
            _ => return Ok(None),
        }
        let row = sqlx::query(
            "UPDATE api_key SET device_hash = CASE WHEN kind = 'device' THEN COALESCE(device_hash, ?) ELSE NULL END, \
             first_authorized_at = COALESCE(first_authorized_at, unixepoch()), last_used_at = unixepoch() \
             WHERE token_hash = ? AND status = 'active' AND \
             (kind = 'general' OR (? IS NOT NULL AND (device_hash IS NULL OR device_hash = ?))) RETURNING id",
        ).bind(&device).bind(hash).bind(&device).bind(&device).fetch_optional(&self.pool).await?;
        Ok(row.map(|row| format!("api_key:{}", row.get::<i64, _>("id"))))
    }

    /// Used to disconnect already-open WebSockets after revocation.
    pub async fn is_active(&self, caller: &str) -> Result<bool, AppError> {
        if !self.enabled {
            return Ok(true);
        }
        let Some(id) = caller
            .strip_prefix("api_key:")
            .and_then(|id| id.parse::<i64>().ok())
        else {
            return Ok(false);
        };
        Ok(sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM api_key WHERE id = ? AND status = 'active'",
        )
        .bind(id)
        .fetch_one(&self.pool)
        .await?
            == 1)
    }
}

pub async fn require_token(
    State(state): State<AuthState>,
    mut request: Request,
    next: Next,
) -> Response {
    let (authorization, installation, android_hash) = {
        let header = |name| {
            request
                .headers()
                .get(name)
                .and_then(|value| value.to_str().ok())
                .map(str::to_owned)
        };
        (
            header("authorization"),
            header("x-installation-id"),
            header("x-android-id-hash"),
        )
    };
    let caller = state
        .store
        .authenticate(
            authorization.as_deref(),
            installation.as_deref(),
            android_hash.as_deref(),
        )
        .await;
    match caller {
        Ok(Some(id)) => {
            request.extensions_mut().insert(TokenId(id));
            next.run(request).await
        }
        Ok(None) => AppError::Unauthorized.into_response(),
        Err(error) => error.into_response(),
    }
}
