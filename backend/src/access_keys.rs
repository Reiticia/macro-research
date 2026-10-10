//! Telegram-issued access credentials. Only digests and masked references are persisted.
use ring::{
    digest,
    rand::{SecureRandom, SystemRandom},
};
use sqlx::{FromRow, SqlitePool};

use crate::error::AppError;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyKind {
    General,
    Device,
}
impl KeyKind {
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "general" => Some(Self::General),
            "device" => Some(Self::Device),
            _ => None,
        }
    }
    pub fn as_str(self) -> &'static str {
        match self {
            Self::General => "general",
            Self::Device => "device",
        }
    }
    pub fn label(self) -> &'static str {
        match self {
            Self::General => "通用",
            Self::Device => "设备绑定",
        }
    }
}

#[derive(FromRow)]
pub struct KeyRequest {
    pub id: i64,
    pub requester_id: i64,
    pub requested_kind: String,
    pub state: String,
}

#[derive(FromRow)]
pub struct KeyRecord {
    pub id: i64,
    pub owner_id: i64,
    pub issued_by: i64,
    pub kind: String,
    pub token_prefix: String,
    pub status: String,
    pub device_hash: Option<String>,
    pub created_at: i64,
    pub first_authorized_at: Option<i64>,
    pub last_used_at: Option<i64>,
}

// Deliberately no Debug/Serialize: the secret exists only until private delivery completes.
pub struct IssuedKey {
    pub id: i64,
    pub request_id: i64,
    pub owner_id: i64,
    pub kind: KeyKind,
    pub secret: String,
}

#[derive(Clone)]
pub struct AccessKeys {
    pool: SqlitePool,
}
impl AccessKeys {
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    /// Repeated Telegram message/update delivery and repeated applications are idempotent.
    /// One unresolved request per user, at most three new requests per UTC day.
    pub async fn request(
        &self,
        owner: i64,
        message: i64,
        kind: KeyKind,
    ) -> Result<(KeyRequest, bool), AppError> {
        if owner <= 0 || message <= 0 {
            return Err(AppError::InvalidRequest(
                "private Telegram identity required".into(),
            ));
        }
        let created: Option<i64> = sqlx::query_scalar(
            "INSERT INTO api_key_request (requester_id, message_id, requested_kind) \
             SELECT ?, ?, ? WHERE (SELECT COUNT(*) FROM api_key_request WHERE requester_id = ? \
             AND created_at >= unixepoch('now', 'start of day')) < 3 ON CONFLICT DO NOTHING RETURNING id",
        ).bind(owner).bind(message).bind(kind.as_str()).bind(owner).fetch_optional(&self.pool).await?;
        let request = sqlx::query_as::<_, KeyRequest>(
            "SELECT id, requester_id, requested_kind, state FROM api_key_request WHERE requester_id = ? \
             AND (message_id = ? OR state IN ('pending', 'delivering')) \
             ORDER BY (message_id = ?) DESC, id DESC LIMIT 1",
        ).bind(owner).bind(message).bind(message).fetch_optional(&self.pool).await?
            .ok_or(AppError::QuotaExceeded { retry_after_seconds: 86400 })?;
        Ok((request, created.is_some()))
    }

    /// A conditional write claims an approval atomically across competing administrators.
    /// The new key cannot authenticate until Telegram has accepted delivery.
    pub async fn approve(
        &self,
        request_id: i64,
        admin: i64,
        kind: KeyKind,
    ) -> Result<Option<IssuedKey>, AppError> {
        let mut random = [0u8; 32];
        SystemRandom::new()
            .fill(&mut random)
            .map_err(|_| AppError::Internal("secure key generation failed".into()))?;
        let secret = format!("mrk_{}", hex(&random));
        let mut tx = self.pool.begin().await?;
        let owner: Option<i64> = sqlx::query_scalar(
            "UPDATE api_key_request SET state = 'delivering', decided_by = ? \
             WHERE id = ? AND state = 'pending' RETURNING requester_id",
        )
        .bind(admin)
        .bind(request_id)
        .fetch_optional(&mut *tx)
        .await?;
        let Some(owner_id) = owner else {
            return Ok(None);
        };
        let id = sqlx::query_scalar(
            "INSERT INTO api_key (request_id, owner_id, kind, token_hash, token_prefix, issued_by) \
             VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
        )
        .bind(request_id)
        .bind(owner_id)
        .bind(kind.as_str())
        .bind(hash(&secret))
        .bind(&secret[..12])
        .bind(admin)
        .fetch_one(&mut *tx)
        .await?;
        tx.commit().await?;
        Ok(Some(IssuedKey {
            id,
            request_id,
            owner_id,
            kind,
            secret,
        }))
    }

    pub async fn finish_delivery(&self, key: &IssuedKey, delivered: bool) -> Result<(), AppError> {
        let mut tx = self.pool.begin().await?;
        // Never resurrect a key an administrator revoked while delivery was in flight.
        let activated = sqlx::query(
            "UPDATE api_key SET status = CASE WHEN ? THEN 'active' ELSE 'revoked' END, \
             revoked_at = CASE WHEN ? THEN NULL ELSE unixepoch() END \
             WHERE id = ? AND status = 'delivery_pending'",
        )
        .bind(delivered)
        .bind(delivered)
        .bind(key.id)
        .execute(&mut *tx)
        .await?
        .rows_affected();
        if activated == 1 {
            sqlx::query(
                "UPDATE api_key_request SET state = ? WHERE id = ? AND state = 'delivering'",
            )
            .bind(if delivered { "approved" } else { "pending" })
            .bind(key.request_id)
            .execute(&mut *tx)
            .await?;
        }
        tx.commit().await?;
        Ok(())
    }

    /// Crash recovery: an undelivered credential is never enabled or redisplayed.
    /// The operator can approve its request again with a fresh credential.
    pub async fn recover_deliveries(&self) -> Result<(), AppError> {
        let mut tx = self.pool.begin().await?;
        sqlx::query("UPDATE api_key SET status = 'revoked', revoked_at = unixepoch() WHERE status = 'delivery_pending'")
            .execute(&mut *tx).await?;
        sqlx::query("UPDATE api_key_request SET state = 'pending' WHERE state = 'delivering'")
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn reject(&self, id: i64, admin: i64) -> Result<bool, AppError> {
        Ok(sqlx::query("UPDATE api_key_request SET state = 'rejected', decided_by = ? WHERE id = ? AND state = 'pending'")
            .bind(admin).bind(id).execute(&self.pool).await?.rows_affected() == 1)
    }

    pub async fn revoke(&self, id: i64, admin: i64) -> Result<bool, AppError> {
        let mut tx = self.pool.begin().await?;
        let request: Option<i64> = sqlx::query_scalar(
            "UPDATE api_key SET status = 'revoked', revoked_at = unixepoch(), revoked_by = ? \
             WHERE id = ? AND status != 'revoked' RETURNING request_id",
        )
        .bind(admin)
        .bind(id)
        .fetch_optional(&mut *tx)
        .await?;
        if let Some(request) = request {
            sqlx::query("UPDATE api_key_request SET state = 'rejected' WHERE id = ? AND state = 'delivering'")
                .bind(request).execute(&mut *tx).await?;
        }
        tx.commit().await?;
        Ok(request.is_some())
    }

    pub async fn list(&self, owner: Option<i64>, offset: i64) -> Result<Vec<KeyRecord>, AppError> {
        Ok(sqlx::query_as(
            "SELECT id, owner_id, issued_by, kind, token_prefix, status, device_hash, created_at, first_authorized_at, last_used_at \
             FROM api_key WHERE (? IS NULL OR owner_id = ?) ORDER BY id DESC LIMIT 10 OFFSET ?",
        ).bind(owner).bind(owner).bind(offset).fetch_all(&self.pool).await?)
    }
    pub async fn pending(&self, offset: i64) -> Result<Vec<KeyRequest>, AppError> {
        Ok(sqlx::query_as("SELECT id, requester_id, requested_kind, state FROM api_key_request WHERE state = 'pending' ORDER BY id LIMIT 10 OFFSET ?")
            .bind(offset).fetch_all(&self.pool).await?)
    }
}

pub fn hash(text: &str) -> String {
    hex(digest::digest(&digest::SHA256, text.as_bytes()).as_ref())
}
fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// Android sends a per-install UUID and an app-scoped SHA-256 of ANDROID_ID.
/// Neither is a hardware attestation. We store only the combined digest.
pub fn device_hash(installation: Option<&str>, android_hash: Option<&str>) -> Option<String> {
    let uuid = installation?.to_ascii_lowercase();
    let android = android_hash?.to_ascii_lowercase();
    if uuid.len() != 36 || android.len() != 64 {
        return None;
    }
    if !uuid.bytes().enumerate().all(|(i, b)| {
        if [8, 13, 18, 23].contains(&i) {
            b == b'-'
        } else {
            b.is_ascii_hexdigit()
        }
    }) || !android.bytes().all(|b| b.is_ascii_hexdigit())
    {
        return None;
    }
    Some(hash(&format!("{uuid}:{android}")))
}
