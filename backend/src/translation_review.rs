//! Immutable, deduplicated review evidence; manual approval never overwrites a newer translation.
use crate::{
    error::AppError,
    translation::{EventNameTranslation, TranslationVerdict},
};
use sqlx::{Row, SqlitePool};

#[derive(Clone)]
pub struct ReviewAudit {
    pub pool: SqlitePool,
}

impl ReviewAudit {
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    pub async fn record(
        &self,
        row: &EventNameTranslation,
        verdict: Option<&TranslationVerdict>,
        reviewer: &str,
        manual: bool,
    ) -> Result<i64, AppError> {
        let confidence = verdict
            .and_then(|v| v.confidence)
            .filter(|v| v.is_finite() && (0.0..=1.0).contains(v));
        let threshold = verdict
            .and_then(|v| v.threshold)
            .filter(|v| v.is_finite() && (0.0..=1.0).contains(v));
        if row.source.trim().is_empty()
            || row.zh_cn.trim().is_empty()
            || row.zh_tw.trim().is_empty()
            || [&row.source, &row.zh_cn, &row.zh_tw]
                .iter()
                .any(|s| s.chars().count() > 4096)
        {
            return Err(AppError::Provider(
                "invalid translation review text length".into(),
            ));
        }
        let outcome = match verdict {
            Some(v) if v.approved => "approved",
            Some(_) => "rejected",
            None => "unavailable",
        };
        let status = if manual { "pending" } else { "recorded" };
        let encoded = serde_json::to_vec(&(
            &row.source,
            &row.zh_cn,
            &row.zh_tw,
            reviewer,
            confidence,
            threshold,
            outcome,
            status,
        ))
        .map_err(|_| AppError::Internal("review encoding failed".into()))?;
        let fingerprint = ring::digest::digest(&ring::digest::SHA256, &encoded)
            .as_ref()
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect::<String>();
        let id: i64 = sqlx::query_scalar("INSERT INTO translation_review_audit(fingerprint,source,zh_cn,zh_tw,reviewer,confidence,threshold,outcome,status) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(fingerprint) DO UPDATE SET fingerprint=excluded.fingerprint RETURNING id")
            .bind(fingerprint).bind(&row.source).bind(&row.zh_cn).bind(&row.zh_tw).bind(reviewer).bind(confidence).bind(threshold).bind(outcome).bind(status).fetch_one(&self.pool).await?;
        // Structured debug strings escape control characters; only event names/translations, never requests or keys.
        if outcome == "approved" {
            tracing::info!(review_id=id, source=?row.source, zh_cn=?row.zh_cn, zh_tw=?row.zh_tw, reviewer, ?confidence, ?threshold, outcome, "translation review audit");
        } else {
            tracing::warn!(review_id=id, source=?row.source, zh_cn=?row.zh_cn, zh_tw=?row.zh_tw, reviewer, ?confidence, ?threshold, outcome, "translation review audit");
        }
        Ok(id)
    }

    /// Pending/rejected manual decisions stop hourly retries from regenerating the same name.
    pub async fn blocked(&self, source: &str) -> Result<bool, AppError> {
        Ok(sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM translation_review_audit WHERE source=? AND status IN ('pending','rejected')")
            .bind(source).fetch_one(&self.pool).await? > 0)
    }

    pub async fn decide(
        &self,
        id: i64,
        admin: i64,
        accept: bool,
    ) -> Result<&'static str, AppError> {
        let mut tx = self.pool.begin().await?;
        // Obtain the write lock before reading a pending candidate. All decisions are idempotent.
        let row = sqlx::query("UPDATE translation_review_audit SET decided_by=?,decided_at=unixepoch(),status=? WHERE id=? AND status='pending' RETURNING source,zh_cn,zh_tw")
            .bind(admin).bind(if accept { "accepted" } else { "rejected" }).bind(id).fetch_optional(&mut *tx).await?;
        let Some(row) = row else {
            tx.commit().await?;
            return Ok("已处理或不存在，请重新查询。");
        };
        let source: String = row.try_get("source")?;
        let cn: String = row.try_get("zh_cn")?;
        let tw: String = row.try_get("zh_tw")?;
        let mut message = "已拒绝；保持原文，不自动重复提交。";
        if accept {
            let inserted = sqlx::query("INSERT INTO event_name_translation(source_text,zh_cn,zh_tw,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP) ON CONFLICT(source_text) DO NOTHING")
                .bind(&source).bind(&cn).bind(&tw).execute(&mut *tx).await?.rows_affected();
            if inserted == 0 {
                sqlx::query("UPDATE translation_review_audit SET status='superseded' WHERE id=?")
                    .bind(id)
                    .execute(&mut *tx)
                    .await?;
                message = "已有缓存译名，未覆盖；此复核已失效。";
            } else {
                sqlx::query("UPDATE economic_event SET event_zh_cn=COALESCE(event_zh_cn,?),event_zh_tw=COALESCE(event_zh_tw,?) WHERE event=?")
                    .bind(&cn).bind(&tw).bind(&source).execute(&mut *tx).await?;
                message = "已人工采纳，仅补充译名，不修改公布值。";
            }
        }
        sqlx::query("UPDATE translation_review_audit SET status='superseded' WHERE source=? AND status='pending' AND id!=?").bind(&source).bind(id).execute(&mut *tx).await?;
        tx.commit().await?;
        tracing::info!(
            review_id = id,
            admin,
            accept,
            "translation human review decision"
        );
        Ok(message)
    }
}
