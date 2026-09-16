use chrono::{DateTime, Duration, NaiveDate, Utc};
use serde::Serialize;
use sqlx::{Row, SqlitePool};

use crate::{
    error::AppError,
    model::{Candle, Interval, MarketSymbol},
};

#[derive(Clone)]
pub struct BackfillRepository {
    pool: SqlitePool,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackfillSummary {
    pub id: i64,
    pub from: String,
    pub to: String,
    pub status: String,
    pub days_complete: i64,
    pub days_partial: i64,
    pub days_failed: i64,
    pub events: i64,
    pub analyses: i64,
    pub updated_at: String,
    pub error: Option<String>,
    pub day_errors: Vec<String>,
}

impl BackfillRepository {
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    pub async fn begin(&self, from: NaiveDate, to: NaiveDate) -> Result<i64, AppError> {
        let now = Utc::now();
        // A short lease prevents two CLI processes from writing the same run.
        // Interrupted processes can be resumed after 15 minutes; Ctrl-C releases immediately.
        let row = sqlx::query(
            "INSERT INTO backfill_run(from_date,to_date,status,started_at,updated_at,error)
             VALUES (?,?,'running',?,?,NULL)
             ON CONFLICT(from_date,to_date) DO UPDATE SET status='running',updated_at=excluded.updated_at,error=NULL
             WHERE backfill_run.status != 'running' OR backfill_run.updated_at < ? RETURNING id")
            .bind(from.to_string()).bind(to.to_string()).bind(now.to_rfc3339()).bind(now.to_rfc3339())
            .bind((now - Duration::minutes(15)).to_rfc3339()).fetch_optional(&self.pool).await?;
        row.map(|r| r.try_get("id")).transpose()?.ok_or_else(|| {
            AppError::InvalidRequest("this date range already has a running backfill".into())
        })
    }

    pub async fn day_complete(&self, run: i64, date: NaiveDate) -> Result<bool, AppError> {
        let status: Option<String> =
            sqlx::query_scalar("SELECT status FROM backfill_day WHERE run_id=? AND date=?")
                .bind(run)
                .bind(date.to_string())
                .fetch_optional(&self.pool)
                .await?;
        Ok(status.as_deref() == Some("complete"))
    }

    pub async fn save_day(
        &self,
        run: i64,
        date: NaiveDate,
        status: &str,
        events: usize,
        analyses: usize,
        error: Option<&str>,
    ) -> Result<(), AppError> {
        let mut tx = self.pool.begin().await?;
        sqlx::query("INSERT INTO backfill_day(run_id,date,status,events,analyses,error,updated_at) VALUES (?,?,?,?,?,?,?)
            ON CONFLICT(run_id,date) DO UPDATE SET status=excluded.status,events=excluded.events,analyses=excluded.analyses,error=excluded.error,updated_at=excluded.updated_at")
            .bind(run).bind(date.to_string()).bind(status).bind(events as i64).bind(analyses as i64)
            .bind(error).bind(Utc::now().to_rfc3339()).execute(&mut *tx).await?;
        sqlx::query("UPDATE backfill_run SET updated_at=? WHERE id=?")
            .bind(Utc::now().to_rfc3339())
            .bind(run)
            .execute(&mut *tx)
            .await?;
        tx.commit().await?;
        Ok(())
    }

    pub async fn finish(
        &self,
        run: i64,
        status: &str,
        error: Option<&str>,
    ) -> Result<(), AppError> {
        sqlx::query("UPDATE backfill_run SET status=?, error=?, updated_at=? WHERE id=?")
            .bind(status)
            .bind(error)
            .bind(Utc::now().to_rfc3339())
            .bind(run)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn latest(&self) -> Result<Option<BackfillSummary>, AppError> {
        let id: Option<i64> =
            sqlx::query_scalar("SELECT id FROM backfill_run ORDER BY updated_at DESC LIMIT 1")
                .fetch_optional(&self.pool)
                .await?;
        match id {
            Some(id) => self.summary(id).await.map(Some),
            None => Ok(None),
        }
    }

    pub async fn summary(&self, id: i64) -> Result<BackfillSummary, AppError> {
        let row = sqlx::query("SELECT r.*, COALESCE(SUM(d.status='complete'),0) AS complete,
            COALESCE(SUM(d.status='partial'),0) AS partial, COALESCE(SUM(d.status='failed'),0) AS failed,
            COALESCE(SUM(d.events),0) AS events, COALESCE(SUM(d.analyses),0) AS analyses
            FROM backfill_run r LEFT JOIN backfill_day d ON r.id=d.run_id WHERE r.id=? GROUP BY r.id")
            .bind(id).fetch_optional(&self.pool).await?.ok_or(AppError::NotFound)?;
        let day_errors = sqlx::query_scalar("SELECT date || ': ' || error FROM backfill_day WHERE run_id=? AND error IS NOT NULL ORDER BY date LIMIT 20")
            .bind(id).fetch_all(&self.pool).await?;
        Ok(BackfillSummary {
            id,
            day_errors,
            from: row.try_get("from_date")?,
            to: row.try_get("to_date")?,
            status: row.try_get("status")?,
            days_complete: row.try_get("complete")?,
            days_partial: row.try_get("partial")?,
            days_failed: row.try_get("failed")?,
            events: row.try_get("events")?,
            analyses: row.try_get("analyses")?,
            updated_at: row.try_get("updated_at")?,
            error: row.try_get("error")?,
        })
    }

    pub async fn cached_candles(
        &self,
        source: &str,
        symbol: MarketSymbol,
        interval: Interval,
        from: DateTime<Utc>,
        to: DateTime<Utc>,
    ) -> Result<Option<Vec<Candle>>, AppError> {
        let exists: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM historical_fetch WHERE source=? AND symbol=? AND interval_seconds=? AND from_time=? AND to_time=?")
            .bind(source).bind(symbol.as_str()).bind(interval.seconds()).bind(from.to_rfc3339()).bind(to.to_rfc3339())
            .fetch_one(&self.pool).await?;
        if exists == 0 {
            return Ok(None);
        }
        let rows = sqlx::query("SELECT * FROM historical_candle WHERE source=? AND symbol=? AND interval_seconds=? AND timestamp>=? AND timestamp<? ORDER BY timestamp")
            .bind(source).bind(symbol.as_str()).bind(interval.seconds()).bind(from.to_rfc3339()).bind(to.to_rfc3339())
            .fetch_all(&self.pool).await?;
        let candles = rows
            .into_iter()
            .map(|r| {
                Ok(Candle {
                    symbol,
                    timestamp: crate::repository::datetime_from_row(&r, "timestamp")?,
                    open: r.try_get("open")?,
                    high: r.try_get("high")?,
                    low: r.try_get("low")?,
                    close: r.try_get("close")?,
                    volume: r.try_get("volume")?,
                })
            })
            .collect::<Result<Vec<_>, AppError>>()?;
        Ok(Some(candles))
    }

    pub async fn cache_candles(
        &self,
        source: &str,
        symbol: MarketSymbol,
        interval: Interval,
        from: DateTime<Utc>,
        to: DateTime<Utc>,
        candles: &[Candle],
    ) -> Result<(), AppError> {
        let mut tx = self.pool.begin().await?;
        for c in candles {
            sqlx::query("INSERT INTO historical_candle(source,symbol,interval_seconds,timestamp,open,high,low,close,volume) VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT(source,symbol,interval_seconds,timestamp) DO UPDATE SET open=excluded.open,high=excluded.high,low=excluded.low,close=excluded.close,volume=excluded.volume")
                .bind(source).bind(symbol.as_str()).bind(interval.seconds()).bind(c.timestamp.to_rfc3339())
                .bind(c.open).bind(c.high).bind(c.low).bind(c.close).bind(c.volume).execute(&mut *tx).await?;
        }
        sqlx::query("INSERT OR IGNORE INTO historical_fetch(source,symbol,interval_seconds,from_time,to_time) VALUES (?,?,?,?,?)")
            .bind(source).bind(symbol.as_str()).bind(interval.seconds()).bind(from.to_rfc3339()).bind(to.to_rfc3339())
            .execute(&mut *tx).await?;
        tx.commit().await?;
        Ok(())
    }
}
