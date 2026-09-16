pub mod reactions;
pub mod repository;

use chrono::{DateTime, Duration, Months, NaiveDate, Utc};
use std::{collections::HashMap, future::Future, sync::Arc, time::Duration as StdDuration};

use crate::{
    analysis::AnalysisService,
    calendar::CalendarProvider,
    error::AppError,
    market::MarketService,
    model::{Candle, EventStatus, HistoricalEvidence, Interval, MarketSymbol},
    repository::{AnalysisRepository, EventRepository},
    translation::TranslationService,
};
use repository::{BackfillRepository, BackfillSummary};

#[derive(Clone, Copy, Debug)]
pub struct BackfillRange {
    pub from: NaiveDate,
    pub to: NaiveDate,
}

impl BackfillRange {
    /// Three calendar months, through yesterday UTC. Excludes ongoing releases.
    pub fn three_months(now: DateTime<Utc>) -> Self {
        let today = now.date_naive();
        Self {
            from: today
                .checked_sub_months(Months::new(3))
                .expect("valid current date"),
            to: today.pred_opt().expect("valid current date"),
        }
    }
    pub fn validate(self, now: DateTime<Utc>) -> Result<Self, AppError> {
        if self.to < self.from
            || self.to >= now.date_naive()
            || (self.to - self.from).num_days() >= 93
        {
            return Err(AppError::InvalidRequest(
                "backfill must be an ordered UTC date range of at most 93 days ending before today"
                    .into(),
            ));
        }
        Ok(self)
    }
    pub fn from_args(args: &[String], now: DateTime<Utc>) -> Result<Self, AppError> {
        match args {
            [flag] if flag == "--backfill" => Self::three_months(now).validate(now),
            [flag, from, to] if flag == "--backfill" => {
                let parse = |v: &str| {
                    NaiveDate::parse_from_str(v, "%Y-%m-%d")
                        .map_err(|_| AppError::InvalidRequest("expected YYYY-MM-DD".into()))
                };
                Self {
                    from: parse(from)?,
                    to: parse(to)?,
                }
                .validate(now)
            }
            _ => Err(AppError::InvalidRequest(
                "usage: --backfill [YYYY-MM-DD YYYY-MM-DD]".into(),
            )),
        }
    }
}

pub struct BackfillService {
    pub repository: BackfillRepository,
    calendar: Arc<dyn CalendarProvider>,
    events: EventRepository,
    analyses: AnalysisRepository,
    market: Arc<MarketService>,
    analysis: Arc<AnalysisService>,
    request_delay: StdDuration,
    translation: Option<Arc<TranslationService>>,
}

impl BackfillService {
    pub fn new(
        repository: BackfillRepository,
        calendar: Arc<dyn CalendarProvider>,
        events: EventRepository,
        analyses: AnalysisRepository,
        market: Arc<MarketService>,
        analysis: Arc<AnalysisService>,
        request_delay: StdDuration,
    ) -> Self {
        Self {
            repository,
            calendar,
            events,
            analyses,
            market,
            analysis,
            request_delay,
            translation: None,
        }
    }

    pub fn with_translation(mut self, translation: Arc<TranslationService>) -> Self {
        self.translation = Some(translation);
        self
    }

    pub async fn run(&self, range: BackfillRange) -> Result<BackfillSummary, AppError> {
        if self.market.symbols().is_empty() {
            return Err(AppError::Config(
                "backfill requires at least one configured market symbol".into(),
            ));
        }
        let range = range.validate(Utc::now())?;
        let id = self.repository.begin(range.from, range.to).await?;
        let result = tokio::select! {
            result = self.run_days(id, range) => result,
            _ = tokio::signal::ctrl_c() => Err(AppError::Internal("backfill cancelled; rerun the same range to resume".into())),
        };
        match result {
            Ok(()) => {
                let s = self.repository.summary(id).await?;
                let status = if s.days_failed > 0 || s.days_partial > 0 {
                    "partial"
                } else {
                    "complete"
                };
                self.repository.finish(id, status, None).await?;
                self.repository.summary(id).await
            }
            Err(error) => {
                self.repository
                    .finish(id, "failed", Some(&error.to_string()))
                    .await?;
                Err(error)
            }
        }
    }

    async fn run_days(&self, id: i64, range: BackfillRange) -> Result<(), AppError> {
        let mut date = range.from;
        while date <= range.to {
            if !self.repository.day_complete(id, date).await? {
                match self.run_day(id, date).await {
                    Ok(()) => (),
                    Err(error) => {
                        self.repository
                            .save_day(id, date, "failed", 0, 0, Some(&error.to_string()))
                            .await?;
                        tracing::warn!(run_id=id, %date, %error, "historical day failed");
                        // A denied account is not a per-day problem: don't hammer
                        // the same endpoint for every date in the range.
                        if matches!(&error, AppError::Http(e) if e.status().is_some_and(|s| matches!(s.as_u16(), 401 | 403 | 410 | 451)))
                            || matches!(error, AppError::Database(_))
                        {
                            return Err(error);
                        }
                    }
                }
            }
            date = date
                .succ_opt()
                .ok_or_else(|| AppError::InvalidRequest("date overflow".into()))?;
        }
        Ok(())
    }

    async fn run_day(&self, run: i64, date: NaiveDate) -> Result<(), AppError> {
        let start = date.and_hms_opt(0, 0, 0).unwrap().and_utc();
        let end = start + Duration::days(1);
        let mut events = self
            .request(|| self.calendar.fetch_events(start, end))
            .await?;
        if events
            .iter()
            .any(|e| e.event_time < start || e.event_time >= end)
        {
            return Err(AppError::Provider(
                "calendar ignored historical date bounds; import rejected".into(),
            ));
        }
        if let Some(translation) = &self.translation
            && let Err(error) = translation.enrich(&mut events).await
        {
            tracing::warn!(%error, %date, "historical event-name translation failed");
        }
        let count = events.len();
        let mut ids = Vec::new();
        let mut analyzed = 0;
        let mut pending_release = false;
        for mut event in events {
            if event.event_time + Duration::minutes(60) > Utc::now() {
                pending_release = true;
                continue;
            }
            // Do not overwrite release-time data/reports already captured live.
            if let Some(existing) = self
                .events
                .find_provider_event(&event.provider, &event.provider_id)
                .await?
            {
                if matches!(
                    existing.status,
                    EventStatus::Watching
                        | EventStatus::Released
                        | EventStatus::CollectingMarketData
                        | EventStatus::Analyzing
                ) {
                    pending_release = true;
                    continue;
                }
                match self.analyses.get(existing.id).await {
                    Ok(report) if report.historical.is_none() => {
                        analyzed += 1;
                        continue;
                    }
                    Ok(_) | Err(AppError::NotFound) => (),
                    Err(e) => return Err(e),
                }
            }
            event.status = EventStatus::Historical;
            let id = self.events.save_events(&[event]).await?[0];
            self.events.set_status(id, EventStatus::Historical).await?;
            ids.push(id);
        }
        self.repository
            .save_day(run, date, "partial", count, analyzed, None)
            .await?;
        // Fetch once per day+asset, not once per event. Includes pre-release
        // baseline and +60m for events near UTC midnight.
        let from = start - Duration::minutes(10);
        let to = end + Duration::minutes(61);
        let mut bars: HashMap<MarketSymbol, Result<(Interval, Vec<Candle>), String>> =
            HashMap::new();
        let mut last_error = pending_release.then(|| {
            "some releases are still live or their +60m window has not closed; retry later"
                .to_owned()
        });
        if !ids.is_empty() {
            for &symbol in self.market.symbols() {
                let result = self.fetch_day(symbol, from, to).await;
                match result {
                    Ok(Some(value)) => {
                        bars.insert(symbol, Ok(value));
                    }
                    Ok(None) => {
                        bars.insert(symbol, Err("retention_limit".into()));
                    }
                    Err(e) => {
                        if matches!(e, AppError::Database(_)) {
                            return Err(e);
                        }
                        tracing::warn!(%symbol, %date, error=%e, "historical market data unavailable");
                        last_error = Some(e.to_string());
                        bars.insert(symbol, Err("provider_error".into()));
                    }
                }
                // Renew the run lease between network requests.
                self.repository
                    .save_day(run, date, "partial", count, analyzed, last_error.as_deref())
                    .await?;
            }
        }
        let mut complete = !pending_release;
        for id in ids {
            let event = self.events.get(id).await?;
            let mut observed = Vec::new();
            let mut coverage = Vec::new();
            for &symbol in self.market.symbols() {
                let entry = if !event.time_exact {
                    reactions::unavailable(symbol, "approximate_release_time", false)
                } else {
                    match &bars[&symbol] {
                        Ok((interval, candles)) => {
                            let (reaction, c) = reactions::calculate(
                                id,
                                event.event_time,
                                symbol,
                                *interval,
                                candles,
                            );
                            if let Some(r) = reaction {
                                observed.push(r);
                            }
                            c
                        }
                        Err(reason) => {
                            reactions::unavailable(symbol, reason, reason == "provider_error")
                        }
                    }
                };
                complete &= entry.status == "complete";
                coverage.push(entry);
            }
            let evidence = HistoricalEvidence {
                fetched_at: Utc::now(),
                revised_data_possible: true,
                coverage,
            };
            self.analysis
                .analyze_historical(id, observed, evidence)
                .await?;
            // Leave status historical even on success: old releases never enter
            // real-time collectors or send release/analysis push notifications.
            analyzed += 1;
            self.repository
                .save_day(run, date, "partial", count, analyzed, last_error.as_deref())
                .await?;
        }
        self.repository
            .save_day(
                run,
                date,
                if complete { "complete" } else { "partial" },
                count,
                analyzed,
                last_error.as_deref(),
            )
            .await?;
        tracing::info!(run_id=run, %date, events=count, analyses=analyzed, complete, "historical day imported");
        Ok(())
    }

    async fn fetch_day(
        &self,
        symbol: MarketSymbol,
        from: DateTime<Utc>,
        to: DateTime<Utc>,
    ) -> Result<Option<(Interval, Vec<Candle>)>, AppError> {
        let source = reactions::source(symbol);
        // Never cache a future-ended window as complete during the first UTC hour.
        let to = to.min(Utc::now());
        // Previously cached 1m bars remain usable after the provider's retention expires.
        for interval in [Interval::OneMinute, Interval::FiveMinutes] {
            if let Some(bars) = self
                .repository
                .cached_candles(source, symbol, interval, from, to)
                .await?
            {
                return Ok(Some((interval, bars)));
            }
        }
        let Some(interval) = reactions::interval_for(symbol, from, Utc::now()) else {
            return Ok(None);
        };
        let bars = self
            .request(|| self.market.historical_candles(symbol, from, to, interval))
            .await?;
        if bars.iter().any(|c| {
            c.symbol != symbol || c.timestamp < from || c.timestamp >= to || !reactions::valid(c)
        }) {
            return Err(AppError::Provider(
                "historical candle payload has invalid prices, symbols or timestamps; not cached"
                    .into(),
            ));
        }
        self.repository
            .cache_candles(source, symbol, interval, from, to, &bars)
            .await?;
        Ok(Some((interval, bars)))
    }

    async fn request<T, F, Fut>(&self, mut call: F) -> Result<T, AppError>
    where
        F: FnMut() -> Fut,
        Fut: Future<Output = Result<T, AppError>>,
    {
        for attempt in 0..3 {
            tokio::time::sleep(self.request_delay).await;
            match call().await {
                Ok(value) => return Ok(value),
                Err(error) => {
                    let retryable = matches!(&error, AppError::Http(e) if e.is_timeout() || e.is_connect()
                        || e.status().is_some_and(|s| s.as_u16()==429 || s.is_server_error()));
                    if !retryable || attempt == 2 {
                        return Err(error);
                    }
                    tokio::time::sleep(StdDuration::from_secs(2 << attempt)).await;
                }
            }
        }
        unreachable!()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;
    #[test]
    fn range_is_three_calendar_months_not_ninety_days_and_excludes_today() {
        let now = Utc.with_ymd_and_hms(2026, 9, 8, 10, 0, 0).unwrap();
        let r = BackfillRange::three_months(now);
        assert_eq!(r.from.to_string(), "2026-06-08");
        assert_eq!(r.to.to_string(), "2026-09-07");
        assert!(r.validate(now).is_ok());
        assert!(
            BackfillRange {
                from: r.from,
                to: now.date_naive()
            }
            .validate(now)
            .is_err()
        );
        assert!(BackfillRange::from_args(&["--backfill".into(), "bad".into()], now).is_err());
    }
}
