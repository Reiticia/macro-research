use chrono::{DateTime, Duration, Utc};
use sqlx::{Row, SqlitePool};
use std::collections::{HashMap, HashSet};

use crate::{
    calendar::{
        parse::{
            conflicting_duplicate_actual, normalized_event_name, occurrence_key, provider_priority,
            release_status, same_named_occurrence, same_occurrence,
        },
        release_identity::{
            inherit_actual_provenance, release_value_for, validate_release_identity,
        },
    },
    error::AppError,
    event_description::EventDescription,
    market_selection::MarketSelection,
    model::{EconomicEvent, EventObservation, EventStatus, MarketSymbol},
};

use super::event_identity::{find_identity, remember_identity};
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

    /// Returns IDs for accepted rows (duplicates may repeat an ID). Ambiguous new identities
    /// are skipped without a placeholder; unrelated rows in the batch still persist.
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
        // Serialize identity lookup + upsert, including concurrent startup/periodic syncs.
        let mut transaction = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        let mut ids = Vec::with_capacity(events.len());
        let mut prepared = events.to_vec();
        for event in &mut prepared {
            // Read cached translations only; duplicate detection must never call a model.
            if event.event_zh_cn.is_none() || event.event_zh_tw.is_none() {
                if let Some(row) = sqlx::query(
                    "SELECT zh_cn,zh_tw FROM event_name_translation WHERE source_text=?",
                )
                .bind(&event.event)
                .fetch_optional(&mut *transaction)
                .await?
                {
                    if event.event_zh_cn.is_none() {
                        event.event_zh_cn = Some(row.try_get("zh_cn")?);
                    }
                    if event.event_zh_tw.is_none() {
                        event.event_zh_tw = Some(row.try_get("zh_tw")?);
                    }
                }
            }
        }

        // Only use the existing verified cross-source aliases when the batch has one
        // distinct normalized name for that occurrence. Exact user-name duplicates in a
        // single batch are handled sequentially against this transaction's own writes.
        let mut occurrences: HashMap<_, HashSet<String>> = HashMap::new();
        for event in &prepared {
            occurrences
                .entry(occurrence_key(
                    &event.country,
                    event.event_time,
                    &event.event,
                ))
                .or_default()
                .insert(normalized_event_name(&event.event));
        }
        for incoming in &prepared {
            let mut event = incoming.clone();
            let observed = event.clone();
            let conflicting = conflicting_duplicate_actual(&event, &prepared);
            if conflicting {
                tracing::warn!(provider=%event.provider,"conflicting duplicate Actual values; retaining stored values");
                event.actual = None;
                event.previous = None;
                event.consensus = None;
                event.forecast = None;
                event.status = release_status(None, event.event_time, Utc::now());
            }
            let mut existing =
                find_identity(&mut transaction, &event.provider, &event.provider_id).await?;
            if existing.is_none() && event.time_exact && provider_priority(&event.provider) > 0 {
                let key = occurrence_key(&event.country, event.event_time, &event.event);
                let allow_alias = !key.2.is_empty()
                    && occurrences.get(&key).is_some_and(|names| names.len() == 1);
                let rows = sqlx::query(
                    "SELECT * FROM economic_event WHERE country=? AND event_time IN (?,?)",
                )
                .bind(&event.country)
                .bind(event.event_time.to_rfc3339())
                .bind(
                    event
                        .event_time
                        .to_rfc3339_opts(chrono::SecondsFormat::AutoSi, true),
                )
                .fetch_all(&mut *transaction)
                .await?;
                let candidates: Vec<_> = rows
                    .into_iter()
                    .map(event_from_row)
                    .collect::<Result<Vec<_>, _>>()?
                    .into_iter()
                    .filter(|old| {
                        old.time_exact
                            && provider_priority(&old.provider) > 0
                            && (same_named_occurrence(old, &event)
                                || allow_alias
                                    && old.provider != event.provider
                                    && same_occurrence(old, &event))
                    })
                    .collect();
                match candidates.as_slice() {
                    [old] => existing = Some(old.clone()),
                    [] => {}
                    _ => {
                        // Legacy duplicates require an explicit repair; neither choose an
                        // arbitrary owner nor insert a third row while unrelated events sync.
                        tracing::warn!(provider=%event.provider,matches=candidates.len(),
                            "ambiguous calendar identity; skipping new upstream ID");
                        continue;
                    }
                }
            }
            if let Some(old) = existing {
                let same_identity =
                    old.provider == event.provider && old.provider_id == event.provider_id;
                let mut peers = Vec::new();
                if !same_identity {
                    peers = sqlx::query("SELECT * FROM economic_event WHERE country=? AND julianday(event_time)=julianday(?)")
                        .bind(&event.country).bind(event.event_time.to_rfc3339())
                        .fetch_all(&mut *transaction).await?.into_iter()
                        .map(event_from_row).collect::<Result<Vec<_>, _>>()?;
                    // Include later rows in this batch, not only rows already persisted.
                    peers.extend_from_slice(&prepared);
                    if let Err(reason) = validate_release_identity(&old, &observed, &peers) {
                        tracing::warn!(
                            event_id = old.id,
                            ?reason,
                            "calendar identity update skipped"
                        );
                        ids.push(old.id);
                        continue;
                    }
                    // Do not relabel a valued fallback row as primary if its value cannot
                    // be expressed in the primary comparison basis. Leave it intact.
                    if provider_priority(&event.provider) >= provider_priority(&old.provider)
                        && event.actual.is_none()
                        && old.actual.is_some()
                        && release_value_for(&event, &old, &peers).is_err()
                    {
                        tracing::warn!(
                            event_id = old.id,
                            "calendar promotion skipped: incompatible units"
                        );
                        ids.push(old.id);
                        continue;
                    }
                }
                if conflicting {
                    event.actual = old.actual;
                    event.previous = old.previous;
                    event.consensus = old.consensus;
                    event.forecast = old.forecast;
                    event.unit = old.unit.clone();
                    event.release_metadata.actual_source =
                        old.release_metadata.actual_source.clone();
                    event.release_metadata.actual_source_id =
                        old.release_metadata.actual_source_id.clone();
                    event.release_metadata.actual_observed_at =
                        old.release_metadata.actual_observed_at;
                    event.status = if old.status == EventStatus::Historical {
                        observed.status
                    } else {
                        old.status
                    };
                }
                // Keep the first receipt time for an unchanged value from the same source.
                if observed.actual.is_some()
                    && observed.actual == old.actual
                    && old.release_metadata.actual_source.as_deref()
                        == Some(observed.provider.as_str())
                    && old.release_metadata.actual_source_id.as_deref()
                        == Some(observed.provider_id.as_str())
                {
                    event.release_metadata.actual_source =
                        old.release_metadata.actual_source.clone();
                    event.release_metadata.actual_source_id =
                        old.release_metadata.actual_source_id.clone();
                    event.release_metadata.actual_observed_at =
                        old.release_metadata.actual_observed_at;
                }
                remember_identity(&mut transaction, &old, old.id).await?;
                let lower_source =
                    provider_priority(&event.provider) < provider_priority(&old.provider);
                if lower_source {
                    if !same_occurrence(&old, &event) {
                        ids.push(old.id);
                        continue;
                    }
                    remember_identity(&mut transaction, &observed, old.id).await?;
                    if old.actual.is_some()
                        || event.actual.is_none()
                        || old.status == EventStatus::Historical
                            && observed.status != EventStatus::Historical
                    {
                        ids.push(old.id);
                        continue;
                    }
                    // Keep the primary comparison basis: only convert/fill Actual.
                    let actual = match release_value_for(&old, &event, &peers) {
                        Ok(actual) => actual,
                        Err(reason) => {
                            tracing::warn!(event_id = old.id, ?reason, "release fill skipped");
                            ids.push(old.id);
                            continue;
                        }
                    };
                    event = EconomicEvent {
                        actual: Some(actual),
                        ..old.clone()
                    };
                    inherit_actual_provenance(&mut event, &observed);
                } else {
                    if !same_identity
                        && old.status == EventStatus::Historical
                        && observed.status != EventStatus::Historical
                    {
                        remember_identity(&mut transaction, &observed, old.id).await?;
                        ids.push(old.id);
                        continue;
                    }
                    if !same_identity {
                        sqlx::query(
                            "UPDATE economic_event SET provider=?,provider_id=? WHERE id=?",
                        )
                        .bind(&event.provider)
                        .bind(&event.provider_id)
                        .bind(old.id)
                        .execute(&mut *transaction)
                        .await?;
                    }
                    if !same_identity
                        || same_named_occurrence(&old, &event)
                        || old.event == event.event
                    {
                        event.event_zh_cn = old.event_zh_cn.clone().or(event.event_zh_cn);
                        event.event_zh_tw = old.event_zh_tw.clone().or(event.event_zh_tw);
                    }
                    if same_identity
                        && old.event_time == event.event_time
                        && old.country == event.country
                    {
                        event.release_metadata.period = event
                            .release_metadata
                            .period
                            .clone()
                            .or(old.release_metadata.period.clone());
                        event.release_metadata.series_id = event
                            .release_metadata
                            .series_id
                            .clone()
                            .or(old.release_metadata.series_id.clone());
                    }
                    if crate::calendar::release_identity::verified_relation(&old, &event)
                        != Some(false)
                        && (!same_identity
                            || old.time_exact
                                && event.time_exact
                                && old.country == event.country
                                && old.event_time == event.event_time)
                        && event.actual.is_none()
                        && old.actual.is_some()
                    {
                        if !same_identity {
                            // Promoting a fallback row to the primary owner must adopt the
                            // primary forecast/previous, not keep the fallback's comparison basis.
                            event.actual = release_value_for(&event, &old, &peers).ok();
                        } else {
                            // Only the exact same source occurrence may retain its old basis
                            // after an empty response. Cross-source conversion already passed.
                            event.actual = old.actual;
                            event.previous = old.previous;
                            event.consensus = old.consensus;
                            event.forecast = old.forecast;
                            event.unit = old.unit.clone();
                        }
                        event.status = old.status;
                        event.release_metadata.actual_source =
                            old.release_metadata.actual_source.clone();
                        event.release_metadata.actual_source_id =
                            old.release_metadata.actual_source_id.clone();
                        event.release_metadata.actual_observed_at =
                            old.release_metadata.actual_observed_at;
                    }
                }
            }
            if !conflicting
                && observed.actual.is_some()
                && event.release_metadata.actual_source.is_none()
            {
                event.release_metadata.actual_source = Some(observed.provider.clone());
                event.release_metadata.actual_source_id = Some(observed.provider_id.clone());
                event.release_metadata.actual_observed_at = Some(Utc::now());
            }
            let metadata = serde_json::to_string(&event.release_metadata)
                .map_err(|_| AppError::Internal("cannot encode release metadata".into()))?;
            let event_query = sqlx::query(
                r#"INSERT INTO economic_event (
                    provider, provider_id, country, currency, category, event,
                    event_zh_cn, event_zh_tw, event_time, importance, actual, previous, consensus,
                    forecast, unit, status, time_exact, updated_at, release_metadata
                ) VALUES (?, ?, ?, ?, ?, ?,
                    COALESCE(?, (SELECT zh_cn FROM event_name_translation WHERE source_text = ?)),
                    COALESCE(?, (SELECT zh_tw FROM event_name_translation WHERE source_text = ?)),
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    updated_at = excluded.updated_at,
                    release_metadata = excluded.release_metadata
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
            .bind(Utc::now().to_rfc3339())
            .bind(metadata);
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
                    // Refresh source metadata on an archived row only if every persisted
                    // numeric field and occurrence identity is unchanged. Never replace historical
                    // values/observations with a newer live-calendar revision merely to fix units.
                    if event.unit.is_some() {
                        sqlx::query(
                            "UPDATE economic_event SET unit = ?, updated_at = ? \
                             WHERE id = ? AND country = ? AND event = ? AND event_time = ? \
                             AND actual IS ? AND previous IS ? AND consensus IS ? AND forecast IS ?",
                        )
                        .bind(&event.unit)
                        .bind(Utc::now().to_rfc3339())
                        .bind(id)
                        .bind(&event.country)
                        .bind(&event.event)
                        .bind(event.event_time.to_rfc3339())
                        .bind(event.actual.map(|value| value.to_string()))
                        .bind(event.previous.map(|value| value.to_string()))
                        .bind(event.consensus.map(|value| value.to_string()))
                        .bind(event.forecast.map(|value| value.to_string()))
                        .execute(&mut *transaction)
                        .await?;
                    }
                    (id, false)
                }
            };

            remember_identity(&mut transaction, &observed, id).await?;
            remember_identity(&mut transaction, &event, id).await?;

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

    /// Bounded, read-only Telegram lookup. Blank search covers the surrounding week;
    /// a literal keyword searches all retained events, nearest occurrence first.
    /// The eleventh row is a sentinel so the bot can ask users to narrow the search.
    pub async fn search_for_admin(
        &self,
        keyword: &str,
        now: DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let rows = sqlx::query(
            r#"SELECT * FROM economic_event
               WHERE (? <> '' OR (event_time >= ? AND event_time <= ?))
                 AND (instr(lower(event), lower(?)) > 0
                   OR instr(event_zh_cn, ?) > 0
                   OR instr(event_zh_tw, ?) > 0
                   OR instr(lower(country), lower(?)) > 0
                   OR instr(lower(currency), lower(?)) > 0)
               ORDER BY abs(unixepoch(event_time) - ?), event_time, id
               LIMIT 11"#,
        )
        .bind(keyword)
        .bind((now - Duration::days(7)).to_rfc3339())
        .bind((now + Duration::days(7)).to_rfc3339())
        .bind(keyword)
        .bind(keyword)
        .bind(keyword)
        .bind(keyword)
        .bind(keyword)
        .bind(now.timestamp())
        .fetch_all(&self.pool)
        .await?;
        rows.into_iter().map(event_from_row).collect()
    }

    pub async fn find_provider_event(
        &self,
        provider: &str,
        provider_id: &str,
    ) -> Result<Option<EconomicEvent>, AppError> {
        let mut connection = self.pool.acquire().await?;
        find_identity(&mut connection, provider, provider_id).await
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

    pub async fn description(&self, event_id: i64) -> Result<Option<EventDescription>, AppError> {
        let row = sqlx::query("SELECT en, zh_cn, zh_tw FROM event_description WHERE event_id = ?")
            .bind(event_id)
            .fetch_optional(&self.pool)
            .await?;
        row.map(|row| {
            Ok(EventDescription {
                en: row.try_get("en")?,
                zh_cn: row.try_get("zh_cn")?,
                zh_tw: row.try_get("zh_tw")?,
            })
        })
        .transpose()
    }

    pub async fn save_description(
        &self,
        event_id: i64,
        description: &EventDescription,
    ) -> Result<(), AppError> {
        let now = Utc::now().to_rfc3339();
        sqlx::query(
            "INSERT INTO event_description (event_id, en, zh_cn, zh_tw, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(event_id) DO UPDATE SET en = excluded.en, zh_cn = excluded.zh_cn, zh_tw = excluded.zh_tw, updated_at = excluded.updated_at",
        )
        .bind(event_id)
        .bind(&description.en)
        .bind(&description.zh_cn)
        .bind(&description.zh_tw)
        .bind(&now)
        .bind(&now)
        .execute(&self.pool)
        .await?;
        Ok(())
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
        self.history_page_multi(countries, category, limit, offset, from, to, None)
            .await
    }

    /// History page filtered before pagination, matching the client's country set and importance.
    #[allow(clippy::too_many_arguments)]
    pub async fn history_page_multi(
        &self,
        countries: Vec<String>,
        category: Option<&str>,
        limit: u32,
        offset: u32,
        from: Option<DateTime<Utc>>,
        to: Option<DateTime<Utc>>,
        importance: Option<Vec<u8>>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        if importance.as_ref().is_some_and(Vec::is_empty) {
            return Ok(Vec::new());
        }
        let levels = importance.unwrap_or_default();
        let importance_clause = if levels.is_empty() {
            String::new()
        } else {
            format!("AND importance IN ({})", vec!["?"; levels.len()].join(","))
        };
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
                 {importance_clause}
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
        for level in &levels {
            query = query.bind(i64::from(*level));
        }
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
               WHERE status = 'scheduled' AND time_exact = 1 AND importance >= ?
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

    /// Missing exact-time releases only; no network traffic outside the configured window.
    pub async fn biquote_release_candidates(
        &self,
        now: DateTime<Utc>,
        timeout_minutes: i64,
        minimum_importance: u8,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let rows = sqlx::query(
            "SELECT * FROM economic_event WHERE actual IS NULL AND time_exact=1
             AND status IN ('scheduled','watching','data_unavailable') AND importance >= ?
             AND julianday(event_time) <= julianday(?) AND julianday(event_time) >= julianday(?)
             ORDER BY event_time,id",
        )
        .bind(i64::from(minimum_importance))
        .bind(now.to_rfc3339())
        .bind((now - Duration::minutes(timeout_minutes.max(0))).to_rfc3339())
        .fetch_all(&self.pool)
        .await?;
        rows.into_iter().map(event_from_row).collect()
    }

    /// Include legacy `Z` and canonical `+00:00` timestamp storage in ambiguity checks.
    pub async fn events_at_release_time(
        &self,
        time: DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError> {
        let rows = sqlx::query(
            "SELECT * FROM economic_event WHERE julianday(event_time)=julianday(?) ORDER BY id",
        )
        .bind(time.to_rfc3339())
        .fetch_all(&self.pool)
        .await?;
        rows.into_iter().map(event_from_row).collect()
    }

    /// Atomic first-value fill. Keep every existing ID/topic and the target comparison basis.
    /// Verified source pairs may fill legacy duplicate rows without stealing their aliases.
    pub async fn fill_biquote_release(
        &self,
        expected: &EconomicEvent,
        source: &EconomicEvent,
    ) -> Result<bool, AppError> {
        use crate::calendar::release_identity::verified_relation;
        if source.provider != "biquote" || source.actual.is_none() {
            return Ok(false);
        }
        let mut transaction = self.pool.begin_with("BEGIN IMMEDIATE").await?;
        let current = sqlx::query("SELECT * FROM economic_event WHERE id=?")
            .bind(expected.id)
            .fetch_one(&mut *transaction)
            .await?;
        let current = event_from_row(current)?;
        if current.provider != expected.provider
            || current.provider_id != expected.provider_id
            || current.country != expected.country
            || current.event != expected.event
            || current.event_time != expected.event_time
            || current.actual.is_some()
            || !current.time_exact
            || !source.time_exact
            || current.country != source.country
            || current.event_time != source.event_time
        {
            return Ok(false);
        }
        let verified = verified_relation(&current, source);
        if verified == Some(false) {
            return Ok(false);
        }
        // Recheck all identity rules under the write lock, including generic matches.
        let peers = sqlx::query(
            "SELECT * FROM economic_event WHERE country=? AND julianday(event_time)=julianday(?)",
        )
        .bind(&current.country)
        .bind(current.event_time.to_rfc3339())
        .fetch_all(&mut *transaction)
        .await?
        .into_iter()
        .map(event_from_row)
        .collect::<Result<Vec<_>, _>>()?;
        if peers
            .iter()
            .filter(|peer| peer.provider == current.provider && same_occurrence(peer, source))
            .count()
            != 1
        {
            return Ok(false);
        }
        let owner = find_identity(&mut transaction, &source.provider, &source.provider_id).await?;
        if owner.as_ref().is_some_and(|owner| owner.id != expected.id) && verified != Some(true) {
            return Ok(false);
        }
        if owner.is_none() && !same_occurrence(&current, source) {
            return Ok(false);
        }
        let actual = match release_value_for(&current, source, &peers) {
            Ok(actual) => actual,
            Err(reason) => {
                tracing::debug!(
                    event_id = current.id,
                    ?reason,
                    "BiQuote transactional fill skipped"
                );
                return Ok(false);
            }
        };
        let now = Utc::now();
        let mut metadata = current.release_metadata.clone();
        if current.provider == source.provider && current.provider_id == source.provider_id {
            metadata.period = metadata.period.or(source.release_metadata.period.clone());
            metadata.series_id = metadata
                .series_id
                .or(source.release_metadata.series_id.clone());
        }
        metadata.actual_source = Some(source.provider.clone());
        metadata.actual_source_id = Some(source.provider_id.clone());
        metadata.actual_observed_at = Some(now);
        let metadata = serde_json::to_string(&metadata)
            .map_err(|_| AppError::Internal("cannot encode release metadata".into()))?;
        let filled = sqlx::query("UPDATE economic_event SET actual=?,release_metadata=?,updated_at=?
            WHERE id=? AND actual IS NULL AND status IN ('scheduled','watching','data_unavailable')")
            .bind(actual.to_string()).bind(metadata).bind(now.to_rfc3339()).bind(current.id)
            .execute(&mut *transaction).await?.rows_affected() == 1;
        if filled && owner.is_none() {
            remember_identity(&mut transaction, source, expected.id).await?;
        }
        transaction.commit().await?;
        Ok(filled)
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
            // Release-value polling can time out before the configured market window ends.
            // Keep these events in the collector until it advances them to analysis.
            EventStatus::Timeout,
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

    pub async fn transition_status(
        &self,
        id: i64,
        from: EventStatus,
        to: EventStatus,
    ) -> Result<bool, AppError> {
        Ok(
            sqlx::query("UPDATE economic_event SET status=?,updated_at=? WHERE id=? AND status=?")
                .bind(to.as_str())
                .bind(Utc::now().to_rfc3339())
                .bind(id)
                .bind(from.as_str())
                .execute(&self.pool)
                .await?
                .rows_affected()
                == 1,
        )
    }

    /// Both release watchers share this claim, so only one sends WebSocket/FCM notifications.
    pub async fn claim_release(&self, id: i64) -> Result<bool, AppError> {
        Ok(sqlx::query("UPDATE economic_event SET status='released',updated_at=?
            WHERE id=? AND actual IS NOT NULL AND status IN ('scheduled','watching','data_unavailable')")
            .bind(Utc::now().to_rfc3339()).bind(id)
            .execute(&self.pool).await?.rows_affected() == 1)
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
