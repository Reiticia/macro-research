use std::str::FromStr;

use async_trait::async_trait;
use chrono::{DateTime, TimeZone, Utc};
use serde::Deserialize;

use crate::{
    error::AppError,
    market::MarketDataProvider,
    model::{Candle, Interval, LiveQuote, MarketSymbol, Quote},
};

pub struct BinanceProvider {
    client: reqwest::Client,
    base_url: String,
}

impl BinanceProvider {
    pub fn new(client: reqwest::Client, base_url: impl Into<String>) -> Self {
        Self {
            client,
            base_url: base_url.into().trim_end_matches('/').to_owned(),
        }
    }

    fn ticker(symbol: MarketSymbol) -> Result<&'static str, AppError> {
        match symbol {
            MarketSymbol::Bitcoin => Ok("BTCUSDT"),
            MarketSymbol::Ethereum => Ok("ETHUSDT"),
            _ => Err(AppError::Provider(format!(
                "{symbol} does not belong to Binance"
            ))),
        }
    }
}

#[async_trait]
impl MarketDataProvider for BinanceProvider {
    async fn quote(&self, symbol: MarketSymbol) -> Result<Quote, AppError> {
        let ticker = Self::ticker(symbol)?;
        let response: BinanceTicker = self
            .client
            .get(format!("{}/ticker/price", self.base_url))
            .query(&[("symbol", ticker)])
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        let price = f64::from_str(&response.price)
            .map_err(|error| AppError::Provider(format!("invalid Binance price: {error}")))?;
        Ok(Quote {
            symbol,
            timestamp: Utc::now(),
            price,
        })
    }

    async fn live_quote(&self, symbol: MarketSymbol) -> Result<LiveQuote, AppError> {
        let ticker = Self::ticker(symbol)?;
        let response: Binance24HourTicker = self
            .client
            .get(format!("{}/ticker/24hr", self.base_url))
            .query(&[("symbol", ticker)])
            .send()
            .await?
            .error_for_status()?
            .json()
            .await?;
        let number = |value: &str| {
            f64::from_str(value)
                .map_err(|error| AppError::Provider(format!("invalid Binance price: {error}")))
        };
        let timestamp = Utc
            .timestamp_millis_opt(response.close_time)
            .single()
            .unwrap_or_else(Utc::now);
        Ok(LiveQuote {
            symbol,
            timestamp,
            price: number(&response.last_price)?,
            provider: "binance".into(),
            change_percent: Some(number(&response.price_change_percent)?),
            high: Some(number(&response.high_price)?),
            low: Some(number(&response.low_price)?),
            market_state: Some("open".into()),
            stale: false,
        })
    }

    async fn candles(
        &self,
        symbol: MarketSymbol,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
        interval: Interval,
    ) -> Result<Vec<Candle>, AppError> {
        let ticker = Self::ticker(symbol)?;
        let interval = match interval {
            Interval::OneMinute => "1m",
            Interval::FiveMinutes => "5m",
            Interval::FifteenMinutes => "15m",
            Interval::OneHour => "1h",
            Interval::OneDay => "1d",
        };
        let mut cursor = start.timestamp_millis();
        let mut candles = Vec::new();
        while cursor < end.timestamp_millis() {
            let values: Vec<Vec<serde_json::Value>> = self
                .client
                .get(format!("{}/klines", self.base_url))
                .query(&[
                    ("symbol", ticker.to_owned()),
                    ("interval", interval.to_owned()),
                    ("startTime", cursor.to_string()),
                    ("endTime", (end.timestamp_millis() - 1).to_string()),
                    ("limit", "1000".to_owned()),
                ])
                .send()
                .await?
                .error_for_status()?
                .json()
                .await?;

            if values.is_empty() {
                break;
            }
            let count = values.len();
            let last_time = values
                .last()
                .and_then(|row| row.first())
                .and_then(|v| v.as_i64())
                .ok_or_else(|| AppError::Provider("Binance candle has no timestamp".into()))?;
            if last_time < cursor {
                return Err(AppError::Provider(
                    "Binance pagination did not advance".into(),
                ));
            }
            let page = values
                .into_iter()
                .filter_map(|row| {
                    let timestamp = row
                        .first()?
                        .as_i64()
                        .and_then(|value| Utc.timestamp_millis_opt(value).single())?;
                    let number = |index: usize| row.get(index)?.as_str()?.parse::<f64>().ok();
                    Some(Candle {
                        symbol,
                        timestamp,
                        open: number(1)?,
                        high: number(2)?,
                        low: number(3)?,
                        close: number(4)?,
                        volume: number(5),
                    })
                })
                .collect::<Vec<_>>();
            candles.extend(page);
            if count < 1000 {
                break;
            }
            cursor = last_time + 1;
            tokio::time::sleep(std::time::Duration::from_millis(250)).await;
        }
        candles.retain(|c| c.timestamp >= start && c.timestamp < end);
        Ok(candles)
    }
}

#[derive(Debug, Deserialize)]
struct BinanceTicker {
    price: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Binance24HourTicker {
    last_price: String,
    price_change_percent: String,
    high_price: String,
    low_price: String,
    close_time: i64,
}
