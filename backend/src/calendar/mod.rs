mod forex_factory;
pub mod parse;
mod provider;
pub mod trading_economics;
pub mod trading_economics_api;
mod tradingview;

pub use forex_factory::ForexFactoryProvider;
pub use provider::CalendarProvider;
pub use trading_economics::TradingEconomicsProvider;
pub use trading_economics_api::TradingEconomicsApiProvider;
pub use tradingview::TradingViewProvider;

use std::{collections::HashMap, sync::Arc};

use chrono::{DateTime, Utc};

use crate::{
    alert::HealthRegistry,
    error::AppError,
    model::{EconomicEvent, EventStatus},
    repository::EventRepository,
    translation::TranslationService,
};

pub const PRIMARY_KEY: &str = "calendar.primary";
pub const FALLBACK_KEY: &str = "calendar.fallback";

/// Result of one calendar refresh, including which provider actually answered.
pub struct CalendarFetch {
    pub events: Vec<EconomicEvent>,
    /// Set when the primary source failed and the weekly fallback had to cover the range.
    pub degraded_detail: Option<String>,
}

/// Aggregates the keyless calendar feeds and keeps them honest.
///
/// The primary source is TradingView; the weekly Forex Factory feed covers a primary outage
/// but never replaces primary observations, and published values are copied onto matching
/// weekly rows so those rows do not stay permanently empty.
pub struct CalendarService {
    primary: Arc<dyn CalendarProvider>,
    fallback: Arc<dyn CalendarProvider>,
    events: EventRepository,
    translation: Option<Arc<TranslationService>>,
    health: Option<Arc<HealthRegistry>>,
}

impl CalendarService {
    pub fn new(
        primary: Arc<dyn CalendarProvider>,
        fallback: Arc<dyn CalendarProvider>,
        events: EventRepository,
    ) -> Self {
        Self {
            primary,
            fallback,
            events,
            translation: None,
            health: None,
        }
    }

    pub fn with_translation(mut self, translation: Arc<TranslationService>) -> Self {
        self.translation = Some(translation);
        self
    }

    pub fn with_health(mut self, health: Arc<HealthRegistry>) -> Self {
        self.health = Some(health);
        self
    }

    pub async fn sync(&self, start: DateTime<Utc>, end: DateTime<Utc>) -> Result<usize, AppError> {
        let fetched = self.refresh(start, end).await?;
        Ok(fetched.events.len())
    }

    /// Fetches, translates and persists one range. Returns the rows that were saved.
    pub async fn refresh(
        &self,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<CalendarFetch, AppError> {
        let fetched = self.lookup(start, end).await?;
        if fetched.events.is_empty() {
            return Ok(fetched);
        }
        let events = self.enrich(fetched.events).await;
        self.events.save_events(&events).await?;
        Ok(CalendarFetch {
            events,
            degraded_detail: fetched.degraded_detail,
        })
    }

    /// Reads both feeds without writing anything.
    ///
    /// The event watcher only needs the current provider values; persisting there would add an
    /// observation row on every 15-second scan and bloat the history table.
    pub async fn lookup(
        &self,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<CalendarFetch, AppError> {
        let mut primary_events: Vec<EconomicEvent> = Vec::new();
        let mut degraded_detail = None;
        match self.primary.fetch_events(start, end).await {
            Ok(events) if !events.is_empty() => {
                if let Some(health) = &self.health {
                    health.record_success(PRIMARY_KEY).await;
                }
                primary_events = events;
            }
            Ok(_) => {
                degraded_detail = Some("primary source returned no events".to_owned());
                if let Some(health) = &self.health {
                    health
                        .record_degraded(PRIMARY_KEY, "primary source returned no events")
                        .await;
                }
            }
            Err(error) => {
                degraded_detail = Some(error.to_string());
                if let Some(health) = &self.health {
                    health.record_failure(PRIMARY_KEY, error.to_string()).await;
                }
            }
        }

        if primary_events.is_empty() {
            let fallback = match self.fallback.fetch_events(start, end).await {
                Ok(events) => {
                    if let Some(health) = &self.health {
                        health.record_success(FALLBACK_KEY).await;
                    }
                    events
                }
                Err(error) => {
                    if let Some(health) = &self.health {
                        health.record_failure(FALLBACK_KEY, error.to_string()).await;
                        health
                            .notify_critical(
                                "calendar.all_sources",
                                format!(
                                    "日历主源与回退源都不可用：primary={} / fallback={}",
                                    degraded_detail.as_deref().unwrap_or("unknown"),
                                    error
                                ),
                            )
                            .await;
                    }
                    return Err(error);
                }
            };
            let mut fallback = fallback;
            // The weekly feed uses different titles for the same indicator, so copy the
            // published values from any stored primary rows of the same occurrence.
            let cached = self
                .events
                .events_in_range(start, end)
                .await
                .unwrap_or_default();
            let mut sources: Vec<EconomicEvent> = primary_events.clone();
            sources.extend(
                cached
                    .into_iter()
                    .filter(|event| event.provider == "trading_view"),
            );
            fill_missing_values(&mut fallback, &sources);
            return Ok(CalendarFetch {
                events: fallback,
                degraded_detail,
            });
        }

        Ok(CalendarFetch {
            events: primary_events,
            degraded_detail,
        })
    }

    async fn enrich(&self, mut events: Vec<EconomicEvent>) -> Vec<EconomicEvent> {
        if let Some(translation) = &self.translation
            && let Err(error) = translation.enrich(&mut events).await
        {
            tracing::warn!(%error, "event-name translation failed; saving source names");
        }
        events
    }
}

/// Copies published values from a primary occurrence onto the weekly-schedule row of the same
/// occurrence.
///
/// Only rows that need a value are touched, and only when exactly one primary row matches
/// (country + exact instant + canonical title) and that row actually carries a value. Names,
/// ids and corrected translations are preserved and no row is ever deleted.
pub fn fill_missing_values(fallback: &mut [EconomicEvent], sources: &[EconomicEvent]) {
    let mut by_occurrence: HashMap<(String, DateTime<Utc>, String), Vec<&EconomicEvent>> =
        HashMap::new();
    for event in sources {
        if event.provider != "trading_view" || event.actual.is_none() {
            continue;
        }
        by_occurrence
            .entry(parse::occurrence_key(
                &event.country,
                event.event_time,
                &event.event,
            ))
            .or_default()
            .push(event);
    }
    for target in fallback.iter_mut() {
        if target.provider != "forex_factory" || target.actual.is_some() {
            continue;
        }
        let key = parse::occurrence_key(&target.country, target.event_time, &target.event);
        // Ambiguity leaves the row untouched: a wrong alias would attach another
        // indicator's value to it.
        let Some(matcher) = by_occurrence.get(&key).filter(|rows| rows.len() == 1) else {
            continue;
        };
        let source = matcher[0];
        target.actual = source.actual;
        target.previous = source.previous;
        target.consensus = source.consensus;
        target.forecast = source.forecast;
        target.unit = source.unit.clone();
        target.status = if source.status == EventStatus::Historical {
            EventStatus::Historical
        } else {
            source.status
        };
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;
    use rust_decimal::Decimal;

    fn event(provider: &str, country: &str, event: &str, actual: Option<i64>) -> EconomicEvent {
        let time = Utc.with_ymd_and_hms(2026, 9, 11, 12, 30, 0).unwrap();
        EconomicEvent {
            id: 0,
            provider: provider.to_owned(),
            provider_id: format!("{provider}|{event}"),
            release_group_id: None,
            country: country.to_owned(),
            currency: Some("USD".into()),
            category: event.to_owned(),
            event: event.to_owned(),
            event_zh_cn: None,
            event_zh_tw: None,
            event_time: time,
            importance: 3,
            actual: actual.map(Decimal::from),
            previous: Some(Decimal::from(1)),
            consensus: Some(Decimal::from(2)),
            forecast: Some(Decimal::from(2)),
            unit: Some("%".into()),
            status: if actual.is_some() {
                EventStatus::Released
            } else {
                EventStatus::DataUnavailable
            },
            time_exact: true,
        }
    }

    #[test]
    fn published_values_are_copied_onto_the_matching_weekly_row() {
        let mut fallback = vec![event(
            "forex_factory",
            "United States",
            "Core CPI m/m",
            None,
        )];
        let sources = vec![event(
            "trading_view",
            "United States",
            "Core Inflation Rate MoM",
            Some(3),
        )];
        fill_missing_values(&mut fallback, &sources);
        assert_eq!(fallback[0].actual, Some(Decimal::from(3)));
        assert_eq!(fallback[0].status, EventStatus::Released);
        // The weekly title is preserved; only values are copied.
        assert_eq!(fallback[0].event, "Core CPI m/m");
    }

    #[test]
    fn ambiguous_or_missing_primary_rows_leave_the_weekly_row_untouched() {
        let mut fallback = vec![event(
            "forex_factory",
            "United States",
            "Core CPI m/m",
            None,
        )];
        let sources = vec![
            event("trading_view", "United States", "Core CPI m/m", Some(3)),
            event(
                "trading_view",
                "United States",
                "Core Inflation Rate MoM",
                Some(4),
            ),
        ];
        fill_missing_values(&mut fallback, &sources);
        assert!(fallback[0].actual.is_none());

        let mut other = vec![event("forex_factory", "Japan", "Core CPI m/m", None)];
        fill_missing_values(&mut other, &sources);
        assert!(other[0].actual.is_none());
    }

    #[test]
    fn a_primary_row_is_never_overwritten_by_the_weekly_feed() {
        let mut rows = vec![event("trading_view", "United States", "CPI YoY", Some(5))];
        let sources = vec![event("forex_factory", "United States", "CPI YoY", None)];
        fill_missing_values(&mut rows, &sources);
        assert_eq!(rows[0].actual, Some(Decimal::from(5)));
    }
}
