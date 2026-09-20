use std::collections::HashMap;

use chrono::{DateTime, Duration, Utc};

use crate::model::{MarketReaction, MarketSnapshot, MarketSymbol, ReactionUnit};

pub fn calculate_reactions(
    event_id: i64,
    event_time: DateTime<Utc>,
    snapshots: &[MarketSnapshot],
) -> Vec<MarketReaction> {
    let mut grouped: HashMap<MarketSymbol, Vec<&MarketSnapshot>> = HashMap::new();
    for snapshot in snapshots {
        grouped.entry(snapshot.symbol).or_default().push(snapshot);
    }

    grouped
        .into_iter()
        .filter_map(|(symbol, mut values)| {
            values.sort_by_key(|snapshot| snapshot.timestamp);
            let baseline = nearest(&values, event_time - Duration::minutes(1), 180)?;
            if baseline.price == 0.0 {
                return None;
            }
            let is_yield = matches!(symbol, MarketSymbol::Us2y | MarketSymbol::Us10y);
            let reaction_unit = if is_yield {
                ReactionUnit::BasisPoints
            } else {
                ReactionUnit::Percent
            };
            let change = |minutes| {
                nearest(&values, event_time + Duration::minutes(minutes), 180).map(|snapshot| {
                    if is_yield {
                        (snapshot.price - baseline.price) * 100.0
                    } else {
                        (snapshot.price - baseline.price) / baseline.price * 100.0
                    }
                })
            };
            Some(MarketReaction {
                event_id,
                symbol,
                baseline_price: baseline.price,
                reaction_unit,
                change_1m: change(1),
                change_5m: change(5),
                change_15m: change(15),
                change_30m: change(30),
                change_60m: change(60),
            })
        })
        .collect()
}

fn nearest<'a>(
    snapshots: &[&'a MarketSnapshot],
    target: DateTime<Utc>,
    tolerance_seconds: i64,
) -> Option<&'a MarketSnapshot> {
    snapshots
        .iter()
        .copied()
        .min_by_key(|snapshot| (snapshot.timestamp - target).num_milliseconds().abs())
        .filter(|snapshot| (snapshot.timestamp - target).num_seconds().abs() <= tolerance_seconds)
}

#[cfg(test)]
mod tests {
    use chrono::TimeZone;

    use super::*;

    fn snapshot(event_id: i64, minute: u32, price: f64) -> MarketSnapshot {
        MarketSnapshot {
            id: minute as i64,
            event_id,
            symbol: MarketSymbol::Gold,
            timestamp: Utc.with_ymd_and_hms(2026, 9, 10, 12, minute, 0).unwrap(),
            price,
            open: None,
            high: None,
            low: None,
            close: Some(price),
            volume: None,
        }
    }

    #[test]
    fn measures_each_horizon_against_pre_release_baseline() {
        let time = Utc.with_ymd_and_hms(2026, 9, 10, 12, 30, 0).unwrap();
        let values = vec![
            snapshot(1, 29, 100.0),
            snapshot(1, 31, 99.0),
            snapshot(1, 35, 98.0),
        ];
        let result = calculate_reactions(1, time, &values);
        assert_eq!(result.len(), 1);
        assert_eq!(result[0].change_1m, Some(-1.0));
        assert_eq!(result[0].reaction_unit, ReactionUnit::Percent);
        assert_eq!(result[0].change_5m, Some(-2.0));
        assert_eq!(result[0].change_15m, None);
    }
}
