//! A single admin-only API diagnostic entry point. Opening a menu never probes an upstream.
use super::{
    bot::BotState,
    earnings_commands,
    telegram::{InlineButton, escape},
};
use crate::error::AppError;
use chrono::NaiveDate;
use serde_json::Value;

fn panel(date: NaiveDate, symbol: &str) -> (String, Vec<Vec<InlineButton>>) {
    let text = format!(
        "<b>API 连通性测试</b>\n请选择具体 API；行情接口会继续显示标的按钮。\n\n财报/Logo 目标：纽约日期 {date}，股票 {}\n可用 /test_api YYYY-MM-DD 股票代码 修改。\n每次只测试所选接口；财报/Logo 不用缓存或备用源判定成功，沿用现有 60 秒诊断冷却。",
        escape(symbol)
    );
    let mut buttons: Vec<Vec<InlineButton>> = [
        [
            ("行情 · Yahoo Finance", "yahoo"),
            ("行情 · BiQuote", "biquote"),
        ]
        .as_slice(),
        [("行情 · Binance", "binance"), ("行情 · CNBC", "cnbc")].as_slice(),
        [
            ("宏观 · TradingView", "tradingview"),
            ("宏观 · BiQuote", "biquote_calendar"),
        ]
        .as_slice(),
        [("宏观 · Forex Factory", "forexfactory")].as_slice(),
        [
            ("宏观 · TE 页面", "tradingeconomics"),
            ("宏观 · TE API", "te_api"),
        ]
        .as_slice(),
    ]
    .into_iter()
    .map(|row| {
        row.iter()
            .map(|(label, provider)| InlineButton {
                text: (*label).into(),
                callback_data: format!("apitest:{provider}"),
            })
            .collect()
    })
    .collect();
    buttons.extend(earnings_commands::buttons(date, symbol));
    (text, buttons)
}

pub(super) fn back_buttons() -> Vec<Vec<InlineButton>> {
    vec![vec![InlineButton {
        text: "← 返回 API 菜单".into(),
        callback_data: "api_menu".into(),
    }]]
}

pub(super) async fn message(state: &BotState, message: &Value) -> Result<bool, AppError> {
    let text = message["text"].as_str().unwrap_or_default().trim();
    let mut words = text.split_whitespace();
    let command = words
        .next()
        .unwrap_or_default()
        .split('@')
        .next()
        .unwrap_or_default();
    // Hidden compatibility aliases all open the same menu; only /test_api is advertised.
    if !matches!(
        command,
        "/test_api" | "/test_market_api" | "/test_calendar_api" | "/test_earnings_api"
    ) {
        return Ok(false);
    }
    let Some(chat_id) = earnings_commands::admin_chat(state, message, &message["from"]) else {
        return Ok(true);
    };
    let args: Vec<_> = words.take(3).collect();
    match earnings_commands::parse_target(&args) {
        Ok((date, symbol)) => {
            let (text, buttons) = panel(date, &symbol);
            state
                .telegram
                .send_message(chat_id, &text, Some(buttons))
                .await?;
        }
        Err(usage) => {
            state.telegram.send_message(chat_id, usage, None).await?;
        }
    }
    Ok(true)
}

pub(super) async fn callback(state: &BotState, callback: &Value) -> Result<bool, AppError> {
    let data = callback["data"].as_str().unwrap_or_default();
    if data != "api_menu" && !data.starts_with("api_menu:") {
        return Ok(false);
    }
    let Some(chat_id) =
        earnings_commands::admin_chat(state, &callback["message"], &callback["from"])
    else {
        return Ok(true);
    };
    let id = callback["id"].as_str().unwrap_or_default();
    let args: Vec<_> = data
        .strip_prefix("api_menu:")
        .map(|payload| payload.split(':').take(3).collect())
        .unwrap_or_default();
    let parsed = if data.len() <= 64 && (data == "api_menu" || args.len() == 2) {
        earnings_commands::parse_target(&args).ok()
    } else {
        None
    };
    let Some((date, symbol)) = parsed else {
        state
            .telegram
            .answer_callback(id, "菜单参数无效或已过期，请重新发送 /test_api")
            .await?;
        return Ok(true);
    };
    state.telegram.answer_callback(id, "请选择 API").await?;
    let (text, buttons) = panel(date, &symbol);
    if let Some(message_id) = callback["message"]["message_id"].as_i64() {
        state
            .telegram
            .edit_message(chat_id, message_id, &text, Some(buttons))
            .await?;
    } else {
        state
            .telegram
            .send_message(chat_id, &text, Some(buttons))
            .await?;
    }
    Ok(true)
}
