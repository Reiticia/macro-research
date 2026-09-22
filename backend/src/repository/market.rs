use std::str::FromStr;

use chrono::{Timelike, Utc};
use sqlx::{Row, SqlitePool};

use crate::{
    error::AppError,
    model::{Candle, MarketReaction, MarketSnapshot, MarketSymbol, Quote, ReactionUnit},
};

use super::datetime_from_row;

#[derive(Clone)]
pub struct MarketRepository {
    pool: SqlitePool,
}

impl MarketRepository {
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    pub async fn save_quote(&self, event_id: i64, quote: &Quote) -> Result<(), AppError> {
        // Event timelines use one-minute OHLC bars. Keep collecting point quotes at the
        // scheduler's higher frequency so the live cache remains independent, but merge all
        // quotes from the same UTC minute into one persisted bar.
        let timestamp = quote
            .timestamp
            .with_second(0)
            .and_then(|value| value.with_nanosecond(0))
            .ok_or_else(|| AppError::Internal("invalid quote timestamp".into()))?
            .to_rfc3339();
        sqlx::query(
            r#"INSERT INTO market_snapshot (
                    event_id, symbol, timestamp, price, open, high, low, close, source
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'quote')
               ON CONFLICT(event_id, symbol, timestamp) DO UPDATE SET
                 price = excluded.price,
                 open = COALESCE(market_snapshot.open, excluded.open),
                 high = MAX(COALESCE(market_snapshot.high, excluded.high), excluded.high),
                 low = MIN(COALESCE(market_snapshot.low, excluded.low), excluded.low),
                 close = excluded.close,
                 source = 'quote'"#,
        )
        .bind(event_id)
        .bind(quote.symbol.as_str())
        .bind(timestamp)
        .bind(quote.price)
        .bind(quote.price)
        .bind(quote.price)
        .bind(quote.price)
        .bind(quote.price)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    /// Persists candle bars as per-minute snapshots for one event, giving historical events
    /// the same reaction-timeline samples that live collection produces in real time.
    pub async fn save_candle_snapshots(
        &self,
        event_id: i64,
        candles: &[Candle],
    ) -> Result<usize, AppError> {
        let mut tx = self.pool.begin().await?;
        for c in candles {
            sqlx::query(
                r#"INSERT INTO market_snapshot (event_id, symbol, timestamp, price, open, high, low, close, volume, source)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'candle')
                   ON CONFLICT(event_id, symbol, timestamp) DO UPDATE SET
                     price = excluded.price, open = excluded.open, high = excluded.high,
                     low = excluded.low, close = excluded.close, volume = excluded.volume,
                     source = 'candle'
                   WHERE market_snapshot.source = 'candle'"#,
            )
            .bind(event_id)
            .bind(c.symbol.as_str())
            .bind(c.timestamp.to_rfc3339())
            .bind(c.close)
            .bind(c.open)
            .bind(c.high)
            .bind(c.low)
            .bind(c.close)
            .bind(c.volume)
            .execute(&mut *tx)
            .await?;
        }
        tx.commit().await?;
        Ok(candles.len())
    }

    pub async fn snapshots(&self, event_id: i64) -> Result<Vec<MarketSnapshot>, AppError> {
        let rows = sqlx::query(
            "SELECT * FROM market_snapshot WHERE event_id = ? ORDER BY timestamp ASC, symbol ASC",
        )
        .bind(event_id)
        .fetch_all(&self.pool)
        .await?;

        rows.into_iter()
            .map(|row| {
                let symbol: String = row.try_get("symbol")?;
                Ok(MarketSnapshot {
                    id: row.try_get("id")?,
                    event_id: row.try_get("event_id")?,
                    symbol: MarketSymbol::from_str(&symbol).map_err(AppError::Internal)?,
                    timestamp: datetime_from_row(&row, "timestamp")?,
                    price: row.try_get("price")?,
                    open: row.try_get("open")?,
                    high: row.try_get("high")?,
                    low: row.try_get("low")?,
                    close: row.try_get("close")?,
                    volume: row.try_get("volume")?,
                })
            })
            .collect()
    }

    pub async fn upsert_reaction(&self, reaction: &MarketReaction) -> Result<(), AppError> {
        sqlx::query(
            r#"INSERT INTO market_reaction (
                event_id, symbol, baseline_price, reaction_unit, change_1m, change_5m,
                change_15m, change_30m, change_60m, calculated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(event_id, symbol) DO UPDATE SET
                baseline_price = excluded.baseline_price,
                reaction_unit = excluded.reaction_unit,
                change_1m = excluded.change_1m,
                change_5m = excluded.change_5m,
                change_15m = excluded.change_15m,
                change_30m = excluded.change_30m,
                change_60m = excluded.change_60m,
                calculated_at = excluded.calculated_at"#,
        )
        .bind(reaction.event_id)
        .bind(reaction.symbol.as_str())
        .bind(reaction.baseline_price)
        .bind(match reaction.reaction_unit {
            ReactionUnit::Percent => "percent",
            ReactionUnit::BasisPoints => "basis_points",
        })
        .bind(reaction.change_1m)
        .bind(reaction.change_5m)
        .bind(reaction.change_15m)
        .bind(reaction.change_30m)
        .bind(reaction.change_60m)
        .bind(Utc::now().to_rfc3339())
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    /// A retry with reduced coverage must not leave stale reaction rows behind.
    pub async fn replace_reactions(
        &self,
        event_id: i64,
        reactions: &[MarketReaction],
    ) -> Result<(), AppError> {
        let mut tx = self.pool.begin().await?;
        sqlx::query("DELETE FROM market_reaction WHERE event_id=?")
            .bind(event_id)
            .execute(&mut *tx)
            .await?;
        for r in reactions {
            sqlx::query("INSERT INTO market_reaction(event_id,symbol,baseline_price,reaction_unit,change_1m,change_5m,change_15m,change_30m,change_60m,calculated_at) VALUES (?,?,?,?,?,?,?,?,?,?)")
                .bind(event_id).bind(r.symbol.as_str()).bind(r.baseline_price)
                .bind(if r.reaction_unit == ReactionUnit::BasisPoints { "basis_points" } else { "percent" })
                .bind(r.change_1m).bind(r.change_5m).bind(r.change_15m).bind(r.change_30m).bind(r.change_60m)
                .bind(Utc::now().to_rfc3339()).execute(&mut *tx).await?;
        }
        tx.commit().await?;
        Ok(())
    }

    pub async fn reactions(&self, event_id: i64) -> Result<Vec<MarketReaction>, AppError> {
        let rows =
            sqlx::query("SELECT * FROM market_reaction WHERE event_id = ? ORDER BY symbol ASC")
                .bind(event_id)
                .fetch_all(&self.pool)
                .await?;
        rows.into_iter()
            .map(|row| {
                let symbol: String = row.try_get("symbol")?;
                Ok(MarketReaction {
                    event_id: row.try_get("event_id")?,
                    symbol: MarketSymbol::from_str(&symbol).map_err(AppError::Internal)?,
                    baseline_price: row.try_get("baseline_price")?,
                    reaction_unit: match row.try_get::<String, _>("reaction_unit")?.as_str() {
                        "basis_points" => ReactionUnit::BasisPoints,
                        _ => ReactionUnit::Percent,
                    },
                    change_1m: row.try_get("change_1m")?,
                    change_5m: row.try_get("change_5m")?,
                    change_15m: row.try_get("change_15m")?,
                    change_30m: row.try_get("change_30m")?,
                    change_60m: row.try_get("change_60m")?,
                })
            })
            .collect()
    }
}
