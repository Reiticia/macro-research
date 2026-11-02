//! Keyless earnings calendars. A provider timestamp is evidence for a date, not an exact
//! company-confirmed release time. TradingView forecasts remain a separate, unaligned snapshot.
use crate::{alert::HealthRegistry, error::AppError};
use chrono::{DateTime, Duration, NaiveDate, TimeZone, Utc};
use chrono_tz::America::New_York;
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sqlx::SqlitePool;
use std::{
    collections::{BTreeMap, HashMap},
    str::FromStr,
    sync::Arc,
    time::Duration as StdDuration,
};
use tokio::{sync::Mutex, time::Instant};

mod diagnostics;
pub(crate) use diagnostics::{content_kind, network_failure};
use diagnostics::{response_shape, with_evidence};

pub const NASDAQ_URL: &str = "https://api.nasdaq.com/api/calendar/earnings";
pub const TRADINGVIEW_URL: &str = "https://scanner.tradingview.com/america/scan";
pub const TV_COLUMNS: &[&str] = &[
    "name",
    "description",
    "earnings_release_next_date",
    "earnings_per_share_forecast_next_fq",
    "revenue_forecast_next_fq",
    "currency",
];

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EarningsCalendar {
    pub date: NaiveDate,
    pub timezone: String,
    pub updated_at: DateTime<Utc>,
    pub nasdaq_updated_at: Option<DateTime<Utc>>,
    pub trading_view_updated_at: Option<DateTime<Utc>>,
    pub stale: bool,
    pub warnings: Vec<String>,
    pub events: Vec<EarningsEvent>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EarningsEvent {
    pub symbol: String,
    pub name: String,
    pub source: String,
    pub session: String,
    pub fiscal_quarter_ending: Option<String>,
    pub eps_forecast: Option<String>,
    pub analyst_count: Option<u32>,
    pub market_cap: Option<String>,
    pub actual_eps: Option<String>,
    pub surprise_percent: Option<String>,
    pub last_year_eps: Option<String>,
    pub trading_view: Option<TradingViewForecast>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TradingViewForecast {
    pub ticker: String,
    pub next_date: Option<NaiveDate>,
    pub next_eps_forecast: Option<String>,
    pub next_revenue_forecast: Option<String>,
    /// Quote currency is NOT proof of the currency of financial statements.
    pub quote_currency: Option<String>,
    pub date_conflict: bool,
}

pub fn validate_date(date: NaiveDate) -> Result<(), AppError> {
    let today = Utc::now().with_timezone(&New_York).date_naive();
    if date < today - Duration::days(120) || date > today + Duration::days(60) {
        return Err(AppError::InvalidRequest(
            "earnings date must be within the last 120 or next 60 days".into(),
        ));
    }
    Ok(())
}

fn provider(message: &str) -> AppError {
    AppError::Provider(format!("earnings: {message}"))
}
fn string(value: &Value) -> Option<String> {
    value
        .as_str()
        .map(str::trim)
        .filter(|s| !s.is_empty() && !matches!(*s, "N/A" | "--" | "-"))
        .map(str::to_owned)
}
/// Normalize once; preserve zero and negative values, never infer missing units.
pub fn number(value: &Value) -> Option<String> {
    let raw = if value.is_number() {
        value.to_string()
    } else {
        string(value)?
    };
    let mut clean = raw.replace([',', '$', '%'], "").trim().to_owned();
    if clean.starts_with('(') && clean.ends_with(')') {
        clean = format!("-{}", &clean[1..clean.len() - 1]);
    }
    Decimal::from_str(&clean)
        .or_else(|_| Decimal::from_scientific(&clean))
        .ok()
        .map(|n| n.normalize().to_string())
}

pub fn parse_nasdaq(root: &Value, date: NaiveDate) -> Result<Vec<EarningsEvent>, AppError> {
    if root.pointer("/status/rCode").and_then(Value::as_i64) != Some(200) {
        return Err(provider("Nasdaq status"));
    }
    let data = root
        .get("data")
        .filter(|v| v.is_object())
        .ok_or_else(|| provider("Nasdaq data"))?;
    let as_of = data
        .get("asOf")
        .and_then(Value::as_str)
        .and_then(|s| NaiveDate::parse_from_str(s, "%a, %b %e, %Y").ok());
    if as_of != Some(date) {
        return Err(provider("Nasdaq date mismatch"));
    }
    let rows = match data.get("rows") {
        Some(Value::Null) => return Ok(vec![]),
        Some(Value::Array(rows)) => rows,
        _ => return Err(provider("Nasdaq rows")),
    };
    let mut events = BTreeMap::new();
    for row in rows {
        let symbol = string(&row["symbol"]).ok_or_else(|| provider("Nasdaq symbol"))?;
        let name = string(&row["name"]).ok_or_else(|| provider("Nasdaq company"))?;
        let event = EarningsEvent {
            symbol: symbol.clone(),
            name,
            source: "nasdaq".into(),
            session: match row["time"].as_str() {
                Some("time-pre-market") => "pre_market",
                Some("time-after-hours") => "after_hours",
                _ => "unknown",
            }
            .into(),
            fiscal_quarter_ending: string(&row["fiscalQuarterEnding"]),
            eps_forecast: number(&row["epsForecast"]),
            analyst_count: string(&row["noOfEsts"]).and_then(|s| s.parse().ok()),
            market_cap: number(&row["marketCap"]),
            actual_eps: number(&row["eps"]),
            surprise_percent: number(&row["surprise"]),
            last_year_eps: number(&row["lastYearEPS"]),
            trading_view: None,
        };
        // Ambiguous duplicates must not silently replace another fiscal occurrence.
        if events.insert(symbol, event).is_some() {
            return Err(provider("duplicate Nasdaq symbol"));
        }
    }
    Ok(events.into_values().collect())
}

pub fn parse_tradingview(root: &Value, date: NaiveDate) -> Result<Vec<EarningsEvent>, AppError> {
    let rows = root
        .get("data")
        .and_then(Value::as_array)
        .ok_or_else(|| provider("TradingView rows"))?;
    let total = root
        .get("totalCount")
        .and_then(Value::as_u64)
        .ok_or_else(|| provider("TradingView count"))?;
    if total as usize != rows.len() {
        return Err(provider("incomplete TradingView response"));
    }
    let tickers: std::collections::HashSet<_> =
        rows.iter().filter_map(|row| row["s"].as_str()).collect();
    if tickers.len() != rows.len() {
        return Err(provider("duplicate or missing TradingView ticker"));
    }
    rows.iter()
        .map(|row| {
            let ticker = string(&row["s"])
                .filter(|s| s.contains(':'))
                .ok_or_else(|| provider("TradingView ticker"))?;
            let d = row["d"]
                .as_array()
                .filter(|d| d.len() == TV_COLUMNS.len())
                .ok_or_else(|| provider("TradingView columns"))?;
            let symbol = string(&d[0]).ok_or_else(|| provider("TradingView symbol"))?;
            let next_date = d[2]
                .as_i64()
                .and_then(|ts| Utc.timestamp_opt(ts, 0).single())
                .map(|ts| ts.with_timezone(&New_York).date_naive());
            Ok(EarningsEvent {
                name: string(&d[1]).unwrap_or_else(|| symbol.clone()),
                symbol,
                source: "tradingview".into(),
                session: "unknown".into(),
                fiscal_quarter_ending: None,
                eps_forecast: None,
                analyst_count: None,
                market_cap: None,
                actual_eps: None,
                surprise_percent: None,
                last_year_eps: None,
                trading_view: Some(TradingViewForecast {
                    ticker,
                    next_date,
                    next_eps_forecast: number(&d[3]),
                    next_revenue_forecast: number(&d[4]),
                    quote_currency: string(&d[5]),
                    date_conflict: next_date.is_some_and(|d| d != date),
                }),
            })
        })
        .collect()
}

pub fn merge_tradingview(events: &mut [EarningsEvent], snapshots: Vec<EarningsEvent>) {
    let mut by_symbol: HashMap<String, Vec<EarningsEvent>> = HashMap::new();
    for event in snapshots {
        by_symbol
            .entry(event.symbol.clone())
            .or_default()
            .push(event);
    }
    for event in events {
        // Exact, unique match only. Do not guess exchange or share-class aliases.
        if event.actual_eps.is_some() {
            continue;
        }
        if let Some(matches) = by_symbol.get_mut(&event.symbol)
            && matches.len() == 1
        {
            event.trading_view = matches[0].trading_view.take();
        }
    }
}

fn ttl(date: NaiveDate, today: NaiveDate) -> Duration {
    let days = (date - today).num_days();
    Duration::hours(if (-7..=0).contains(&days) {
        1
    } else if (1..=7).contains(&days) {
        6
    } else {
        24
    })
}

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub enum EarningsSource {
    Nasdaq,
    TradingView,
}

#[derive(Default)]
struct Requests {
    attempted: HashMap<NaiveDate, Instant>,
    probes: HashMap<(EarningsSource, NaiveDate), Instant>,
    nasdaq_next: Option<Instant>,
    tradingview_next: Option<Instant>,
}

pub struct EarningsService {
    pool: SqlitePool,
    nasdaq: reqwest::Client,
    tradingview: reqwest::Client,
    nasdaq_url: String,
    tradingview_url: String,
    requests: Mutex<Requests>,
    health: Option<Arc<HealthRegistry>>,
    pub logos: crate::earnings_logo::CompanyLogoService,
}

impl EarningsService {
    pub fn new(pool: SqlitePool, nasdaq: reqwest::Client, tradingview: reqwest::Client) -> Self {
        Self {
            logos: crate::earnings_logo::CompanyLogoService::new(nasdaq.clone()),
            pool,
            nasdaq,
            tradingview,
            nasdaq_url: NASDAQ_URL.into(),
            tradingview_url: TRADINGVIEW_URL.into(),
            requests: Mutex::new(Requests::default()),
            health: None,
        }
    }
    pub fn with_logos(mut self, logos: crate::earnings_logo::CompanyLogoService) -> Self {
        self.logos = logos;
        self
    }
    pub fn with_urls(mut self, nasdaq: String, tradingview: String) -> Self {
        self.nasdaq_url = nasdaq;
        self.tradingview_url = tradingview;
        self
    }
    pub fn with_health(mut self, health: Arc<HealthRegistry>) -> Self {
        self.health = Some(health);
        self
    }
    pub async fn cached(&self, date: NaiveDate) -> Result<Option<EarningsCalendar>, AppError> {
        let raw: Option<String> =
            sqlx::query_scalar("SELECT payload FROM earnings_calendar WHERE date = ?")
                .bind(date.to_string())
                .fetch_optional(&self.pool)
                .await?;
        raw.map(|raw| serde_json::from_str(&raw).map_err(|e| AppError::Internal(e.to_string())))
            .transpose()
    }
    /// A real single-source request, with no daily cache read/write or fallback. Shares the
    /// production provider gates; a successful probe never resets background health counters.
    pub async fn probe(&self, source: EarningsSource, date: NaiveDate) -> Result<String, AppError> {
        validate_date(date)?;
        if source == EarningsSource::TradingView
            && date < Utc::now().with_timezone(&New_York).date_naive()
        {
            return Err(AppError::InvalidRequest(
                "TradingView earnings scans next-period snapshots, not historical actuals; HTTP=not_requested".into(),
            ));
        }
        let mut requests = self.requests.lock().await;
        requests
            .probes
            .retain(|_, at| at.elapsed() < StdDuration::from_secs(60));
        if let Some(at) = requests.probes.get(&(source, date)) {
            return Err(AppError::QuotaExceeded {
                retry_after_seconds: (60 - at.elapsed().as_secs() as i64).max(1),
            });
        }
        requests.probes.insert((source, date), Instant::now());
        let (events, evidence) = match source {
            EarningsSource::Nasdaq => self.nasdaq(date, &mut requests).await?,
            EarningsSource::TradingView => self.tradingview(date, None, &mut requests).await?,
        };
        Ok(format!("{evidence}; validated_rows={}", events.len()))
    }

    async fn request(
        &self,
        builder: reqwest::RequestBuilder,
        next: &mut Option<Instant>,
        date: NaiveDate,
    ) -> Result<(Value, String), AppError> {
        if next.is_some_and(|at| at > Instant::now() + StdDuration::from_secs(2)) {
            let remaining = next
                .unwrap()
                .saturating_duration_since(Instant::now())
                .as_secs();
            return Err(provider(&format!(
                "provider cooldown; date={date}; retry_after_seconds={remaining}; HTTP=not_requested"
            )));
        }
        if let Some(at) = *next {
            tokio::time::sleep_until(at).await;
        }
        *next = Some(Instant::now() + StdDuration::from_secs(1));
        let mut response = builder
            .timeout(StdDuration::from_secs(10))
            .send()
            .await
            .map_err(|error| {
                provider(&format!(
                    "date={date}; HTTP=not_received; network={}",
                    network_failure(&error)
                ))
            })?;
        let status = response.status();
        let mut evidence = format!(
            "date={date} (America/New_York); HTTP={}; content-type={}",
            status.as_u16(),
            content_kind(response.headers())
        );
        if matches!(status.as_u16(), 403 | 429) {
            let delay = response
                .headers()
                .get("retry-after")
                .and_then(|v| v.to_str().ok())
                .and_then(|value| {
                    value.parse::<u64>().ok().or_else(|| {
                        DateTime::parse_from_rfc2822(value).ok().map(|at| {
                            (at.with_timezone(&Utc) - Utc::now()).num_seconds().max(0) as u64
                        })
                    })
                })
                .unwrap_or(300)
                .clamp(60, 86400);
            *next = Some(Instant::now() + StdDuration::from_secs(delay));
            evidence.push_str(&format!("; retry_after_seconds={delay}"));
        }
        if !status.is_success() {
            return Err(provider(&evidence));
        }
        const MAX_RESPONSE_BYTES: usize = 2 * 1024 * 1024;
        if response
            .content_length()
            .is_some_and(|n| n > MAX_RESPONSE_BYTES as u64)
        {
            return Err(provider(&format!("{evidence}; response=too_large")));
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|error| {
            provider(&format!(
                "{evidence}; response_body={}",
                network_failure(&error)
            ))
        })? {
            if bytes.len() + chunk.len() > MAX_RESPONSE_BYTES {
                return Err(provider(&format!("{evidence}; response=too_large")));
            }
            bytes.extend_from_slice(&chunk);
        }
        let root: Value = serde_json::from_slice(&bytes)
            .map_err(|_| provider(&format!("{evidence}; JSON=invalid")))?;
        evidence.push_str(&format!("; {}", response_shape(&root)));
        Ok((root, evidence))
    }
    async fn nasdaq(
        &self,
        date: NaiveDate,
        requests: &mut Requests,
    ) -> Result<(Vec<EarningsEvent>, String), AppError> {
        let request = self
            .nasdaq
            .get(&self.nasdaq_url)
            .query(&[("date", date.to_string())])
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", "https://www.nasdaq.com")
            .header("Referer", "https://www.nasdaq.com/");
        let (root, evidence) = self
            .request(request, &mut requests.nasdaq_next, date)
            .await?;
        let events = parse_nasdaq(&root, date).map_err(|error| with_evidence(error, &evidence))?;
        Ok((events, evidence))
    }
    async fn tradingview(
        &self,
        date: NaiveDate,
        symbols: Option<Vec<String>>,
        requests: &mut Requests,
    ) -> Result<(Vec<EarningsEvent>, String), AppError> {
        let date_scan = symbols.is_none();
        let filters = if let Some(symbols) = symbols {
            if symbols.is_empty() {
                return Ok((
                    vec![],
                    format!("date={date}; HTTP=not_requested; symbols=empty"),
                ));
            }
            vec![
                json!({"left":"is_primary", "operation":"equal", "right":true}),
                json!({"left":"name", "operation":"in_range", "right": symbols}),
            ]
        } else {
            let start = New_York
                .from_local_datetime(&date.and_hms_opt(0, 0, 0).unwrap())
                .single()
                .ok_or_else(|| provider("day boundary"))?
                .timestamp();
            let end = New_York
                .from_local_datetime(&date.succ_opt().unwrap().and_hms_opt(0, 0, 0).unwrap())
                .single()
                .ok_or_else(|| provider("day boundary"))?
                .timestamp();
            vec![
                json!({"left":"is_primary", "operation":"equal", "right":true}),
                json!({"left":"earnings_release_next_date", "operation":"in_range", "right":[start,end-1]}),
            ]
        };
        // Fail closed on truncation instead of presenting the first page as a complete day.
        let request = self
            .tradingview
            .post(&self.tradingview_url)
            .header("Origin", "https://www.tradingview.com")
            .json(&json!({"filter":filters,"columns":TV_COLUMNS,"range":[0,2000]}));
        let (root, evidence) = self
            .request(request, &mut requests.tradingview_next, date)
            .await?;
        let events =
            parse_tradingview(&root, date).map_err(|error| with_evidence(error, &evidence))?;
        if date_scan
            && events
                .iter()
                .any(|event| event.trading_view.as_ref().and_then(|tv| tv.next_date) != Some(date))
        {
            return Err(provider(&format!(
                "TradingView fallback date mismatch; {evidence}"
            )));
        }
        Ok((events, evidence))
    }
    pub async fn calendar(
        &self,
        date: NaiveDate,
        force: bool,
    ) -> Result<EarningsCalendar, AppError> {
        let now = Utc::now();
        let today = now.with_timezone(&New_York).date_naive();
        validate_date(date)?;
        // Serializes cache fill and provider pacing across HTTP callers and the scheduler.
        let mut requests = self.requests.lock().await;
        let cached = self.cached(date).await?;
        if let Some(cache) = &cached
            && !force
            && now - cache.updated_at < ttl(date, today)
        {
            return Ok(cache.clone());
        }
        if requests
            .attempted
            .get(&date)
            .is_some_and(|at| at.elapsed() < StdDuration::from_secs(60))
        {
            return cached
                .map(|mut c| {
                    c.stale = true;
                    c.warnings.push("refresh_cooldown".into());
                    c
                })
                .ok_or(AppError::QuotaExceeded {
                    retry_after_seconds: 60,
                });
        }
        requests
            .attempted
            .retain(|_, at| at.elapsed() < StdDuration::from_secs(86400));
        requests.attempted.insert(date, Instant::now());
        let primary = self
            .nasdaq(date, &mut requests)
            .await
            .map(|(events, _)| events);
        let mut warnings = vec![];
        self.record("earnings_nasdaq", &primary).await;
        let symbols: Option<Vec<String>> = primary.as_ref().ok().map(|events| {
            events
                .iter()
                .filter(|e| e.actual_eps.is_none())
                .map(|e| e.symbol.clone())
                .collect()
        });
        // Scanner fields describe the NEXT occurrence, not a historical fiscal period.
        let fetch_secondary =
            date >= today && symbols.as_ref().is_none_or(|symbols| !symbols.is_empty());
        let secondary = if fetch_secondary {
            self.tradingview(date, symbols, &mut requests)
                .await
                .map(|(events, _)| events)
        } else {
            Ok(vec![])
        };
        if fetch_secondary {
            self.record("earnings_tradingview", &secondary).await;
        }
        if secondary.is_err() {
            warnings.push("tradingview_unavailable".into());
        }
        let nasdaq_ok = primary.is_ok();
        let tv_ok = fetch_secondary && secondary.is_ok();
        let mut events = match primary {
            Ok(events) => events,
            Err(error) => {
                tracing::warn!(%date, %error, "Nasdaq earnings unavailable");
                warnings.push("nasdaq_unavailable".into());
                // Never replace an authoritative cached day with a partial fallback.
                if let Some(mut cache) = cached
                    && (cache.nasdaq_updated_at.is_some() || secondary.is_err() || !fetch_secondary)
                {
                    cache.stale = true;
                    cache.warnings = warnings;
                    return Ok(cache);
                }
                match &secondary {
                    Ok(events) if fetch_secondary => events
                        .iter()
                        .filter(|e| e.trading_view.as_ref().and_then(|t| t.next_date) == Some(date))
                        .cloned()
                        .collect(),
                    Ok(_) => return Err(provider("historical Nasdaq unavailable")),
                    Err(_) => return Err(provider("all sources unavailable")),
                }
            }
        };
        if nasdaq_ok && let Ok(snapshots) = secondary {
            merge_tradingview(&mut events, snapshots);
        }
        if events
            .iter()
            .any(|e| e.trading_view.as_ref().is_some_and(|t| t.date_conflict))
        {
            warnings.push("date_conflict".into());
        }
        let calendar = EarningsCalendar {
            date,
            timezone: "America/New_York".into(),
            updated_at: now,
            nasdaq_updated_at: nasdaq_ok.then_some(now),
            trading_view_updated_at: tv_ok.then_some(now),
            stale: false,
            warnings,
            events,
        };
        let payload =
            serde_json::to_string(&calendar).map_err(|e| AppError::Internal(e.to_string()))?;
        sqlx::query("INSERT INTO earnings_calendar(date, payload) VALUES (?, ?) ON CONFLICT(date) DO UPDATE SET payload=excluded.payload").bind(date.to_string()).bind(payload).execute(&self.pool).await?;
        sqlx::query("DELETE FROM earnings_calendar WHERE date < ?")
            .bind((today - Duration::days(120)).to_string())
            .execute(&self.pool)
            .await?;
        Ok(calendar)
    }
    async fn record<T>(&self, key: &str, result: &Result<T, AppError>) {
        if let Some(health) = &self.health {
            match result {
                Ok(_) => health.record_success(key).await,
                Err(error) => health.record_failure(key, error.to_string()).await,
            }
        }
    }
}

pub async fn sync_loop(service: Arc<EarningsService>) {
    let mut interval = tokio::time::interval(StdDuration::from_secs(3600));
    interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    loop {
        interval.tick().await;
        let today = Utc::now().with_timezone(&New_York).date_naive();
        for offset in 0..68 {
            let date = today + Duration::days(if offset <= 60 { offset } else { 60 - offset });
            if let Err(error) = service.calendar(date, false).await {
                tracing::warn!(%date, %error, "earnings synchronization failed");
            }
            tokio::time::sleep(StdDuration::from_secs(1)).await;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn decimal_parsing_preserves_zero_and_missing() {
        assert_eq!(number(&json!("($1,234.50)")), Some("-1234.5".into()));
        assert_eq!(number(&json!("$0.00")), Some("0".into()));
        assert_eq!(number(&json!("N/A")), None);
        assert_eq!(number(&Value::Null), None);
    }
    #[test]
    fn nasdaq_validates_date_and_keeps_unknown_time() {
        let date = NaiveDate::from_ymd_opt(2026, 11, 2).unwrap();
        let root = json!({"status":{"rCode":200},"data":{"asOf":"Mon, Nov 2, 2026","rows":[{"symbol":"AAPL","name":"Apple","time":"time-not-supplied","epsForecast":"$0","noOfEsts":"7"}]}});
        let events = parse_nasdaq(&root, date).unwrap();
        assert_eq!(events[0].session, "unknown");
        assert_eq!(events[0].eps_forecast.as_deref(), Some("0"));
        assert!(parse_nasdaq(&root, date.succ_opt().unwrap()).is_err());
        assert!(parse_nasdaq(&json!({}), date).is_err());
    }
    #[test]
    fn timestamps_are_new_york_dates_not_exact_release_times() {
        let date = NaiveDate::from_ymd_opt(2026, 11, 2).unwrap();
        let ts = DateTime::parse_from_rfc3339("2026-11-03T01:00:00Z")
            .unwrap()
            .timestamp();
        let root = json!({"totalCount":1,"data":[{"s":"NASDAQ:AAPL","d":["AAPL","Apple",ts,1.98,100000000000_i64,"USD"]}]});
        let events = parse_tradingview(&root, date).unwrap();
        assert_eq!(events[0].session, "unknown");
        assert_eq!(
            events[0].trading_view.as_ref().unwrap().next_date,
            Some(date)
        );
        assert!(!events[0].trading_view.as_ref().unwrap().date_conflict);
        assert!(parse_tradingview(&json!({"totalCount":2,"data":[]}), date).is_err());
    }
}
