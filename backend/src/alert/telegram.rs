use serde_json::{Value, json};

use crate::error::AppError;

/// Minimal Telegram Bot API client.
///
/// Long polling is used instead of a webhook: the server is behind a domestic proxy and has no
/// reason to expose an inbound endpoint, and the update offset is persisted so a restart does
/// not replay already-handled commands.
#[derive(Clone)]
pub struct TelegramClient {
    client: reqwest::Client,
    api_base: String,
    token: String,
}

/// One button of an inline keyboard row.
#[derive(Clone)]
pub struct InlineButton {
    pub text: String,
    pub callback_data: String,
}

impl TelegramClient {
    pub fn new(
        client: reqwest::Client,
        api_base: impl Into<String>,
        token: impl Into<String>,
    ) -> Self {
        Self {
            client,
            api_base: api_base.into().trim_end_matches('/').to_owned(),
            token: token.into(),
        }
    }

    fn url(&self, method: &str) -> String {
        format!("{}/bot{}/{}", self.api_base, self.token, method)
    }

    /// Sends a message and returns its `message_id` when Telegram accepted it.
    pub async fn send_message(
        &self,
        chat_id: i64,
        text: &str,
        keyboard: Option<Vec<Vec<InlineButton>>>,
    ) -> Result<Option<i64>, AppError> {
        let mut payload = json!({
            "chat_id": chat_id,
            "text": text,
            "parse_mode": "HTML",
            "disable_web_page_preview": true,
        });
        if let Some(rows) = keyboard {
            let inline: Vec<Vec<Value>> = rows
                .into_iter()
                .map(|row| {
                    row.into_iter()
                        .map(|button| {
                            json!({"text": button.text, "callback_data": button.callback_data})
                        })
                        .collect()
                })
                .collect();
            payload["reply_markup"] = json!({ "inline_keyboard": inline });
        }
        let response = self
            .client
            .post(self.url("sendMessage"))
            .json(&payload)
            .send()
            .await?;
        let body: Value = response.json().await?;
        if body.get("ok").and_then(Value::as_bool) != Some(true) {
            return Err(AppError::Provider(format!(
                "Telegram sendMessage failed: {}",
                body.get("description")
                    .and_then(Value::as_str)
                    .unwrap_or("unknown error")
            )));
        }
        Ok(body
            .get("result")
            .and_then(|result| result.get("message_id"))
            .and_then(Value::as_i64))
    }

    /// Replaces the buttons of an already-sent message, so a decided request cannot be
    /// applied twice and the admin sees the outcome inline.
    pub async fn close_keyboard(
        &self,
        chat_id: i64,
        message_id: i64,
        text: &str,
    ) -> Result<(), AppError> {
        let payload = json!({
            "chat_id": chat_id,
            "message_id": message_id,
            "reply_markup": { "inline_keyboard": [] },
        });
        let response = self
            .client
            .post(self.url("editMessageReplyMarkup"))
            .json(&payload)
            .send()
            .await?;
        if !response.status().is_success() {
            tracing::debug!(text, "Telegram keyboard edit failed");
        }
        Ok(())
    }

    pub async fn answer_callback(&self, callback_id: &str, text: &str) -> Result<(), AppError> {
        let payload = json!({ "callback_query_id": callback_id, "text": text });
        let _ = self
            .client
            .post(self.url("answerCallbackQuery"))
            .json(&payload)
            .send()
            .await?;
        Ok(())
    }

    /// Long-polls for updates. Returns raw update objects; callers pick the fields they know.
    pub async fn get_updates(
        &self,
        offset: i64,
        timeout_seconds: u64,
    ) -> Result<Vec<Value>, AppError> {
        let payload = json!({
            "offset": offset,
            "timeout": timeout_seconds,
            "allowed_updates": ["message", "callback_query"],
        });
        let response = self
            .client
            .post(self.url("getUpdates"))
            .json(&payload)
            .send()
            .await?;
        let body: Value = response.json().await?;
        if body.get("ok").and_then(Value::as_bool) != Some(true) {
            return Err(AppError::Provider(format!(
                "Telegram getUpdates failed: {}",
                body.get("description")
                    .and_then(Value::as_str)
                    .unwrap_or("unknown error")
            )));
        }
        Ok(body
            .get("result")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default())
    }
}

/// Telegram only parses a small HTML subset; unescaped `<`/`&` would break the message.
pub fn escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}
