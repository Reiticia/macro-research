//! Immutable public briefings: scheduled generation, durable retries and administrator review.
use crate::{
    AppState,
    ai_analysis::AnalysisMethod,
    alert::{
        AlertService,
        telegram::{InlineButton, escape},
    },
    error::AppError,
};
use chrono::Utc;
use sqlx::{Row, SqlitePool};
use std::{sync::Arc, time::Duration};

pub fn language(value: &str) -> &'static str {
    if value.starts_with("zh") {
        if value.contains("TW") || value.contains("HK") || value.contains("Hant") {
            "zh-TW"
        } else {
            "zh-CN"
        }
    } else {
        "en"
    }
}

pub async fn submit_feedback(
    pool: &SqlitePool,
    event: i64,
    method: u8,
    lang: &str,
    revision: i64,
    caller: &str,
    message: &str,
) -> Result<i64, AppError> {
    if !(1..=3).contains(&method) || message.trim().is_empty() || message.chars().count() > 2000 {
        return Err(AppError::InvalidRequest(
            "method must be 1..3 and feedback must contain 1..2000 characters".into(),
        ));
    }
    let id: Option<i64> = sqlx::query_scalar("SELECT id FROM ai_analysis WHERE event_id=? AND method=? AND language=? AND timezone='UTC' AND revision=?")
        .bind(event).bind(method).bind(language(lang)).bind(revision).fetch_optional(pool).await?;
    let id = id.ok_or(AppError::NotFound)?;
    sqlx::query("INSERT INTO analysis_feedback(analysis_id,revision,requested_by,message,created_at) VALUES(?,?,?,?,?) ON CONFLICT(analysis_id,revision) DO NOTHING")
        .bind(id).bind(revision).bind(caller).bind(message.trim()).bind(Utc::now().to_rfc3339()).execute(pool).await?;
    Ok(
        sqlx::query_scalar("SELECT id FROM analysis_feedback WHERE analysis_id=? AND revision=?")
            .bind(id)
            .bind(revision)
            .fetch_one(pool)
            .await?,
    )
}

/// Called only after the bot has checked the administrator chat allowlist.
pub async fn decide(pool: &SqlitePool, id: i64, regenerate: bool) -> Result<(), AppError> {
    let mut tx = pool.begin().await?;
    let row = sqlx::query("SELECT f.status,f.revision,a.event_id,a.method,a.language,a.revision AS current_revision FROM analysis_feedback f JOIN ai_analysis a ON a.id=f.analysis_id WHERE f.id=?")
        .bind(id).fetch_optional(&mut *tx).await?.ok_or(AppError::NotFound)?;
    let status: String = row.try_get("status")?;
    if status != "pending" {
        return Ok(());
    }
    let revision: i64 = row.try_get("revision")?;
    let current: i64 = row.try_get("current_revision")?;
    let next = if regenerate && revision == current {
        "queued"
    } else {
        "ignored"
    };
    if next == "queued" {
        sqlx::query("INSERT INTO shared_ai_job(event_id,method,language,target_revision) VALUES(?,?,?,?) ON CONFLICT(event_id,method,language) DO UPDATE SET target_revision=excluded.target_revision,status='pending',retry_at='',last_error=NULL")
            .bind(row.try_get::<i64,_>("event_id")?).bind(row.try_get::<i64,_>("method")?).bind(row.try_get::<String,_>("language")?).bind(current+1).execute(&mut *tx).await?;
    }
    sqlx::query("UPDATE analysis_feedback SET status=?,decided_at=? WHERE id=?")
        .bind(next)
        .bind(Utc::now().to_rfc3339())
        .bind(id)
        .execute(&mut *tx)
        .await?;
    tx.commit().await?;
    Ok(())
}

pub async fn run(state: AppState, alerts: Arc<AlertService>) {
    loop {
        if let Err(error) = notify_feedback(&state, &alerts).await {
            tracing::warn!(%error,"analysis feedback notification failed; will retry");
        }
        if state.ai_analysis_service.is_some() {
            if let Err(error) = generate_pending(&state).await {
                tracing::warn!(%error,"shared AI jobs failed; will retry");
            }
        }
        tokio::time::sleep(Duration::from_secs(30)).await;
    }
}

async fn notify_feedback(state: &AppState, alerts: &AlertService) -> Result<(), AppError> {
    if !alerts.enabled() {
        return Ok(());
    }
    let pool = state.events.pool();
    let rows = sqlx::query("SELECT f.id,f.message,f.revision,a.event_id,a.method,a.language,e.event FROM analysis_feedback f JOIN ai_analysis a ON a.id=f.analysis_id JOIN economic_event e ON e.id=a.event_id WHERE f.notified=0 AND f.status='pending' ORDER BY f.id LIMIT 20").fetch_all(pool).await?;
    for row in rows {
        let id: i64 = row.try_get("id")?;
        let text = format!(
            "AI 市场分析反馈 #{}\n事件 #{}：{}\n方法 {} · {} · 版本 {}\n{}",
            id,
            row.try_get::<i64, _>("event_id")?,
            escape(&row.try_get::<String, _>("event")?),
            row.try_get::<i64, _>("method")?,
            row.try_get::<String, _>("language")?,
            row.try_get::<i64, _>("revision")?,
            escape(&row.try_get::<String, _>("message")?)
        );
        for chat in state.config.telegram.admin_chat_ids() {
            alerts
                .notify_with_buttons(
                    chat,
                    &text,
                    vec![vec![
                        InlineButton {
                            text: "重新分析".into(),
                            callback_data: format!("ag:{id}"),
                        },
                        InlineButton {
                            text: "忽略".into(),
                            callback_data: format!("ai:{id}"),
                        },
                    ]],
                )
                .await?;
        }
        sqlx::query("UPDATE analysis_feedback SET notified=1 WHERE id=?")
            .bind(id)
            .execute(pool)
            .await?;
    }
    Ok(())
}

async fn generate_pending(state: &AppState) -> Result<(), AppError> {
    let pool = state.events.pool();
    let cutoff = Utc::now()
        - chrono::Duration::minutes(state.config.scheduler.market_collect_after_minutes.max(0));
    // Catch releases even if a restart or provider fallback bypassed the live watcher.
    let events: Vec<i64> = sqlx::query_scalar(
        "SELECT id FROM economic_event WHERE actual IS NOT NULL AND event_time<=?",
    )
    .bind(cutoff.to_rfc3339())
    .fetch_all(pool)
    .await?;
    for event in events {
        for lang in ["en", "zh-CN", "zh-TW"] {
            for method in 1..=3i64 {
                sqlx::query(
                    "INSERT OR IGNORE INTO shared_ai_job(event_id,method,language) VALUES(?,?,?)",
                )
                .bind(event)
                .bind(method)
                .bind(lang)
                .execute(pool)
                .await?;
            }
        }
    }
    let jobs = sqlx::query("SELECT event_id,method,language,target_revision FROM shared_ai_job WHERE status='pending' AND retry_at<=? ORDER BY event_id DESC,method,language LIMIT 9").bind(Utc::now().to_rfc3339()).fetch_all(pool).await?;
    let service = state.ai_analysis_service.as_ref().unwrap();
    for job in jobs {
        let event: i64 = job.try_get("event_id")?;
        let method: i64 = job.try_get("method")?;
        let lang: String = job.try_get("language")?;
        let target: i64 = job.try_get("target_revision")?;
        let result: Result<(), AppError> = async {
            let _slot = state.quota.acquire_ai_slot().await?;
            let kind = AnalysisMethod::from_u8(method as u8);
            if service
                .cached(event, &lang, kind, "UTC")
                .await?
                .is_some_and(|v| v.revision >= target)
            {
                return Ok(());
            }
            match state.analyses.get(event).await {
                Ok(_) => {}
                Err(AppError::NotFound) => {
                    state.analysis_service.analyze(event).await?;
                }
                Err(error) => return Err(error),
            }
            let response = service
                .generate(event, &lang, kind, "UTC", target > 1, "server-scheduler")
                .await?;
            // A cooldown response must not mark an administrator request as completed.
            if response.analysis.revision < target {
                return Err(AppError::Provider("regeneration cooling down".into()));
            }
            Ok(())
        }
        .await;
        match result {
            Ok(()) => {
                sqlx::query("UPDATE shared_ai_job SET status='completed',last_error=NULL WHERE event_id=? AND method=? AND language=? AND target_revision=?").bind(event).bind(method).bind(&lang).bind(target).execute(pool).await?;
                sqlx::query("UPDATE analysis_feedback SET status='completed' WHERE status='queued' AND revision<? AND analysis_id IN (SELECT id FROM ai_analysis WHERE event_id=? AND method=? AND language=? AND timezone='UTC')").bind(target).bind(event).bind(method).bind(&lang).execute(pool).await?;
            }
            Err(error) => {
                tracing::warn!(event,method,%lang,%error,"shared AI generation failed");
                sqlx::query("UPDATE shared_ai_job SET last_error=?,retry_at=? WHERE event_id=? AND method=? AND language=? AND target_revision=?").bind(error.to_string()).bind((Utc::now()+chrono::Duration::minutes(5)).to_rfc3339()).bind(event).bind(method).bind(&lang).bind(target).execute(pool).await?;
            }
        }
    }
    Ok(())
}
