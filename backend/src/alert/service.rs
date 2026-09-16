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
        let _ = sqlx::query(
            r#"INSERT INTO alert_event (key, status, severity, message, created_at)
               VALUES (?, ?, ?, ?, ?)"#,
        )
        .bind(key)
        .bind(status)
        .bind(severity.as_str())
        .bind(&message)
        .bind(Utc::now().to_rfc3339())
        .execute(&self.pool)
        .await;

        let Some(telegram) = &self.telegram else {
            tracing::info!(key, %message, "alert (no Telegram bot configured)");
            return Ok(());
        };
        if severity != Severity::Critical && self.quiet_now(Utc::now()) {
            tracing::debug!(key, "alert suppressed by quiet hours");
            return Ok(());
        }
        let text = format!(
            "{} <b>{}</b>\n{}\n<i>{}</i>",
            severity.icon(),
            crate::alert::telegram::escape(key),
            crate::alert::telegram::escape(&message),
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
}
