use std::sync::Arc;

use serde_json::Value;
use sqlx::SqlitePool;

use crate::{
    alert::{state::HealthRegistry, telegram::TelegramClient},
    llm_usage::LlmUsageRepository,
};

/// Telegram long-polling loop: the admin console for the server.
///
/// It answers `/status`, `/usage` and `/help`, and it resolves the inline buttons attached to
/// shared-analysis feedback. Only whitelisted chat ids are answered.
pub struct BotState {
    pub telegram: Arc<TelegramClient>,
    pub chat_ids: Vec<i64>,
    pub health: Arc<HealthRegistry>,
    /// Model-call audit log, backing the `/usage` command.
    pub llm_usage: Option<Arc<LlmUsageRepository>>,
    pub pool: SqlitePool,
    pub poll_timeout_seconds: u64,
}

pub async fn bot_loop(state: BotState) {
    if let Err(error) = state.telegram.set_admin_commands(&state.chat_ids).await {
        tracing::warn!(%error, "Telegram command menu registration failed; polling will continue");
    }
    loop {
        let offset = read_offset(&state.pool).await.unwrap_or(0);
        let updates = match state
            .telegram
            .get_updates(offset, state.poll_timeout_seconds)
            .await
        {
            Ok(updates) => updates,
            Err(error) => {
                tracing::warn!(%error, "Telegram getUpdates failed; retrying");
                tokio::time::sleep(std::time::Duration::from_secs(5)).await;
                continue;
            }
        };
        for update in updates {
            let Some(update_id) = update.get("update_id").and_then(Value::as_i64) else {
                continue;
            };
            if let Err(error) = handle_update(&state, &update).await {
                tracing::warn!(update_id, %error, "Telegram update handling failed");
            }
            if let Err(error) = write_offset(&state.pool, update_id + 1).await {
                tracing::warn!(%error, "Telegram offset persistence failed");
            }
        }
    }
}

async fn handle_update(state: &BotState, update: &Value) -> Result<(), crate::error::AppError> {
    if let Some(callback) = update.get("callback_query") {
        return handle_callback(state, callback).await;
    }
    if let Some(message) = update.get("message") {
        return handle_message(state, message).await;
    }
    Ok(())
}

async fn handle_callback(state: &BotState, callback: &Value) -> Result<(), crate::error::AppError> {
    let chat_id = callback
        .get("message")
        .and_then(|message| message.get("chat"))
        .and_then(|chat| chat.get("id"))
        .and_then(Value::as_i64);
    if !chat_id.is_some_and(|id| state.chat_ids.contains(&id)) {
        return Ok(());
    }
    let callback_id = callback
        .get("id")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let data = callback
        .get("data")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let message_id = callback
        .get("message")
        .and_then(|message| message.get("message_id"))
        .and_then(Value::as_i64);
    let Some((action, raw_id)) = data.split_once(':') else {
        return Ok(());
    };
    let Ok(id) = raw_id.parse::<i64>() else {
        return Ok(());
    };
    let (outcome, confirmation) = match action {
        "ag" => (
            crate::shared_ai::decide(&state.pool, id, true).await,
            "已处理：有效反馈已加入重新分析队列",
        ),
        "ai" => (
            crate::shared_ai::decide(&state.pool, id, false).await,
            "已忽略反馈",
        ),
        _ => return Ok(()),
    };
    let text = match outcome {
        Ok(()) => confirmation.to_owned(),
        Err(error) => format!("操作失败：{error}"),
    };
    state.telegram.answer_callback(callback_id, &text).await?;
    if let (Some(chat_id), Some(message_id)) = (chat_id, message_id) {
        state
            .telegram
            .close_keyboard(chat_id, message_id, &text)
            .await?;
        state
            .telegram
            .send_message(chat_id, &format!("✅ {text}"), None)
            .await?;
    }
    Ok(())
}

async fn handle_message(state: &BotState, message: &Value) -> Result<(), crate::error::AppError> {
    let chat_id = message
        .get("chat")
        .and_then(|chat| chat.get("id"))
        .and_then(Value::as_i64);
    if !chat_id.is_some_and(|id| state.chat_ids.contains(&id)) {
        return Ok(());
    }
    let Some(chat_id) = chat_id else {
        return Ok(());
    };
    let text = message
        .get("text")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .trim();
    let command = text.split_whitespace().next().unwrap_or_default();
    // Commands may arrive as `/status@the_bot`; the suffix is not part of the command.
    let command = command.split('@').next().unwrap_or(command);
    let reply = match command {
        "/status" => status_text(state).await?,
        "/usage" => usage_text(state).await?,
        "/help" | "/start" => {
            "可用命令：\n/status — 查看数据源健康状态\n/usage — 查看近24小时模型用量\n/help — 查看管理员帮助\n/start — 打开管理员菜单".to_owned()
        }
        _ => return Ok(()),
    };
    state.telegram.send_message(chat_id, &reply, None).await?;
    Ok(())
}

async fn status_text(state: &BotState) -> Result<String, crate::error::AppError> {
    let snapshot = state.health.snapshot().await?;
    if snapshot.is_empty() {
        return Ok("暂无数据源状态记录。".to_owned());
    }
    let mut lines = vec!["<b>数据源状态</b>".to_owned()];
    for health in snapshot {
        let icon = match health.status.as_str() {
            "healthy" => "✅",
            "degraded" => "⚠️",
            _ => "🚨",
        };
        let error = health
            .last_error
            .as_deref()
            .map(|value| format!(" — {}", crate::alert::telegram::escape(value)))
            .unwrap_or_default();
        lines.push(format!(
            "{icon} <code>{}</code> {}{error}",
            crate::alert::telegram::escape(&health.key),
            health.status
        ));
    }
    Ok(lines.join("\n"))
}

/// Last-24-hour model spend, grouped by category, so the admin can see which purpose is burning
/// tokens without opening the API.
async fn usage_text(state: &BotState) -> Result<String, crate::error::AppError> {
    let Some(audit) = &state.llm_usage else {
        return Ok("用量审计未启用 (limits.llm_usage_retention_days = 0)。".to_owned());
    };
    let summary = audit.summary(1, 5).await?;
    if summary.calls == 0 {
        return Ok("近 24 小时没有模型调用。".to_owned());
    }
    let mut lines = vec![format!(
        "<b>近 24 小时</b>：{} 次调用，{} 失败，共 {} tokens",
        summary.calls, summary.failures, summary.total_tokens
    )];
    for bucket in &summary.by_category {
        lines.push(format!(
            "• <code>{}</code> / <code>{}</code> {} → {} 次，{} tokens",
            crate::alert::telegram::escape(&bucket.scope),
            crate::alert::telegram::escape(&bucket.kind),
            crate::alert::telegram::escape(&bucket.model),
            bucket.calls,
            bucket.total_tokens
        ));
    }
    if !summary.recent.is_empty() {
        lines.push("<b>最近</b>".to_owned());
        for row in summary.recent.iter().take(3) {
            let mark = if row.status == "ok" { "✅" } else { "❌" };
            lines.push(format!(
                "{} {} <code>{}</code> {} tok",
                mark,
                row.created_at
                    .parse::<chrono::DateTime<chrono::Utc>>()
                    .map(|time| time.format("%m-%d %H:%M").to_string())
                    .unwrap_or_default(),
                crate::alert::telegram::escape(&row.kind),
                row.total_tokens
            ));
        }
    }
    Ok(lines.join("\n"))
}

async fn read_offset(pool: &SqlitePool) -> Result<i64, crate::error::AppError> {
    Ok(
        sqlx::query_scalar::<_, i64>("SELECT update_offset FROM telegram_offset WHERE id = 1")
            .fetch_optional(pool)
            .await?
            .unwrap_or(0),
    )
}

async fn write_offset(pool: &SqlitePool, offset: i64) -> Result<(), crate::error::AppError> {
    sqlx::query("UPDATE telegram_offset SET update_offset = ? WHERE id = 1")
        .bind(offset)
        .execute(pool)
        .await?;
    Ok(())
}
