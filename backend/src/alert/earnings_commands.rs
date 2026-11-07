//! Administrator-only, live single-source earnings diagnostics. No cache/fallback success.
use super::{
    bot::BotState,
    telegram::{InlineButton, escape},
};
use crate::{
    earnings::{self, EarningsSource},
    earnings_logo::normalize_symbol,
    error::AppError,
};
use chrono::{NaiveDate, Utc};
use chrono_tz::America::New_York;
use serde_json::Value;

fn today() -> NaiveDate {
    Utc::now().with_timezone(&New_York).date_naive()
}

pub(super) fn admin_chat(state: &BotState, message: &Value, sender: &Value) -> Option<i64> {
    let chat = message.get("chat")?;
    let id = chat.get("id")?.as_i64()?;
    (chat.get("type")?.as_str()? == "private"
        && sender.get("id")?.as_i64()? == id
        && state.chat_ids.contains(&id))
    .then_some(id)
}

pub(super) fn buttons(date: NaiveDate, symbol: &str) -> Vec<Vec<InlineButton>> {
    vec![
        vec![
            InlineButton {
                text: "Nasdaq 财报".into(),
                callback_data: format!("earnprobe:n:{date}:{symbol}"),
            },
            InlineButton {
                text: "TradingView 财报".into(),
                callback_data: format!("earnprobe:t:{date}:{symbol}"),
            },
        ],
        vec![InlineButton {
            text: format!("公司 Logo · {symbol}（实时）"),
            callback_data: format!("earnprobe:l:{date}:{symbol}"),
        }],
    ]
}

const USAGE: &str = "用法：/test_api [YYYY-MM-DD] [股票代码]\n财报/Logo 默认纽约今天、AAPL；日期和股票参数不影响行情/宏观日历测试。日期范围为今天前120天至后60天；TradingView 财报只测试今天及未来的下一期预测。\n例：/test_api 2026-11-02 AAPL";

fn parse_date(value: &str) -> Result<NaiveDate, &'static str> {
    let date = NaiveDate::parse_from_str(value, "%Y-%m-%d").map_err(|_| USAGE)?;
    if date.to_string() != value || earnings::validate_date(date).is_err() {
        return Err(USAGE);
    }
    Ok(date)
}

/// Shared target parser for the unified command and menu navigation callbacks.
pub(super) fn parse_target(args: &[&str]) -> Result<(NaiveDate, String), &'static str> {
    if args.len() > 2 {
        return Err(USAGE);
    }
    let date = args
        .first()
        .map(|value| parse_date(value))
        .transpose()?
        .unwrap_or_else(today);
    let symbol = normalize_symbol(args.get(1).copied().unwrap_or("AAPL")).ok_or(USAGE)?;
    Ok((date, symbol))
}

pub(super) async fn callback(state: &BotState, callback: &Value) -> Result<bool, AppError> {
    let data = callback
        .get("data")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let Some(payload) = data.strip_prefix("earnprobe:") else {
        return Ok(false);
    };
    let Some(chat_id) = admin_chat(state, &callback["message"], &callback["from"]) else {
        return Ok(true);
    };
    let id = callback
        .get("id")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let parsed = (|| {
        let parts: Vec<_> = payload.split(':').take(4).collect();
        if data.len() > 64 || parts.len() != 3 || !matches!(parts[0], "n" | "t" | "l") {
            return Err(USAGE);
        }
        Ok((
            parts[0],
            parse_date(parts[1])?,
            normalize_symbol(parts[2]).ok_or(USAGE)?,
        ))
    })();
    let Ok((source, date, symbol)) = parsed else {
        state
            .telegram
            .answer_callback(id, "测试参数无效或已过期，请重新发送 /test_api")
            .await?;
        return Ok(true);
    };
    let (label, network) = match source {
        "n" => ("Nasdaq 财报", "nasdaq"),
        "t" => ("TradingView 财报", "tradingview"),
        _ => ("公司 Logo", "earnings_logo"),
    };
    // Telegram must be acknowledged before any upstream request, and callback text stays short.
    state
        .telegram
        .answer_callback(id, &format!("正在测试 {label}…"))
        .await?;
    let result = match source {
        "n" => state.earnings.probe(EarningsSource::Nasdaq, date).await,
        "t" => {
            state
                .earnings
                .probe(EarningsSource::TradingView, date)
                .await
        }
        _ => state.earnings.logos.probe(&symbol).await,
    };
    let route = if state.config.network.proxies(network) {
        "代理"
    } else {
        "直连"
    };
    let (icon, outcome) = match result {
        Ok(evidence) => (
            "✅",
            format!("请求与数据校验通过\n<code>{}</code>", escape(&evidence)),
        ),
        Err(AppError::QuotaExceeded {
            retry_after_seconds,
        }) => (
            "⚠️",
            format!(
                "冷却中，约 {retry_after_seconds} 秒后重试；HTTP=not_requested，未发起新请求。"
            ),
        ),
        Err(error) => {
            let detail = match error {
                AppError::Provider(detail) | AppError::InvalidRequest(detail) => detail,
                AppError::Http(error) => format!("network={}", earnings::network_failure(&error)),
                _ => "诊断未完成：请检查服务器日志及缓存目录读写权限。".into(),
            };
            (
                "❌",
                format!("请求或数据校验未通过\n<code>{}</code>", escape(&detail)),
            )
        }
    };
    let note = if source == "l" {
        "实时检查 PNG，不使用磁盘旧图兜底；缺少单家公司图片不代表财报日历不可用。"
    } else {
        "validated_rows=0 仅表示该源返回了可验证的空结果；data=null/缺失表示该日期数据不可用，不能据此确认空日或全源断网。错日期、截断仍是失败。测试不写财报缓存，不重置后台健康计数；连续失败次数按日期请求累计，不是小时数。"
    };
    let text = format!(
        "{icon} <b>{label}</b>\n纽约日期：{date}\n股票代码：{}\n出网：{route}（{network}）\n{outcome}\n\n{note}",
        escape(&symbol)
    );
    if let Some(message_id) = callback["message"]["message_id"].as_i64() {
        let mut keyboard = buttons(date, &symbol);
        keyboard.push(vec![InlineButton {
            text: "← 返回 API 菜单".into(),
            callback_data: format!("api_menu:{date}:{symbol}"),
        }]);
        state
            .telegram
            .edit_message(chat_id, message_id, &text, Some(keyboard))
            .await?;
    } else {
        state.telegram.send_message(chat_id, &text, None).await?;
    }
    Ok(true)
}
