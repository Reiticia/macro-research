//! Read-only audit queries and authenticated, idempotent manual review buttons.
use super::{
    bot::BotState,
    earnings_commands::admin_chat,
    telegram::{InlineButton, TelegramClient, escape},
};
use crate::{error::AppError, translation_review::ReviewAudit};
use serde_json::Value;
use sqlx::{Row, SqlitePool, sqlite::SqliteRow};
use std::sync::Arc;

fn clipped(value: &str) -> String {
    let text: String = value.chars().take(140).collect();
    escape(&format!(
        "{text}{}",
        if value.chars().count() > 140 {
            "…（已截断，请勿据此批准）"
        } else {
            ""
        }
    ))
}
fn render(row: &SqliteRow) -> Result<String, AppError> {
    let confidence: Option<f64> = row.try_get("confidence")?;
    let threshold: Option<f64> = row.try_get("threshold")?;
    Ok(format!(
        "<b>译名审校 #{}</b> · {} / {}\n原文：{}\n简体：{}\n繁体：{}\n审校置信度：{}；阈值：{}\n审校器：{}\n记录时间：{} UTC\n人工处理人：{}",
        row.try_get::<i64, _>("id")?,
        row.try_get::<String, _>("outcome")?,
        row.try_get::<String, _>("status")?,
        clipped(&row.try_get::<String, _>("source")?),
        clipped(&row.try_get::<String, _>("zh_cn")?),
        clipped(&row.try_get::<String, _>("zh_tw")?),
        confidence
            .map(|p| format!("{:.2}%", p * 100.0))
            .unwrap_or("不可用（未返回有效评分）".into()),
        threshold
            .map(|p| format!("{:.2}%", p * 100.0))
            .unwrap_or("不可用".into()),
        escape(&row.try_get::<String, _>("reviewer")?),
        chrono::DateTime::from_timestamp(row.try_get("created_at")?, 0)
            .map(|t| t.to_rfc3339())
            .unwrap_or_default(),
        row.try_get::<Option<i64>, _>("decided_by")?
            .map(|id| id.to_string())
            .unwrap_or("—".into())
    ))
}
fn buttons(row: &SqliteRow) -> Result<Option<Vec<Vec<InlineButton>>>, AppError> {
    if row.try_get::<String, _>("status")? != "pending" {
        return Ok(None);
    }
    let id: i64 = row.try_get("id")?;
    let mut options = vec![InlineButton {
        text: "拒绝，保留原文".into(),
        callback_data: format!("trreview:no:{id}"),
    }];
    // No approval of text that the UI could not display in full.
    if ["source", "zh_cn", "zh_tw"]
        .iter()
        .all(|key| row.get::<String, _>(*key).chars().count() <= 140)
    {
        options.insert(
            0,
            InlineButton {
                text: "确认采用此译文".into(),
                callback_data: format!("trreview:yes:{id}"),
            },
        );
    }
    Ok(Some(vec![options]))
}

pub(super) async fn message(state: &BotState, message: &Value) -> Result<bool, AppError> {
    let text = message["text"].as_str().unwrap_or("").trim();
    let parts: Vec<_> = text.split_whitespace().collect();
    if parts.first().unwrap_or(&"").split('@').next() != Some("/translation_audit") {
        return Ok(false);
    }
    let Some(chat) = admin_chat(state, message, &message["from"]) else {
        return Ok(true);
    };
    let filter = parts.get(1).copied().unwrap_or("pending");
    let page = parts
        .get(2)
        .unwrap_or(&"1")
        .parse::<i64>()
        .ok()
        .filter(|v| (1..=10000).contains(v));
    if parts.len() > 3
        || !matches!(
            filter,
            "pending" | "approved" | "accepted" | "rejected" | "unavailable" | "all"
        )
        || page.is_none()
    {
        state.telegram.send_message(chat,"用法：/translation_audit [pending|approved|accepted|rejected|unavailable|all] [页码]",None).await?;
        return Ok(true);
    }
    let page = page.unwrap();
    let predicate = "(?='all' OR (?='pending' AND status='pending') OR (?='approved' AND outcome='approved') OR (?='accepted' AND status='accepted') OR (?='rejected' AND (outcome='rejected' OR status='rejected')) OR (?='unavailable' AND outcome='unavailable'))";
    let query = format!(
        "SELECT * FROM translation_review_audit WHERE {predicate} ORDER BY id DESC LIMIT 3 OFFSET ?"
    );
    let mut q = sqlx::query(&query);
    for _ in 0..6 {
        q = q.bind(filter);
    }
    let rows = q.bind((page - 1) * 3).fetch_all(&state.pool).await?;
    state.telegram.send_message(chat,&format!("译名审校记录 · {filter} · 第 {page} 页（每页3条）\napproved=模型审校通过，不代表一定已覆盖缓存；accepted=人工采纳。\n仅查库，不调用模型。"),None).await?;
    if rows.is_empty() {
        state
            .telegram
            .send_message(chat, "本页无记录。", None)
            .await?;
    }
    for row in rows {
        state
            .telegram
            .send_message(chat, &render(&row)?, buttons(&row)?)
            .await?;
    }
    Ok(true)
}

pub(super) async fn callback(state: &BotState, callback: &Value) -> Result<bool, AppError> {
    let data = callback["data"].as_str().unwrap_or("");
    let Some(payload) = data.strip_prefix("trreview:") else {
        return Ok(false);
    };
    let Some(chat) = admin_chat(state, &callback["message"], &callback["from"]) else {
        return Ok(true);
    };
    let parsed = payload.split_once(':').and_then(|(action, id)| {
        if !matches!(action, "yes" | "no") {
            return None;
        }
        Some((
            action == "yes",
            id.parse::<i64>().ok().filter(|id| *id > 0)?,
        ))
    });
    let callback_id = callback["id"].as_str().unwrap_or("");
    let Some((accept, id)) = parsed else {
        state
            .telegram
            .answer_callback(callback_id, "无效的复核操作")
            .await?;
        return Ok(true);
    };
    state
        .telegram
        .answer_callback(callback_id, "正在处理复核")
        .await?;
    // Never trust callback data alone to approve a truncated snapshot.
    let row = sqlx::query("SELECT * FROM translation_review_audit WHERE id=?")
        .bind(id)
        .fetch_optional(&state.pool)
        .await?;
    if accept
        && row.as_ref().is_some_and(|r| {
            ["source", "zh_cn", "zh_tw"]
                .iter()
                .any(|k| r.get::<String, _>(*k).chars().count() > 140)
        })
    {
        state
            .telegram
            .send_message(chat, "文本超长，不能从截断消息采纳。", None)
            .await?;
        return Ok(true);
    }
    let result = ReviewAudit::new(state.pool.clone())
        .decide(id, chat, accept)
        .await?;
    if let Some(message) = callback["message"]["message_id"].as_i64() {
        state
            .telegram
            .edit_message(chat, message, &format!("译名复核 #{id}\n{result}"), None)
            .await?;
    }
    Ok(true)
}

/// Bounded persistent delivery, per admin. No network call holds the SQLite writer lock.
pub async fn deliver_pending(
    pool: &SqlitePool,
    telegram: &TelegramClient,
    admins: &[i64],
) -> Result<(), AppError> {
    // A cached translation wins over stale pending drafts, including late model results.
    sqlx::query("UPDATE translation_review_audit SET status='superseded' WHERE status='pending' AND EXISTS(SELECT 1 FROM event_name_translation t WHERE t.source_text=translation_review_audit.source)").execute(pool).await?;
    for &chat in admins.iter().filter(|id| **id > 0) {
        let rows=sqlx::query("SELECT a.* FROM translation_review_audit a LEFT JOIN translation_review_delivery d ON d.review_id=a.id AND d.chat_id=? WHERE a.status='pending' AND d.delivered_at IS NULL AND COALESCE(d.attempts,0)<3 AND (d.attempted_at IS NULL OR d.attempted_at<unixepoch()-3600) ORDER BY a.id LIMIT 5")
            .bind(chat).fetch_all(pool).await?;
        for row in rows {
            let id: i64 = row.try_get("id")?;
            let claim=sqlx::query("INSERT INTO translation_review_delivery(review_id,chat_id,attempts,attempted_at) VALUES(?,?,1,unixepoch()) ON CONFLICT(review_id,chat_id) DO UPDATE SET attempts=attempts+1,attempted_at=unixepoch() WHERE delivered_at IS NULL AND attempts<3 AND attempted_at<unixepoch()-3600")
                .bind(id).bind(chat).execute(pool).await?.rows_affected();
            if claim == 0 {
                continue;
            }
            match telegram.send_message(chat,&format!("⚠️ 译名需要人工二次确认\n{}\n未采纳前保留原文。/translation_audit 可查询审校记录。",render(&row)?),buttons(&row)?).await {
                Ok(_) => { sqlx::query("UPDATE translation_review_delivery SET delivered_at=unixepoch() WHERE review_id=? AND chat_id=?").bind(id).bind(chat).execute(pool).await?; }
                Err(_) => tracing::warn!(review_id=id, "translation review Telegram delivery failed; bounded retry pending"),
            }
        }
    }
    Ok(())
}

pub async fn delivery_loop(pool: SqlitePool, telegram: Arc<TelegramClient>, admins: Vec<i64>) {
    let mut timer = tokio::time::interval(std::time::Duration::from_secs(60));
    timer.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        timer.tick().await;
        if let Err(error) = deliver_pending(&pool, &telegram, &admins).await {
            tracing::warn!(%error,"translation review delivery iteration failed");
        }
    }
}
