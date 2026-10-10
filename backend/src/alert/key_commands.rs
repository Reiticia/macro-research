//! Access-key operations are private-chat only; approval/list/revocation require the admin sender.
#[cfg(test)]
#[path = "key_commands_tests.rs"]
mod tests;
use super::{
    bot::BotState,
    telegram::{InlineButton, escape},
};
use crate::{
    access_keys::{AccessKeys, KeyKind, KeyRequest},
    error::AppError,
};
use serde_json::Value;

fn private_sender(message: &Value) -> Option<i64> {
    if message["chat"]["type"].as_str() != Some("private")
        || message["from"]["is_bot"].as_bool() == Some(true)
    {
        return None;
    }
    let sender = message["from"]["id"].as_i64()?;
    (sender > 0 && message["chat"]["id"].as_i64() == Some(sender)).then_some(sender)
}
fn page_offset(argument: Option<&str>) -> Option<i64> {
    let page = argument.unwrap_or("1").parse::<i64>().ok()?;
    (1..=100_000).contains(&page).then(|| (page - 1) * 10)
}
fn buttons(request: i64) -> Vec<Vec<InlineButton>> {
    vec![
        vec![
            InlineButton {
                text: "批准通用密钥".into(),
                callback_data: format!("key_approve:general:{request}"),
            },
            InlineButton {
                text: "批准设备密钥".into(),
                callback_data: format!("key_approve:device:{request}"),
            },
        ],
        vec![InlineButton {
            text: "拒绝申请".into(),
            callback_data: format!("key_reject:{request}"),
        }],
    ]
}
fn utc_time(timestamp: Option<i64>) -> String {
    timestamp
        .and_then(|value| chrono::DateTime::from_timestamp(value, 0))
        .map(|time| time.format("%Y-%m-%d %H:%M UTC").to_string())
        .unwrap_or_else(|| "--".into())
}
fn request_text(request: &KeyRequest) -> String {
    format!(
        "<b>API Key 申请 #{}</b>\n申请者：<code>{}</code>\n申请类型：{}\n状态：{}",
        request.id,
        request.requester_id,
        KeyKind::parse(&request.requested_kind)
            .map(KeyKind::label)
            .unwrap_or("未知"),
        escape(&request.state)
    )
}

pub async fn message(state: &BotState, message: &Value) -> Result<bool, AppError> {
    let text = message["text"].as_str().unwrap_or("").trim();
    let mut words = text.split_whitespace();
    let command = words.next().unwrap_or("").split('@').next().unwrap_or("");
    if !matches!(
        command,
        "/request_key"
            | "/my_keys"
            | "/keys"
            | "/key_requests"
            | "/revoke_key"
            | "/help"
            | "/start"
    ) {
        return Ok(false);
    }
    let Some(sender) = private_sender(message) else {
        return Ok(!matches!(command, "/help" | "/start"));
    };
    let admin = state.chat_ids.contains(&sender);
    if matches!(command, "/help" | "/start") {
        if admin {
            return Ok(false);
        }
        state.telegram.send_message(sender,
            "申请后端 API Key：\n/request_key device — 申请首次授权后绑定本安装/设备的密钥\n/request_key general — 申请不绑定设备的通用密钥\n/my_keys [页码] — 查看自己的分发记录\n申请需管理员批准，完整密钥仅私聊发送一次。", None).await?;
        return Ok(true);
    }
    if text.len() > 512 {
        return Ok(true);
    }
    if matches!(command, "/keys" | "/key_requests" | "/revoke_key") && !admin {
        state
            .telegram
            .send_message(sender, "该操作仅限管理员。", None)
            .await?;
        return Ok(true);
    }
    let keys = AccessKeys::new(state.pool.clone());
    match command {
        "/request_key" => {
            let Some(kind) = KeyKind::parse(words.next().unwrap_or("device")) else {
                state
                    .telegram
                    .send_message(
                        sender,
                        "用法：/request_key device 或 /request_key general",
                        None,
                    )
                    .await?;
                return Ok(true);
            };
            let Some(message_id) = message["message_id"].as_i64() else {
                return Ok(true);
            };
            let (request, created) = match keys.request(sender, message_id, kind).await {
                Ok(result) => result,
                Err(AppError::QuotaExceeded { .. }) => {
                    state
                        .telegram
                        .send_message(sender, "今日申请次数已达上限，请明日再试。", None)
                        .await?;
                    return Ok(true);
                }
                Err(error) => return Err(error),
            };
            state.telegram.send_message(sender, &format!("申请 #{}，状态：{}。重复申请不会重复发放；已发放记录可通过 /my_keys 查看。", request.id, escape(&request.state)), None).await?;
            if created {
                for admin_id in state.chat_ids.iter().copied().filter(|id| *id > 0) {
                    state
                        .telegram
                        .send_message(admin_id, &request_text(&request), Some(buttons(request.id)))
                        .await?;
                }
            }
        }
        "/keys" | "/my_keys" => {
            let Some(offset) = page_offset(words.next()) else {
                state
                    .telegram
                    .send_message(sender, "页码须为 1–100000。", None)
                    .await?;
                return Ok(true);
            };
            let rows = keys
                .list(
                    if command == "/my_keys" {
                        Some(sender)
                    } else {
                        None
                    },
                    offset,
                )
                .await?;
            let mut text = format!(
                "<b>API Key 分发记录，第 {} 页</b>\n仅显示遮罩标识，不回显完整密钥。",
                offset / 10 + 1
            );
            for row in rows {
                let kind = KeyKind::parse(&row.kind)
                    .map(KeyKind::label)
                    .unwrap_or("未知");
                let binding = if row.kind == "general" {
                    "不绑定设备".to_owned()
                } else {
                    row.device_hash
                        .as_ref()
                        .map(|hash| format!("已绑定 #{}", &hash[..12]))
                        .unwrap_or_else(|| "首次授权后绑定".into())
                };
                let status = match row.status.as_str() {
                    "active" => "有效",
                    "revoked" => "已撤销",
                    "delivery_pending" => "待送达",
                    _ => "未知",
                };
                text.push_str(&format!("\n\n#{} <code>{}…</code> {}\n用户 {} · {} · {} · 发放管理员 {}\n发放 {}\n首次授权 {}\n最近使用 {}",
                    row.id, escape(&row.token_prefix), kind, row.owner_id, status, binding, row.issued_by,
                    utc_time(Some(row.created_at)), utc_time(row.first_authorized_at), utc_time(row.last_used_at)));
            }
            state.telegram.send_message(sender, &text, None).await?;
        }
        "/key_requests" => {
            let Some(offset) = page_offset(words.next()) else {
                return Ok(true);
            };
            let requests = keys.pending(offset).await?;
            if requests.is_empty() {
                state
                    .telegram
                    .send_message(sender, "本页没有待审批申请。", None)
                    .await?;
            }
            for request in requests {
                state
                    .telegram
                    .send_message(sender, &request_text(&request), Some(buttons(request.id)))
                    .await?;
            }
        }
        "/revoke_key" => {
            let id = words
                .next()
                .and_then(|word| word.parse::<i64>().ok())
                .filter(|id| *id > 0);
            let Some(id) = id else {
                state
                    .telegram
                    .send_message(sender, "用法：/revoke_key 密钥编号（不是完整密钥）", None)
                    .await?;
                return Ok(true);
            };
            let revoked = keys.revoke(id, sender).await?;
            state
                .telegram
                .send_message(
                    sender,
                    if revoked {
                        "密钥已撤销，后续请求将被拒绝。"
                    } else {
                        "密钥不存在或已经撤销。"
                    },
                    None,
                )
                .await?;
        }
        _ => {}
    }
    Ok(true)
}

pub async fn callback(state: &BotState, callback: &Value) -> Result<bool, AppError> {
    let data = callback["data"].as_str().unwrap_or("");
    if !data.starts_with("key_approve:") && !data.starts_with("key_reject:") {
        return Ok(false);
    }
    let sender = callback["from"]["id"].as_i64();
    let message = &callback["message"];
    let chat = message["chat"]["id"].as_i64();
    if message["chat"]["type"].as_str() != Some("private")
        || sender != chat
        || !sender.is_some_and(|id| id > 0 && state.chat_ids.contains(&id))
    {
        return Ok(true);
    }
    let admin = sender.unwrap();
    let callback_id = callback["id"].as_str().unwrap_or("");
    let keys = AccessKeys::new(state.pool.clone());
    let (id, reply) = if let Some(raw) = data.strip_prefix("key_reject:") {
        let Some(id) = raw.parse::<i64>().ok().filter(|id| *id > 0) else {
            return Ok(true);
        };
        let rejected = keys.reject(id, admin).await?;
        (
            id,
            if rejected {
                "申请已拒绝。"
            } else {
                "该申请已被处理。"
            }
            .to_owned(),
        )
    } else {
        let mut parts = data.split(':');
        parts.next();
        let Some(kind) = parts.next().and_then(KeyKind::parse) else {
            return Ok(true);
        };
        let Some(id) = parts
            .next()
            .and_then(|v| v.parse::<i64>().ok())
            .filter(|id| *id > 0)
        else {
            return Ok(true);
        };
        let Some(issued) = keys.approve(id, admin, kind).await? else {
            state
                .telegram
                .answer_callback(callback_id, "该申请已被处理或正在发放，不会重复生成密钥。")
                .await?;
            return Ok(true);
        };
        let notice = format!(
            "<b>API Key 已批准</b>\n编号：#{}\n类型：{}\n<code>{}</code>\n\n复制到客户端后端设置。完整密钥只发送一次，请妥善保存。设备密钥绑定首次授权时的 UUID + ANDROID_ID 摘要；卸载/清除数据后需重新申请。",
            issued.id,
            issued.kind.label(),
            issued.secret
        );
        // This special transport never echoes a sensitive Telegram body/provider error.
        let delivered = state
            .telegram
            .send_private_key(issued.owner_id, &notice)
            .await
            .is_ok();
        keys.finish_delivery(&issued, delivered).await?;
        (
            id,
            if delivered {
                format!("申请已批准，密钥 #{} 已私聊发给申请者。", issued.id)
            } else {
                "密钥未能送达，已作废。本申请仍待审批，可通过 /key_requests 重试。".into()
            },
        )
    };
    state.telegram.answer_callback(callback_id, &reply).await?;
    if let Some(message_id) = message["message_id"].as_i64() {
        state
            .telegram
            .edit_message(admin, message_id, &format!("申请 #{id}\n{reply}"), None)
            .await?;
    }
    Ok(true)
}
