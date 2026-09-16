use std::sync::Arc;

use chrono::{DateTime, Duration, Utc};
use serde::Serialize;
use sqlx::{Row, SqlitePool};

use crate::{
    alert::{service::AlertService, telegram::InlineButton},
    error::AppError,
    repository::EventRepository,
};

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CorrectionStatus {
    pub id: i64,
    pub event_name: String,
    pub status: String,
    pub zh_cn: String,
    pub zh_tw: String,
    pub created_at: String,
    pub decided_at: Option<String>,
}

/// Admin-moderated event-name corrections.
///
/// The server holds the shared translation cache, so a reader cannot simply overwrite it:
/// suggestions are queued, the admin decides over Telegram, and only an approval is written
/// back to every device.
pub struct TranslationCorrectionService {
    pool: SqlitePool,
    events: EventRepository,
    alerts: Option<Arc<AlertService>>,
}

impl TranslationCorrectionService {
    pub fn new(
        pool: SqlitePool,
        events: EventRepository,
        alerts: Option<Arc<AlertService>>,
    ) -> Self {
        Self {
            pool,
            events,
            alerts,
        }
    }

    pub async fn submit(
        &self,
        event_name: &str,
        zh_cn: &str,
        zh_tw: &str,
        requested_by: Option<String>,
    ) -> Result<CorrectionStatus, AppError> {
        let event_name = event_name.trim();
        let zh_cn = zh_cn.trim();
        let zh_tw = zh_tw.trim();
        if event_name.is_empty() || zh_cn.is_empty() || zh_tw.is_empty() {
            return Err(AppError::InvalidRequest(
                "eventName, zhCn and zhTw are required".into(),
            ));
        }
        if self.is_muted(event_name).await? {
            return Ok(CorrectionStatus {
                id: 0,
                event_name: event_name.to_owned(),
                status: "muted".to_owned(),
                zh_cn: zh_cn.to_owned(),
                zh_tw: zh_tw.to_owned(),
                created_at: Utc::now().to_rfc3339(),
                decided_at: None,
            });
        }
        if let Some(existing) = self.recent_duplicate(event_name, zh_cn, zh_tw).await? {
            return Ok(existing);
        }

        let now = Utc::now().to_rfc3339();
        let id = sqlx::query(
            r#"INSERT INTO translation_correction
                 (event_name, proposed_zh_cn, proposed_zh_tw, requested_by, status, created_at)
               VALUES (?, ?, ?, ?, 'pending', ?)"#,
        )
        .bind(event_name)
        .bind(zh_cn)
        .bind(zh_tw)
        .bind(requested_by.as_deref())
        .bind(&now)
        .execute(&self.pool)
        .await?
        .last_insert_rowid();

        let text = format!(
            "📝 <b>译名勘正待审</b>\n事件：<code>{}</code>\n建议简中：{}\n建议繁中：{}\n提交者：{}",
            crate::alert::telegram::escape(event_name),
            crate::alert::telegram::escape(zh_cn),
            crate::alert::telegram::escape(zh_tw),
            crate::alert::telegram::escape(requested_by.as_deref().unwrap_or("unknown")),
        );
        if let Some(alerts) = &self.alerts {
            let buttons = vec![vec![
                InlineButton {
                    text: "✅ 采纳".into(),
                    callback_data: format!("c:{id}"),
                },
                InlineButton {
                    text: "❌ 拒绝".into(),
                    callback_data: format!("r:{id}"),
                },
                InlineButton {
                    text: "🔇 屏蔽该词条".into(),
                    callback_data: format!("m:{id}"),
                },
            ]];
            for chat_id in alerts.chat_ids() {
                match alerts
                    .notify_with_buttons(*chat_id, &text, buttons.clone())
                    .await
                {
                    Ok(Some(message_id)) => {
                        let _ = sqlx::query(
                            "UPDATE translation_correction SET telegram_message_id = ? WHERE id = ?",
                        )
                        .bind(message_id)
                        .bind(id)
                        .execute(&self.pool)
                        .await;
                    }
                    Ok(None) => {}
                    Err(error) => {
                        tracing::warn!(%error, "translation correction notification failed")
                    }
                }
            }
        }

        self.by_id(id).await
    }

    pub async fn latest_for(&self, event_name: &str) -> Result<Option<CorrectionStatus>, AppError> {
        let row = sqlx::query(
            "SELECT * FROM translation_correction WHERE event_name = ? ORDER BY id DESC LIMIT 1",
        )
        .bind(event_name.trim())
        .fetch_optional(&self.pool)
        .await?;
        row.map(row_to_status).transpose()
    }

    pub async fn pending(&self) -> Result<Vec<CorrectionStatus>, AppError> {
        let rows = sqlx::query(
            "SELECT * FROM translation_correction WHERE status = 'pending' ORDER BY id ASC LIMIT 50",
        )
        .fetch_all(&self.pool)
        .await?;
        rows.into_iter().map(row_to_status).collect()
    }

    /// Applies the proposed names to the shared cache and to every event using that name.
    pub async fn approve(&self, id: i64) -> Result<CorrectionStatus, AppError> {
        let correction = self.by_id(id).await?;
        self.events
            .apply_translation(&correction.event_name, &correction.zh_cn, &correction.zh_tw)
            .await?;
        self.decide(id, "approved").await?;
        self.by_id(id).await
    }

    pub async fn reject(&self, id: i64) -> Result<CorrectionStatus, AppError> {
        self.decide(id, "rejected").await?;
        self.by_id(id).await
    }

    /// Stops future suggestions for this event name and closes the current one.
    pub async fn mute(&self, id: i64) -> Result<CorrectionStatus, AppError> {
        let correction = self.by_id(id).await?;
        sqlx::query(
            r#"INSERT INTO translation_correction_mute (event_name, created_at) VALUES (?, ?)
               ON CONFLICT(event_name) DO NOTHING"#,
        )
        .bind(&correction.event_name)
        .bind(Utc::now().to_rfc3339())
        .execute(&self.pool)
        .await?;
        self.decide(id, "muted").await?;
        self.by_id(id).await
    }

    async fn decide(&self, id: i64, status: &str) -> Result<(), AppError> {
        let result = sqlx::query(
            "UPDATE translation_correction SET status = ?, decided_at = ? WHERE id = ? AND status = 'pending'",
        )
        .bind(status)
        .bind(Utc::now().to_rfc3339())
        .bind(id)
        .execute(&self.pool)
        .await?;
        if result.rows_affected() == 0 {
            return Err(AppError::InvalidRequest(
                "correction is missing or already decided".into(),
            ));
        }
        Ok(())
    }

    pub async fn is_muted(&self, event_name: &str) -> Result<bool, AppError> {
        let exists: i64 = sqlx::query_scalar(
            "SELECT EXISTS(SELECT 1 FROM translation_correction_mute WHERE event_name = ?)",
        )
        .bind(event_name.trim())
        .fetch_one(&self.pool)
        .await?;
        Ok(exists != 0)
    }

    /// The same suggestion resubmitted within a day is the same request, not a new one.
    async fn recent_duplicate(
        &self,
        event_name: &str,
        zh_cn: &str,
        zh_tw: &str,
    ) -> Result<Option<CorrectionStatus>, AppError> {
        let cutoff = (Utc::now() - Duration::hours(24)).to_rfc3339();
        let row = sqlx::query(
            r#"SELECT * FROM translation_correction
               WHERE event_name = ? AND proposed_zh_cn = ? AND proposed_zh_tw = ?
                 AND created_at >= ?
               ORDER BY id DESC LIMIT 1"#,
        )
        .bind(event_name)
        .bind(zh_cn)
        .bind(zh_tw)
        .bind(cutoff)
        .fetch_optional(&self.pool)
        .await?;
        row.map(row_to_status).transpose()
    }

    async fn by_id(&self, id: i64) -> Result<CorrectionStatus, AppError> {
        let row = sqlx::query("SELECT * FROM translation_correction WHERE id = ?")
            .bind(id)
            .fetch_optional(&self.pool)
            .await?
            .ok_or(AppError::NotFound)?;
        row_to_status(row)
    }
}

fn row_to_status(row: sqlx::sqlite::SqliteRow) -> Result<CorrectionStatus, AppError> {
    let created_at: String = row.try_get("created_at")?;
    let decided_at: Option<String> = row.try_get("decided_at")?;
    Ok(CorrectionStatus {
        id: row.try_get("id")?,
        event_name: row.try_get("event_name")?,
        status: row.try_get("status")?,
        zh_cn: row.try_get("proposed_zh_cn")?,
        zh_tw: row.try_get("proposed_zh_tw")?,
        created_at: created_at
            .parse::<DateTime<Utc>>()
            .map(|value| value.to_rfc3339())
            .unwrap_or(created_at),
        decided_at,
    })
}
