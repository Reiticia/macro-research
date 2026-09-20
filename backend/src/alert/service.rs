use std::sync::Arc;

use chrono::{DateTime, Timelike, Utc};
use chrono_tz::Tz;
use sqlx::SqlitePool;

use crate::{alert::telegram::TelegramClient, config::AlertConfig, error::AppError};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Severity {
    Info,
    Warning,
    Critical,
}

impl Severity {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Info => "info",
            Self::Warning => "warning",
            Self::Critical => "critical",
        }
    }

    fn icon(self) -> &'static str {
        match self {
            Self::Info => "✅",
            Self::Warning => "⚠️",
            Self::Critical => "🚨",
        }
    }
}

/// Formats and delivers admin notifications. Quiet hours suppress everything except critical
/// alerts, and every attempt is logged so a silent bot is distinguishable from a silent source.
pub struct AlertService {
    telegram: Option<Arc<TelegramClient>>,
    chat_ids: Vec<i64>,
    pool: SqlitePool,
    config: AlertConfig,
    quiet_window: Option<(u32, u32)>,
}

impl AlertService {
    pub fn new(
        telegram: Option<Arc<TelegramClient>>,
        chat_ids: Vec<i64>,
        pool: SqlitePool,
        config: AlertConfig,
    ) -> Self {
        let quiet_window = parse_quiet_hours(&config.quiet_hours);
        Self {
            telegram,
            chat_ids,
            pool,
            config,
            quiet_window,
        }
    }

    pub fn enabled(&self) -> bool {
        self.telegram.is_some() && !self.chat_ids.is_empty()
    }

    /// Local time inside the configured window suppresses non-critical alerts.
    pub fn quiet_now(&self, now: DateTime<Utc>) -> bool {
        let Some((start, end)) = self.quiet_window else {
            return false;
        };
        let zone: Tz = self
            .config
            .quiet_hours_timezone
            .parse()
            .unwrap_or(chrono_tz::UTC);
        let local = now.with_timezone(&zone);
        let minutes = local.hour() * 60 + local.minute();
        if start <= end {
            minutes >= start && minutes < end
        } else {
            minutes >= start || minutes < end
        }
    }

    pub async fn notify(
        &self,
        severity: Severity,
        key: &str,
        status: &str,
        message: String,
    ) -> Result<(), AppError> {
        let _ = self.audit(severity, key, status, &message).await;
        if severity != Severity::Critical && self.quiet_now(Utc::now()) {
            tracing::debug!(key, "alert suppressed by quiet hours");
            return Ok(());
        }
        self.send_to_admins(severity, key, &message).await
    }

    /// Sends regardless of quiet hours.
    ///
    /// Used for the startup notice: a restart is rare enough to be worth hearing about at night,
    /// and the very first boot must prove the bot token and chat id actually work.
    pub async fn notify_unsuppressed(
        &self,
        severity: Severity,
        key: &str,
        status: &str,
        message: String,
    ) -> Result<(), AppError> {
        let _ = self.audit(severity, key, status, &message).await;
        self.send_to_admins(severity, key, &message).await
    }

    async fn audit(
        &self,
        severity: Severity,
        key: &str,
        status: &str,
        message: &str,
    ) -> Result<(), AppError> {
        sqlx::query(
            r#"INSERT INTO alert_event (key, status, severity, message, created_at)
               VALUES (?, ?, ?, ?, ?)"#,
        )
        .bind(key)
        .bind(status)
        .bind(severity.as_str())
        .bind(message)
        .bind(Utc::now().to_rfc3339())
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    async fn send_to_admins(
        &self,
        severity: Severity,
        key: &str,
        message: &str,
    ) -> Result<(), AppError> {
        let Some(telegram) = &self.telegram else {
            tracing::info!(key, %message, "alert (no Telegram bot configured)");
            return Ok(());
        };
        let text = format!(
            "{} <b>{}</b>\n{}\n<i>{}</i>",
            severity.icon(),
            crate::alert::telegram::escape(key),
            crate::alert::telegram::escape(message),
            Utc::now().format("%Y-%m-%d %H:%M UTC"),
        );
        for chat_id in &self.chat_ids {
            if let Err(error) = telegram.send_message(*chat_id, &text, None).await {
                tracing::warn!(chat_id, %error, "Telegram alert delivery failed");
            }
        }
        Ok(())
    }

    /// Sends a message with inline buttons and returns the resulting message id per chat.
    pub async fn notify_with_buttons(
        &self,
        chat_id: i64,
        text: &str,
        buttons: Vec<Vec<crate::alert::telegram::InlineButton>>,
    ) -> Result<Option<i64>, AppError> {
        let Some(telegram) = &self.telegram else {
            return Ok(None);
        };
        telegram.send_message(chat_id, text, Some(buttons)).await
    }

    pub fn chat_ids(&self) -> &[i64] {
        &self.chat_ids
    }

    pub fn telegram(&self) -> Option<&Arc<TelegramClient>> {
        self.telegram.as_ref()
    }
}

/// `23:00-07:00` into a `(start_minutes, end_minutes)` window.
fn parse_quiet_hours(raw: &str) -> Option<(u32, u32)> {
    let (start, end) = raw.trim().split_once('-')?;
    Some((parse_clock(start)?, parse_clock(end)?))
}

fn parse_clock(value: &str) -> Option<u32> {
    let (hours, minutes) = value.trim().split_once(':')?;
    let hours: u32 = hours.parse().ok()?;
    let minutes: u32 = minutes.parse().ok()?;
    (hours < 24 && minutes < 60).then_some(hours * 60 + minutes)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn quiet_hours_wrap_past_midnight() {
        let config = AlertConfig {
            quiet_hours: "23:00-07:00".into(),
            quiet_hours_timezone: "Asia/Shanghai".into(),
            ..AlertConfig::default()
        };
        let pool = SqlitePool::connect_lazy("sqlite::memory:").unwrap();
        let service = AlertService::new(None, Vec::new(), pool, config);
        let at = |hour: u32| {
            DateTime::parse_from_rfc3339(&format!("2026-09-11T{hour:02}:30:00Z"))
                .unwrap()
                .with_timezone(&Utc)
        };
        // 16:30 UTC is 00:30 in Shanghai.
        assert!(service.quiet_now(at(16)));
        assert!(service.quiet_now(at(22)));
        // 04:30 UTC is 12:30 in Shanghai.
        assert!(!service.quiet_now(at(4)));
        assert!(parse_clock("24:00").is_none());
        assert!(parse_quiet_hours("").is_none());
    }

    /// The startup notice must arrive even inside quiet hours, while regular alerts stay
    /// silenced — otherwise the first boot of the day looks like a broken bot.
    #[tokio::test]
    async fn startup_notice_bypasses_quiet_hours_but_regular_alerts_do_not() {
        use crate::alert::telegram::TelegramClient;
        use axum::{Json, Router, routing::post};
        use std::sync::Arc as StdArc;
        use tokio::sync::Mutex;

        let captured = StdArc::new(Mutex::new(Vec::<serde_json::Value>::new()));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        let router_state = StdArc::clone(&captured);
        let task = tokio::spawn(async move {
            let capture = move |Json(body): Json<serde_json::Value>| {
                let captured = StdArc::clone(&router_state);
                async move {
                    captured.lock().await.push(body);
                    Json(serde_json::json!({"ok": true, "result": {"message_id": 1}}))
                }
            };
            axum::serve(
                listener,
                Router::new().route("/botbottoken/sendMessage", post(capture)),
            )
            .await
            .unwrap();
        });

        let pool = sqlx::SqlitePool::connect("sqlite::memory:").await.unwrap();
        sqlx::migrate!("./migrations").run(&pool).await.unwrap();
        let config = AlertConfig {
            // Quiet around the clock, so anything that arrives is an explicit bypass.
            quiet_hours: "00:00-23:59".into(),
            quiet_hours_timezone: "UTC".into(),
            ..AlertConfig::default()
        };
        let service = AlertService::new(
            Some(Arc::new(TelegramClient::new(
                reqwest::Client::new(),
                base.clone(),
                "bottoken",
            ))),
            vec![42],
            pool,
            config,
        );

        service
            .notify(Severity::Info, "source.quiet", "healthy", "muted".into())
            .await
            .unwrap();
        assert_eq!(captured.lock().await.len(), 0);

        // Sanity check: a direct send must reach the mock, so a failure below is in the service,
        // not in the fixture.
        let direct = TelegramClient::new(reqwest::Client::new(), base.clone(), "bottoken");
        direct.send_message(42, "direct probe", None).await.unwrap();
        assert_eq!(captured.lock().await.len(), 1);

        service
            .notify_unsuppressed(
                Severity::Info,
                "service.startup",
                "healthy",
                "up".to_owned(),
            )
            .await
            .unwrap();
        let sent = captured.lock().await;
        // One direct probe plus the startup notice.
        assert_eq!(sent.len(), 2);
        let text = sent[1]["text"].as_str().unwrap();
        assert!(text.contains("service.startup"), "{text}");
        assert!(text.contains("up"), "{text}");
        task.abort();
    }
}
