use std::collections::BTreeMap;

use chrono::{DateTime, Duration, Utc};

use crate::model::{
    Candle, HistoricalCoverage, Interval, MarketReaction, MarketSymbol, ReactionUnit,
};

pub const HORIZONS: [i64; 5] = [1, 5, 15, 30, 60];

pub fn source(symbol: MarketSymbol) -> &'static str {
    if symbol.is_crypto() {
        "binance"
    } else if symbol.uses_biquote() {
        "biquote"
    } else {
        "yahoo"
    }
}

/// Conservative free-source intraday retention. No daily/hourly substitution.
pub fn interval_for(
    symbol: MarketSymbol,
    start: DateTime<Utc>,
    now: DateTime<Utc>,
) -> Option<Interval> {
    if symbol.is_crypto() || symbol.uses_biquote() || start >= now - Duration::days(7) {
        Some(Interval::OneMinute)
    } else if start >= now - Duration::days(59) {
        Some(Interval::FiveMinutes)
    } else {
        None
    }
}

pub fn unavailable(symbol: MarketSymbol, reason: &str, error: bool) -> HistoricalCoverage {
    HistoricalCoverage {
        symbol,
        source: source(symbol).into(),
        interval_seconds: None,
        status: if error { "error" } else { "unavailable" }.into(),
        reason: Some(reason.into()),
        available_horizons: vec![],
        baseline_time: None,
        sample_times: BTreeMap::new(),
    }
}

pub fn calculate(
    event_id: i64,
    event_time: DateTime<Utc>,
    symbol: MarketSymbol,
    interval: Interval,
    candles: &[Candle],
) -> (Option<MarketReaction>, HistoricalCoverage) {
    let seconds = interval.seconds();
    let mut coverage = unavailable(symbol, "missing_samples", false);
    coverage.interval_seconds = Some(seconds);
    // Candle timestamps are OPEN times. Prices are CLOSE prices and become
    // observable only at open + interval. A release-time or post-release bar
    // cannot be the baseline. All samples are at or before their target.
    let closed: Vec<_> = candles
        .iter()
        .filter(|c| c.symbol == symbol && valid(c))
        .map(|c| (c.timestamp + Duration::seconds(seconds), c.close))
        .collect();
    let baseline = closed
        .iter()
        .filter(|(time, _)| *time < event_time && *time >= event_time - Duration::seconds(seconds))
        .max_by_key(|(time, _)| *time);
    let Some(&(baseline_time, baseline_price)) = baseline else {
        return (None, coverage);
    };
    if baseline_price <= 0.0 {
        return (None, coverage);
    }
    coverage.baseline_time = Some(baseline_time);
    let is_yield = matches!(symbol, MarketSymbol::Us2y | MarketSymbol::Us10y);
    let changes: Vec<_> = HORIZONS
        .into_iter()
        .map(|minute| {
            // A 5m candle cannot measure a 1m reaction. Require a bar close within
            // 59 seconds before the requested horizon (no forward look-ahead).
            if minute * 60 < seconds {
                return None;
            }
            let target = event_time + Duration::minutes(minute);
            let sample = closed
                .iter()
                .filter(|(time, _)| {
                    *time > event_time && *time <= target && *time > target - Duration::minutes(1)
                })
                .max_by_key(|(time, _)| *time)?;
            coverage.sample_times.insert(minute, sample.0);
            coverage.available_horizons.push(minute);
            Some(if is_yield {
                (sample.1 - baseline_price) * 100.0
            } else {
                (sample.1 - baseline_price) / baseline_price * 100.0
            })
        })
        .collect();
    if coverage.available_horizons.is_empty() {
        return (None, coverage);
    }
    coverage.status = if coverage.available_horizons.len() == HORIZONS.len() {
        "complete"
    } else {
        "partial"
    }
    .into();
    coverage.reason = (coverage.status != "complete").then(|| "missing_samples".into());
    (
        Some(MarketReaction {
            event_id,
            symbol,
            baseline_price,
            reaction_unit: if is_yield {
                ReactionUnit::BasisPoints
            } else {
                ReactionUnit::Percent
            },
            change_1m: changes[0],
            change_5m: changes[1],
            change_15m: changes[2],
            change_30m: changes[3],
            change_60m: changes[4],
        }),
        coverage,
    )
}

pub fn valid(c: &Candle) -> bool {
    [c.open, c.high, c.low, c.close]
        .iter()
        .all(|p| p.is_finite() && *p > 0.0)
        && c.high >= c.low
        && c.close >= c.low
        && c.close <= c.high
        && c.open >= c.low
        && c.open <= c.high
        && c.volume.is_none_or(|v| v.is_finite() && v >= 0.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;
    fn time() -> DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 6, 8, 12, 30, 0).unwrap()
    }
    fn candle(open_minute: i64, price: f64, symbol: MarketSymbol) -> Candle {
        Candle {
            symbol,
            timestamp: time() + Duration::minutes(open_minute),
            open: price,
            high: price,
            low: price,
            close: price,
            volume: None,
        }
    }
    #[test]
    fn close_times_prevent_lookahead_and_missing_windows_stay_null() {
        let symbol = MarketSymbol::Gold;
        let bars = vec![
            candle(-2, 100.0, symbol),
            candle(-1, 900.0, symbol),
            candle(0, 101.0, symbol),
            candle(4, 98.0, symbol),
            candle(15, 400.0, symbol),
        ];
        let (r, coverage) = calculate(1, time(), symbol, Interval::OneMinute, &bars);
        let r = r.unwrap();
        assert_eq!(r.baseline_price, 100.0);
        assert_eq!(r.change_1m, Some(1.0));
        assert_eq!(r.change_5m, Some(-2.0));
        assert_eq!(r.change_15m, None); // T+16 is too late, not a T+15 sample.
        assert_eq!(coverage.baseline_time, Some(time() - Duration::minutes(1)));
        assert_eq!(coverage.status, "partial");
    }
    #[test]
    fn coarse_bars_do_not_invent_one_minute_returns() {
        let symbol = MarketSymbol::Us10y;
        let bars = vec![candle(-10, 4.0, symbol), candle(0, 4.1, symbol)];
        let (r, coverage) = calculate(1, time(), symbol, Interval::FiveMinutes, &bars);
        let r = r.unwrap();
        assert_eq!(r.change_1m, None);
        assert!((r.change_5m.unwrap() - 10.0).abs() < 1e-8);
        assert_eq!(r.reaction_unit, ReactionUnit::BasisPoints);
        assert_eq!(coverage.available_horizons, vec![5]);
    }
    #[test]
    fn no_post_release_baseline_or_stale_session_reuse() {
        let s = MarketSymbol::Gold;
        for bars in [
            vec![candle(0, 100.0, s)],
            vec![candle(-100, 100.0, s), candle(0, 110.0, s)],
        ] {
            assert!(
                calculate(1, time(), s, Interval::OneMinute, &bars)
                    .0
                    .is_none()
            );
        }
        assert_eq!(
            interval_for(s, time() - Duration::days(90), time()),
            Some(Interval::OneMinute)
        );
        assert!(interval_for(MarketSymbol::Us10y, time() - Duration::days(90), time()).is_none());
        assert_eq!(
            interval_for(MarketSymbol::Bitcoin, time() - Duration::days(90), time()),
            Some(Interval::OneMinute)
        );
    }
}
