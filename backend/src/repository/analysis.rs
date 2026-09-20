use std::str::FromStr;

use chrono::Utc;
use sqlx::{Row, SqlitePool};

use crate::{
    error::AppError,
    model::{AnalysisReport, ExpectedReaction, MacroSignal, MarketReaction, ReactionComparison},
};

use super::{datetime_from_row, decimal_from_row};

#[derive(Clone)]
pub struct AnalysisRepository {
    pool: SqlitePool,
}

impl AnalysisRepository {
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    pub async fn save(&self, report: &AnalysisReport) -> Result<(), AppError> {
        let expected = serde_json::to_string(&report.expected_reactions)
            .map_err(|error| AppError::Internal(error.to_string()))?;
        let observed = serde_json::to_string(&serde_json::json!({
            "reactions": report.observed_reactions,
            "comparisons": report.comparisons,
            "historical": report.historical,
        }))
        .map_err(|error| AppError::Internal(error.to_string()))?;
        let now = Utc::now().to_rfc3339();

        sqlx::query(
            r#"INSERT INTO analysis_report (
                event_id, raw_surprise, macro_signal, expected_reaction_json,
                observed_reaction_json, summary, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(event_id) DO UPDATE SET
                raw_surprise = excluded.raw_surprise,
                macro_signal = excluded.macro_signal,
                expected_reaction_json = excluded.expected_reaction_json,
                observed_reaction_json = excluded.observed_reaction_json,
                summary = excluded.summary,
                updated_at = excluded.updated_at"#,
        )
        .bind(report.event_id)
        .bind(report.raw_surprise.map(|value| value.to_string()))
        .bind(report.macro_signal.as_str())
        .bind(expected)
        .bind(observed)
        .bind(&report.summary)
        .bind(&now)
        .bind(&now)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn get(&self, event_id: i64) -> Result<AnalysisReport, AppError> {
        let row = sqlx::query("SELECT * FROM analysis_report WHERE event_id = ?")
            .bind(event_id)
            .fetch_optional(&self.pool)
            .await?
            .ok_or(AppError::NotFound)?;

        let expected_json: String = row.try_get("expected_reaction_json")?;
        let observed_json: String = row.try_get("observed_reaction_json")?;
        let expected_reactions: Vec<ExpectedReaction> = serde_json::from_str(&expected_json)
            .map_err(|error| AppError::Internal(error.to_string()))?;
        let observed_value: serde_json::Value = serde_json::from_str(&observed_json)
            .map_err(|error| AppError::Internal(error.to_string()))?;
        let observed_reactions: Vec<MarketReaction> =
            serde_json::from_value(observed_value.get("reactions").cloned().unwrap_or_default())
                .map_err(|error| AppError::Internal(error.to_string()))?;
        let comparisons: Vec<ReactionComparison> = serde_json::from_value(
            observed_value
                .get("comparisons")
                .cloned()
                .unwrap_or_default(),
        )
        .map_err(|error| AppError::Internal(error.to_string()))?;
        let signal: String = row.try_get("macro_signal")?;

        Ok(AnalysisReport {
            id: row.try_get("id")?,
            event_id: row.try_get("event_id")?,
            raw_surprise: decimal_from_row(&row, "raw_surprise")?,
            macro_signal: MacroSignal::from_str(&signal).map_err(AppError::Internal)?,
            expected_reactions,
            observed_reactions,
            comparisons,
            summary: row.try_get("summary")?,
            historical: serde_json::from_value(
                observed_value
                    .get("historical")
                    .cloned()
                    .unwrap_or(serde_json::Value::Null),
            )
            .map_err(|error| AppError::Internal(error.to_string()))?,
            created_at: datetime_from_row(&row, "created_at")?,
            updated_at: datetime_from_row(&row, "updated_at")?,
        })
    }
}
