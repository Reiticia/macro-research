use chrono::{DateTime, Duration, Utc};
use sqlx::{Row, SqlitePool};
use std::collections::HashMap;

use crate::{
    error::AppError,
    market_selection::MarketSelection,
    model::{EconomicEvent, EventObservation, EventStatus, MarketSymbol},
};

use super::{datetime_from_row, decimal_from_row, event_from_row};

#[derive(Clone)]
pub struct EventRepository {
    pool: SqlitePool,
}

impl EventRepository {
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }

    /// Read-only handle for the few schedulers that keep their own bookkeeping tables.
    pub fn pool(&self) -> &SqlitePool {
        &self.pool
    }

    pub async fn save_events(&self, events: &[EconomicEvent]) -> Result<Vec<i64>, AppError> {
        self.save_events_inner(events, true).await
    }

    /// Saves provider values without creating a historical observation. Watchers use this path
    /// because they poll frequently and should not turn unchanged values into history rows.
    pub async fn save_events_without_observations(
        &self,
        events: &[EconomicEvent],
    ) -> Result<Vec<i64>, AppError> {
        self.save_events_inner(events, false).await
    }

    async fn save_events_inner(
        &self,
        events: &[EconomicEvent],
        record_observations: bool,
    ) -> Result<Vec<i64>, AppError> {
        let mut transaction = self.pool.begin().await?;
        let mut ids = Vec::with_capacity(events.len());

        for event in events {
            let event_query = sqlx::query(
                r#"INSERT INTO economic_event (
                    provider, provider_id, country, currency, category, event,
                    event_zh_cn, event_zh_tw, event_time, importance, actual, previous, consensus,
                    forecast, unit, status, time_exact, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?,
                    COALESCE(?, (SELECT zh_cn FROM event_name_translation WHERE source_text = ?)),
                    COALESCE(?, (SELECT zh_tw FROM event_name_translation WHERE source_text = ?)),
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(provider, provider_id) DO UPDATE SET
                    country = excluded.country,
                    currency = excluded.currency,
                    category = excluded.category,
                    event = excluded.event,
                    event_zh_cn = CASE
                        WHEN excluded.event = economic_event.event
                        THEN COALESCE(excluded.event_zh_cn, economic_event.event_zh_cn)
                        ELSE excluded.event_zh_cn
                    END,
                    event_zh_tw = CASE
                        WHEN excluded.event = economic_event.event
                        THEN COALESCE(excluded.event_zh_tw, economic_event.event_zh_tw)
                        ELSE excluded.event_zh_tw
                    END,
                    event_time = excluded.event_time,
                    importance = excluded.importance,
                    actual = excluded.actual,
                    previous = excluded.previous,
                    consensus = excluded.consensus,
                    forecast = excluded.forecast,
                    unit = excluded.unit,
                    time_exact = excluded.time_exact,
                    updated_at = excluded.updated_at
                WHERE economic_event.status != 'historical' OR excluded.status = 'historical'
                RETURNING id"#,
            )
            .bind(&event.provider)
            .bind(&event.provider_id)
            .bind(&event.country)
            .bind(&event.currency)
            .bind(&event.category)
            .bind(&event.event)
            .bind(&event.event_zh_cn)
            .bind(&event.event)
            .bind(&event.event_zh_tw)
            .bind(&event.event)
            .bind(event.event_time.to_rfc3339())
            .bind(i64::from(event.importance))
            .bind(event.actual.map(|value| value.to_string()))
            .bind(event.previous.map(|value| value.to_string()))
            .bind(event.consensus.map(|value| value.to_string()))
            .bind(event.forecast.map(|value| value.to_string()))
            .bind(&event.unit)
            .bind(event.status.as_str())
            .bind(event.time_exact)
            .bind(Utc::now().to_rfc3339());
            let (id, persisted) = match event_query.fetch_optional(&mut *transaction).await? {
                Some(row) => (row.try_get("id")?, true),
                // SQLite returns no row when the historical-preservation WHERE clause rejects
                // the update. The existing row still supplies the stable id, but the incoming
                // values must not become an observation because they were not persisted.
                None => {
                    let id = sqlx::query_scalar(
                        "SELECT id FROM economic_event WHERE provider = ? AND provider_id = ?",
                    )
                    .bind(&event.provider)
                    .bind(&event.provider_id)
                    .fetch_one(&mut *transaction)
                    .await?;
                    (id, false)
                }
            };

            if record_observations && persisted {
                let latest = sqlx::query(
                    "SELECT actual, previous, consensus, forecast FROM event_observation WHERE event_id = ? ORDER BY id DESC LIMIT 1",
                )
                .bind(id)
                .fetch_optional(&mut *transaction)
                .await?;
                let values = (
                    event.actual.map(|value| value.to_string()),
                    event.previous.map(|value| value.to_string()),
                    event.consensus.map(|value| value.to_string()),
                    event.forecast.map(|value| value.to_string()),
                );
                let changed = if let Some(row) = latest {
                    (
                        row.try_get::<Option<String>, _>("actual")?,
                        row.try_get::<Option<String>, _>("previous")?,
                        row.try_get::<Option<String>, _>("consensus")?,
                        row.try_get::<Option<String>, _>("forecast")?,
                    ) != values
                } else {
                    true
                };
                if changed {
                    sqlx::query(
                        r#"INSERT INTO event_observation
                            (event_id, observed_at, actual, previous, consensus, forecast)
                            VALUES (?, ?, ?, ?, ?, ?)"#,
                    )
                    .bind(id)
                    .bind(Utc::now().to_rfc3339())
                    .bind(&values.0)
                    .bind(&values.1)
                    .bind(&values.2)
                    .bind(&values.3)
                    .execute(&mut *transaction)
                    .await?;
                }
            }

            ids.push(id);
        }

        transaction.commit().await?;
        Ok(ids)
    }

    pub async fn cached_event_name_translations(
        &self,
        names: &[String],
    ) -> Result<HashMap<String, (String, String)>, AppError> {
        if names.is_empty() {
            return Ok(HashMap::new());
        }
        let placeholders = vec!["?"; names.len()].join(",");
        let sql = format!(
            "SELECT source_text, zh_cn, zh_tw FROM event_name_translation WHERE source_text IN ({placeholders})"
        );
        let mut query = sqlx::query(&sql);
        for name in names {
            query = query.bind(name);
        }
        let rows = query.fetch_all(&self.pool).await?;
        rows.into_iter()
            .map(|row| {
                Ok((
                    row.try_get("source_text")?,
                    (row.try_get("zh_cn")?, row.try_get("zh_tw")?),
                ))
            })
            .collect()
    }

    pub async fn save_event_name_translations(
        &self,
        translations: &[(String, String, String)],
    ) -> Result<(), AppError> {
        let mut transaction = self.pool.begin().await?;
        for (source, zh_cn, zh_tw) in translations {
            sqlx::query(
                r#"INSERT INTO event_name_translation (source_text, zh_cn, zh_tw, updated_at)
                   VALUES (?, ?, ?, ?)
                   ON CONFLICT(source_text) DO NOTHING"#,
            )
            .bind(source)
            .bind(zh_cn)
            .bind(zh_tw)
            .bind(Utc::now().to_rfc3339())
            .execute(&mut *transaction)
            .await?;
            sqlx::query(
                r#"UPDATE economic_event
                   SET event_zh_cn = (SELECT zh_cn FROM event_name_translation WHERE source_text = ?),
                       event_zh_tw = (SELECT zh_tw FROM event_name_translation WHERE source_text = ?),
                       updated_at = ?
                   WHERE event = ? AND (event_zh_cn IS NULL OR event_zh_tw IS NULL)"#,
            )
            .bind(source)
            .bind(source)
            .bind(Utc::now().to_rfc3339())
            .bind(source)
            .execute(&mut *transaction)
            .await?;
        }
        transaction.commit().await?;
        Ok(())
    }

    pub async fn untranslated_event_names(&self) -> Result<Vec<String>, AppError> {
        let rows = sqlx::query(
            r#"SELECT DISTINCT event FROM economic_event
               WHERE event_zh_cn IS NULL OR event_zh_tw IS NULL
               ORDER BY event"#,
        )
        .fetch_all(&self.pool)
        .await?;
        rows.into_iter()
            .map(|row| row.try_get("event").map_err(AppError::from))
            .collect()
    }

    /// Every stored event inside the range, used to hydrate weekly rows from earlier syncs.
    pub async fn events_in_range(
        &self,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let rows =
            sqlx::query("SELECT * FROM economic_event WHERE event_time >= ? AND event_time < ?")
                .bind(start.to_rfc3339())
                .bind(end.to_rfc3339())
                .fetch_all(&self.pool)
                .await?;
        rows.into_iter().map(event_from_row).collect()
    }

    pub async fn find_provider_event(
        &self,
        provider: &str,
        provider_id: &str,
    ) -> Result<Option<EconomicEvent>, AppError> {
        let row = sqlx::query("SELECT * FROM economic_event WHERE provider=? AND provider_id=?")
            .bind(provider)
            .bind(provider_id)
            .fetch_optional(&self.pool)
            .await?;
        row.map(event_from_row).transpose()
    }

    pub async fn market_selection(
        &self,
        event_id: i64,
    ) -> Result<Option<MarketSelection>, AppError> {
        let row = sqlx::query(
            "SELECT symbols_json, probabilities_json, source, model FROM event_market_selection WHERE event_id = ?",
        )
        .bind(event_id)
        .fetch_optional(&self.pool)
        .await?;
        row.map(|row| {
            let symbols_json: String = row.try_get("symbols_json")?;
            let probabilities_json: String = row.try_get("probabilities_json")?;
            let symbol_names: Vec<String> =
                serde_json::from_str(&symbols_json).map_err(|error| {
                    AppError::Internal(format!("invalid stored market symbols: {error}"))
                })?;
            let symbols = symbol_names
                .into_iter()
                .map(|value| value.parse::<MarketSymbol>().map_err(AppError::Internal))
                .collect::<Result<Vec<_>, _>>()?;
            let probabilities = serde_json::from_str(&probabilities_json).map_err(|error| {
                AppError::Internal(format!("invalid stored market probabilities: {error}"))
            })?;
            let source: String = row.try_get("source")?;
            let source = match source.as_str() {
                "jev" => "jev",
                "category_fallback" => "category_fallback",
                _ => "all_symbols_fallback",
            };
            Ok(MarketSelection {
                symbols,
                probabilities,
                source,
                model: row.try_get("model")?,
            })
        })
        .transpose()
    }

    pub async fn save_market_selection(
        &self,
        event_id: i64,
        selection: &MarketSelection,
    ) -> Result<MarketSelection, AppError> {
        let symbols = selection
            .symbols
            .iter()
            .map(|symbol| symbol.as_str())
            .collect::<Vec<_>>();
        sqlx::query(
            "INSERT OR IGNORE INTO event_market_selection (event_id, symbols_json, probabilities_json, source, model, selected_at) VALUES (?, ?, ?, ?, ?, ?)",
        )
        .bind(event_id)
        .bind(serde_json::to_string(&symbols).map_err(|error| AppError::Internal(error.to_string()))?)
        .bind(serde_json::to_string(&selection.probabilities).map_err(|error| AppError::Internal(error.to_string()))?)
        .bind(selection.source)
        .bind(&selection.model)
        .bind(Utc::now().to_rfc3339())
        .execute(&self.pool)
        .await?;
        self.market_selection(event_id)
            .await?
            .ok_or_else(|| AppError::Internal("market selection insert was not visible".into()))
    }

    pub async fn market_selection_missing_active(&self) -> Result<Vec<EconomicEvent>, AppError> {
        let rows = sqlx::query(
            "SELECT e.* FROM economic_event e LEFT JOIN event_market_selection s ON s.event_id = e.id WHERE e.status IN ('watching','released','collecting_market_data','analyzing') AND s.event_id IS NULL ORDER BY e.event_time",
        )
        .fetch_all(&self.pool)
        .await?;
        rows.into_iter().map(event_from_row).collect()
    }

    pub async fn get(&self, id: i64) -> Result<EconomicEvent, AppError> {
        let row = sqlx::query("SELECT * FROM economic_event WHERE id = ?")
            .bind(id)
            .fetch_optional(&self.pool)
            .await?
            .ok_or(AppError::NotFound)?;
        event_from_row(row)
    }

    pub async fn observations(&self, event_id: i64) -> Result<Vec<EventObservation>, AppError> {
        let rows = sqlx::query(
            "SELECT * FROM event_observation WHERE event_id = ? ORDER BY observed_at ASC",
        )
        .bind(event_id)
        .fetch_all(&self.pool)
        .await?;

        rows.into_iter()
            .map(|row| {
                Ok(EventObservation {
                    id: row.try_get("id")?,
                    event_id: row.try_get("event_id")?,
                    observed_at: datetime_from_row(&row, "observed_at")?,
                    actual: decimal_from_row(&row, "actual")?,
                    previous: decimal_from_row(&row, "previous")?,
                    consensus: decimal_from_row(&row, "consensus")?,
                    forecast: decimal_from_row(&row, "forecast")?,
                })
            })
            .collect()
    }

    pub async fn calendar(
        &self,
        from: DateTime<Utc>,
        to: DateTime<Utc>,
        country: Option<&str>,
        minimum_importance: Option<u8>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let rows = sqlx::query(
            r#"SELECT * FROM economic_event
               WHERE event_time >= ? AND event_time <= ?
                 AND (? IS NULL OR lower(country) = lower(?))
                 AND importance >= ?
               ORDER BY event_time ASC, importance DESC"#,
        )
        .bind(from.to_rfc3339())
        .bind(to.to_rfc3339())
        .bind(country)
        .bind(country)
        .bind(i64::from(minimum_importance.unwrap_or(0)))
        .fetch_all(&self.pool)
        .await?;
        rows.into_iter().map(event_from_row).collect()
    }

    pub async fn upcoming(
        &self,
        now: DateTime<Utc>,
        days: i64,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        self.calendar(now, now + Duration::days(days), None, None)
            .await
    }

    pub async fn history(
        &self,
        country: Option<&str>,
        category: Option<&str>,
        limit: u32,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        self.history_page(country, category, limit, 0, None, None)
            .await
    }

    pub async fn history_page(
        &self,
        country: Option<&str>,
        category: Option<&str>,
        limit: u32,
        offset: u32,
        from: Option<DateTime<Utc>>,
        to: Option<DateTime<Utc>>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let countries: Vec<String> = country
            .map(|value| vec![value.to_owned()])
            .unwrap_or_default();
        self.history_page_multi(countries, category, limit, offset, from, to)
            .await
    }

    /// History page filtered by any number of countries, matching the client's country set.
    pub async fn history_page_multi(
        &self,
        countries: Vec<String>,
        category: Option<&str>,
        limit: u32,
        offset: u32,
        from: Option<DateTime<Utc>>,
        to: Option<DateTime<Utc>>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let lower: Vec<String> = countries
            .into_iter()
            .map(|value| value.trim().to_lowercase())
            .filter(|value| !value.is_empty())
            .collect();
        let placeholders = vec!["?"; lower.len()].join(",");
        let country_clause = if lower.is_empty() {
            String::new()
        } else {
            format!("AND lower(country) IN ({placeholders})")
        };
        let sql = format!(
            r#"SELECT * FROM economic_event
               WHERE event_time < ?
                 AND (? IS NULL OR lower(category) LIKE '%' || lower(?) || '%')
                 AND (? IS NULL OR event_time >= ?)
                 AND (? IS NULL OR event_time < ?)
                 {country_clause}
               ORDER BY event_time DESC, id DESC LIMIT ? OFFSET ?"#
        );
        let mut query = sqlx::query(&sql)
            .bind(Utc::now().to_rfc3339())
            .bind(category)
            .bind(category)
            .bind(from.map(|v| v.to_rfc3339()))
            .bind(from.map(|v| v.to_rfc3339()))
            .bind(to.map(|v| v.to_rfc3339()))
            .bind(to.map(|v| v.to_rfc3339()));
        for country in &lower {
            query = query.bind(country);
        }
        let rows = query
            .bind(i64::from(limit.clamp(1, 500)))
            .bind(i64::from(offset))
            .fetch_all(&self.pool)
            .await?;
        rows.into_iter().map(event_from_row).collect()
    }

    /// Events whose release time has passed without any published actual value.
    pub async fn missing_release_values(
        &self,
        older_than: DateTime<Utc>,
        now: DateTime<Utc>,
        limit: i64,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let rows = sqlx::query(
            r#"SELECT * FROM economic_event
               WHERE actual IS NULL
                 AND event_time <= ?
                 AND event_time >= ?
                 AND status NOT IN ('historical', 'timeout')
               ORDER BY event_time ASC LIMIT ?"#,
        )
        .bind(older_than.to_rfc3339())
        .bind((now - Duration::days(7)).to_rfc3339())
        .bind(limit)
        .fetch_all(&self.pool)
        .await?;
        rows.into_iter().map(event_from_row).collect()
    }

    pub async fn scheduled_to_watch(
        &self,
        now: DateTime<Utc>,
        watch_before_minutes: i64,
        release_timeout_minutes: i64,
        minimum_importance: u8,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let rows = sqlx::query(
            r#"SELECT * FROM economic_event
               WHERE status = 'scheduled' AND importance >= ?
                 AND event_time >= ? AND event_time <= ?
               ORDER BY event_time ASC"#,
        )
        .bind(i64::from(minimum_importance))
        .bind((now - Duration::minutes(release_timeout_minutes)).to_rfc3339())
        .bind((now + Duration::minutes(watch_before_minutes)).to_rfc3339())
        .fetch_all(&self.pool)
        .await?;
        rows.into_iter().map(event_from_row).collect()
    }

    pub async fn watching(&self) -> Result<Vec<EconomicEvent>, AppError> {
        self.by_statuses(&[EventStatus::Watching]).await
    }

    pub async fn market_active(&self) -> Result<Vec<EconomicEvent>, AppError> {
        self.by_statuses(&[
            EventStatus::Watching,
            EventStatus::Released,
            EventStatus::CollectingMarketData,
            EventStatus::Analyzing,
        ])
        .await
    }

    async fn by_statuses(&self, statuses: &[EventStatus]) -> Result<Vec<EconomicEvent>, AppError> {
        let names: Vec<&str> = statuses.iter().map(|status| status.as_str()).collect();
        let placeholders = vec!["?"; names.len()].join(",");
        let sql = format!(
            "SELECT * FROM economic_event WHERE status IN ({placeholders}) ORDER BY event_time ASC"
        );
        let mut query = sqlx::query(&sql);
        for name in names {
            query = query.bind(name);
        }
        let rows = query.fetch_all(&self.pool).await?;
        rows.into_iter().map(event_from_row).collect()
    }

    pub async fn set_status(&self, id: i64, status: EventStatus) -> Result<(), AppError> {
        let result =
            sqlx::query("UPDATE economic_event SET status = ?, updated_at = ? WHERE id = ?")
                .bind(status.as_str())
                .bind(Utc::now().to_rfc3339())
                .bind(id)
                .execute(&self.pool)
                .await?;
        if result.rows_affected() == 0 {
            return Err(AppError::NotFound);
        }
        Ok(())
    }

    /// All events in the half-open UTC window `[start, end)`, regardless of status.
    pub async fn in_range(
        &self,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let rows = sqlx::query(
            "SELECT * FROM economic_event WHERE event_time >= ? AND event_time < ? ORDER BY event_time ASC",
        )
        .bind(start.to_rfc3339())
        .bind(end.to_rfc3339())
        .fetch_all(&self.pool)
        .await?;
        rows.into_iter().map(event_from_row).collect()
    }

    /// Reclassifies past events that hold an actual value but can never advance again: the
    /// watcher only reaches `release_timeout_minutes` back, so a release first seen through a
    /// later calendar sync (e.g. after downtime) would otherwise stay `scheduled` forever.
    /// Stored as historical, which is what client-side status recomputation already displays.
    pub async fn reconcile_missed_releases(&self, cutoff: DateTime<Utc>) -> Result<u64, AppError> {
        let result = sqlx::query(
            r#"UPDATE economic_event
                  SET status = 'historical', updated_at = ?
                WHERE status IN ('scheduled', 'timeout', 'data_unavailable')
                  AND actual IS NOT NULL
                  AND event_time < ?"#,
        )
        .bind(Utc::now().to_rfc3339())
        .bind(cutoff.to_rfc3339())
        .execute(&self.pool)
        .await?;
        Ok(result.rows_affected())
    }
}
