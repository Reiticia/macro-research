use std::collections::BTreeMap;

use async_trait::async_trait;
use chrono::{DateTime, Duration, Utc};
use serde::Deserialize;

use crate::{
    error::AppError,
    market::MarketDataProvider,
    model::{Candle, Interval, LiveQuote, MarketSymbol, Quote},
};

pub struct BiquoteProvider {
    client: reqwest::Client,
    base_url: String,
}

impl BiquoteProvider {
    pub fn new(client: reqwest::Client, base_url: impl Into<String>) -> Self {
        Self {
            client,
            base_url: base_url.into().trim_end_matches('/').to_owned(),
        }
    }

    fn ticker(symbol: MarketSymbol) -> Result<&'static str, AppError> {
        match symbol {
            MarketSymbol::Gold => Ok("XAUUSD"),
            MarketSymbol::Silver => Ok("XAGUSD"),
            MarketSymbol::Dxy => Ok("DXY"),
            MarketSymbol::EurUsd => Ok("EURUSD"),
            MarketSymbol::GbpUsd => Ok("GBPUSD"),
            MarketSymbol::UsdJpy => Ok("USDJPY"),
            MarketSymbol::AudUsd => Ok("AUDUSD"),
            _ => Err(AppError::Provider(format!(
                "{symbol} does not belong to biquote"
            ))),
        }
    }

    async fn tick(&self, symbol: MarketSymbol, allow_stale: bool) -> Result<BiquoteTick, AppError> {
        let ticker = Self::ticker(symbol)?;
        Ok(self
            .client
            .get(format!("{}/api/{ticker}", self.base_url))
            .query(&[("allowStale", allow_stale)])
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?)
    }
}

#[async_trait]
impl MarketDataProvider for BiquoteProvider {
    async fn quote(&self, symbol: MarketSymbol) -> Result<Quote, AppError> {
        // Event-window collection must never turn a closed market's old price
        // into a new release-time observation.
        let tick = self.tick(symbol, false).await?;
        Ok(Quote {
            symbol,
            timestamp: tick.timestamp,
            price: tick.mid,
        })
    }

    async fn live_quote(&self, symbol: MarketSymbol) -> Result<LiveQuote, AppError> {
        // The market overview can show the last price while clearly labelling
        // closed/stale markets, matching biquote's public API contract.
        let tick = self.tick(symbol, true).await?;
        Ok(LiveQuote {
            symbol,
            timestamp: tick.timestamp,
            price: tick.mid,
            provider: "biquote".into(),
            change_percent: tick.day_diff_percent,
            high: tick.high,
            low: tick.low,
            market_state: tick.market_state,
            stale: tick.stale,
        })
    }

    async fn candles(
        &self,
        symbol: MarketSymbol,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
        interval: Interval,
    ) -> Result<Vec<Candle>, AppError> {
        if end <= start {
            return Ok(Vec::new());
        }
        let ticker = Self::ticker(symbol)?;
        let interval_name = match interval {
            Interval::OneMinute => "1m",
            Interval::FiveMinutes => "5m",
            Interval::FifteenMinutes => "15m",
            Interval::OneHour => "1h",
            Interval::OneDay => "1d",
        };

        // biquote caps each response at 1000 bars. Fixed non-overlapping time
        // windows make longer event/backfill ranges complete and deterministic.
        let page_span = Duration::seconds(interval.seconds() * 999);
        let mut cursor = start;
        let mut candles = BTreeMap::new();
        while cursor < end {
            let page_end = (cursor + page_span).min(end);
            let response: BiquoteOhlc = self
                .client
                .get(format!("{}/api/{ticker}/ohlc", self.base_url))
                .query(&[
                    ("interval", interval_name.to_owned()),
                    ("limit", "1000".to_owned()),
                    ("from", cursor.to_rfc3339()),
                    ("to", page_end.to_rfc3339()),
                ])
                .send()
                .await?
                .error_for_status()?
                .json()
                .await?;
            for bar in response.bars {
                if !bar.is_open && bar.open_time >= start && bar.open_time < end {
                    candles.insert(
                        bar.open_time,
                        Candle {
                            symbol,
                            timestamp: bar.open_time,
                            open: bar.open,
                            high: bar.high,
                            low: bar.low,
                            close: bar.close,
                            volume: bar.volume.or(bar.tick_volume),
                        },
                    );
                }
            }
            cursor = page_end;
        }
        Ok(candles.into_values().collect())
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BiquoteTick {
    mid: f64,
    timestamp: DateTime<Utc>,
    day_diff_percent: Option<f64>,
    high: Option<f64>,
    low: Option<f64>,
    market_state: Option<String>,
    #[serde(default)]
    stale: bool,
}

#[derive(Debug, Deserialize)]
struct BiquoteOhlc {
    bars: Vec<BiquoteBar>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BiquoteBar {
    open_time: DateTime<Utc>,
    open: f64,
    high: f64,
    low: f64,
    close: f64,
    volume: Option<f64>,
    tick_volume: Option<f64>,
    #[serde(default)]
    is_open: bool,
}
