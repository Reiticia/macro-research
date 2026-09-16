use async_trait::async_trait;
use chrono::{DateTime, TimeZone, Utc};
use serde_json::Value;

use crate::{
    error::AppError,
    market::MarketDataProvider,
    model::{Candle, Interval, LiveQuote, MarketSymbol, Quote},
};

/// CNBC is the primary source for Treasury yields: it answers on networks where Yahoo is
/// blocked or rate limited, and its one-day chart returns one-minute bars for the last few
/// sessions, which is exactly what the reaction windows need.
pub struct CnbcProvider {
    client: reqwest::Client,
    quote_url: String,
    chart_url: String,
}

impl CnbcProvider {
    pub fn new(
        client: reqwest::Client,
        quote_url: impl Into<String>,
        chart_url: impl Into<String>,
    ) -> Self {
        Self {
            client,
            quote_url: quote_url.into(),
            chart_url: chart_url.into().trim_end_matches('/').to_owned(),
        }
    }

    fn ticker(symbol: MarketSymbol) -> Result<&'static str, AppError> {
        match symbol {
            MarketSymbol::Us2y => Ok("US2Y"),
            MarketSymbol::Us10y => Ok("US10Y"),
            other => Err(AppError::Provider(format!("CNBC does not quote {other}"))),
        }
    }

    async fn quote_body(&self, ticker: &str) -> Result<String, AppError> {
        let response = self
            .client
            .get(&self.quote_url)
            .query(&[
                ("symbols", ticker.to_owned()),
                ("requestMethod", "itv".to_owned()),
                ("noform", "1".to_owned()),
                ("partnerId", "2".to_owned()),
                ("fund", "1".to_owned()),
                ("exthrs", "1".to_owned()),
                ("output", "json".to_owned()),
                ("events", "1".to_owned()),
            ])
            .header("accept", "application/json")
            .send()
            .await?
            .error_for_status()?;
        Ok(response.text().await?)
    }

    async fn chart_body(&self, ticker: &str) -> Result<String, AppError> {
        let url = format!("{}/1D.json", self.chart_url);
        let response = self
            .client
            .get(url)
            .query(&[
                ("symbol", ticker.to_owned()),
                ("interval", "1".to_owned()),
                ("requestMethod", "itv".to_owned()),
                ("events", "1".to_owned()),
            ])
            .header("accept", "application/json")
            .send()
            .await?
            .error_for_status()?;
        Ok(response.text().await?)
    }
}

#[async_trait]
impl MarketDataProvider for CnbcProvider {
    async fn quote(&self, symbol: MarketSymbol) -> Result<Quote, AppError> {
        let live = self.live_quote(symbol).await?;
        Ok(Quote {
            symbol: live.symbol,
            timestamp: live.timestamp,
            price: live.price,
        })
    }

    async fn live_quote(&self, symbol: MarketSymbol) -> Result<LiveQuote, AppError> {
        let ticker = Self::ticker(symbol)?;
        let body = self.quote_body(ticker).await?;
        parse_quote(symbol, &body)
    }

    async fn candles(
        &self,
        symbol: MarketSymbol,
        _start: DateTime<Utc>,
        end: DateTime<Utc>,
        interval: Interval,
    ) -> Result<Vec<Candle>, AppError> {
        if interval != Interval::OneMinute {
            return Err(AppError::Provider(
                "CNBC only serves one-minute bars".into(),
            ));
        }
        let ticker = Self::ticker(symbol)?;
        let body = self.chart_body(ticker).await?;
        Ok(parse_candles(symbol, &body, end))
    }
}

pub fn parse_quote(symbol: MarketSymbol, body: &str) -> Result<LiveQuote, AppError> {
    let root: Value = serde_json::from_str(body)
        .map_err(|error| AppError::Provider(format!("invalid CNBC quote payload: {error}")))?;
    let quote = root
        .get("FormattedQuoteResult")
        .and_then(|value| value.get("FormattedQuote"))
        .and_then(Value::as_array)
        .and_then(|rows| rows.first())
        .ok_or_else(|| AppError::Provider(format!("CNBC returned no quote for {symbol}")))?;
    let price = quote
        .get("last")
        .and_then(scalar_text)
        .and_then(|value| value.trim_end_matches('%').parse::<f64>().ok())
        .ok_or_else(|| AppError::Provider(format!("CNBC returned no price for {symbol}")))?;
    // CNBC's own change_pct disagrees with its change field, so derive the move from the
    // previous session close, exactly like the other providers.
    let previous = quote
        .get("previous_day_closing")
        .and_then(scalar_text)
        .and_then(|value| value.trim_end_matches('%').parse::<f64>().ok())
        .or_else(|| {
            quote
                .get("open")
                .and_then(scalar_text)
                .and_then(|value| value.trim_end_matches('%').parse::<f64>().ok())
        });
    Ok(LiveQuote {
        symbol,
        timestamp: quote
            .get("last_time")
            .and_then(scalar_text)
            .and_then(|value| parse_cnbc_instant(&value))
            .unwrap_or_else(Utc::now),
        price,
        provider: "cnbc".to_owned(),
        change_percent: previous
            .filter(|value| *value != 0.0)
            .map(|previous| (price - previous) / previous * 100.0),
        high: None,
        low: None,
        market_state: quote
            .get("curmktstatus")
            .and_then(scalar_text)
            .map(|value| value.to_lowercase()),
        stale: false,
    })
}

pub fn parse_candles(symbol: MarketSymbol, body: &str, end: DateTime<Utc>) -> Vec<Candle> {
    let Ok(root) = serde_json::from_str::<Value>(body) else {
        return Vec::new();
    };
    let Some(bars) = root
        .get("barData")
        .and_then(|value| value.get("priceBars"))
        .and_then(Value::as_array)
    else {
        return Vec::new();
    };
    let mut candles = Vec::with_capacity(bars.len());
    for bar in bars {
        let Some(millis) = bar
            .get("tradeTimeinMills")
            .and_then(scalar_text)
            .and_then(|value| value.parse::<i64>().ok())
        else {
            continue;
        };
        let timestamp = match Utc.timestamp_millis_opt(millis).single() {
            Some(value) => value,
            None => continue,
        };
        if timestamp > end + chrono::Duration::seconds(60) {
            continue;
        }
        let Some(close) = bar
            .get("close")
            .and_then(scalar_text)
            .and_then(|value| value.parse::<f64>().ok())
        else {
            continue;
        };
        let field = |name: &str| {
            bar.get(name)
                .and_then(scalar_text)
                .and_then(|value| value.parse::<f64>().ok())
        };
        candles.push(Candle {
            symbol,
            timestamp,
            open: field("open").unwrap_or(close),
            high: field("high").unwrap_or(close),
            low: field("low").unwrap_or(close),
            close,
            volume: None,
        });
    }
    candles.sort_by_key(|candle| candle.timestamp);
    candles
}

/// CNBC sends offsets without a colon (`2026-09-11T11:17:11.000-0400`), which strict RFC 3339
/// rejects, so the basic offset form is tried as well.
fn parse_cnbc_instant(value: &str) -> Option<DateTime<Utc>> {
    DateTime::parse_from_rfc3339(value)
        .ok()
        .or_else(|| DateTime::parse_from_str(value, "%Y-%m-%dT%H:%M:%S%.3f%z").ok())
        .map(|parsed| parsed.with_timezone(&Utc))
}

fn scalar_text(value: &Value) -> Option<String> {
    match value {
        Value::String(text) => Some(text.clone()),
        Value::Number(number) => Some(number.to_string()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_treasury_quote() {
        let body = r#"{"FormattedQuoteResult":{"FormattedQuote":[{
            "last":"4.123%","previous_day_closing":"4.050%","open":"4.060%",
            "last_time":"2026-09-11T11:17:11.000-0400","curmktstatus":"OPEN"}]}}"#;
        let quote = parse_quote(MarketSymbol::Us10y, body).unwrap();
        assert!((quote.price - 4.123).abs() < 1e-9);
        assert!((quote.change_percent.unwrap() - 1.8024691358024691).abs() < 1e-6);
        assert_eq!(quote.market_state.as_deref(), Some("open"));
        assert_eq!(quote.timestamp.to_rfc3339(), "2026-09-11T15:17:11+00:00");
    }

    #[test]
    fn parses_one_minute_bars_and_filters_the_future() {
        let end = DateTime::parse_from_rfc3339("2026-09-11T15:00:00Z")
            .unwrap()
            .with_timezone(&Utc);
        let body = r#"{"barData":{"priceBars":[
            {"tradeTimeinMills":"1789000000000","open":"4.1","high":"4.2","low":"4.0","close":"4.15"},
            {"tradeTimeinMills":"99999999999999","open":"4.1","high":"4.2","low":"4.0","close":"4.15"}]}}"#;
        let candles = parse_candles(MarketSymbol::Us10y, body, end);
        assert_eq!(candles.len(), 1);
        assert!((candles[0].close - 4.15).abs() < 1e-9);
    }
}
