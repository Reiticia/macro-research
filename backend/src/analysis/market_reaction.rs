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
            // The baseline must be an actually observed pre-release quote. Never use a
            // post-release sample merely because it is closest to T-1m.
            let baseline = values
                .iter()
                .copied()
                .filter(|snapshot| snapshot.timestamp < event_time)
                .filter(|snapshot| snapshot.timestamp >= event_time - Duration::seconds(180))
                .max_by_key(|snapshot| snapshot.timestamp)?;
            if !baseline.price.is_finite() || baseline.price <= 0.0 {
                return None;
            }
            let is_yield = matches!(symbol, MarketSymbol::Us2y | MarketSymbol::Us10y);
            let reaction_unit = if is_yield {
                ReactionUnit::BasisPoints
            } else {
                ReactionUnit::Percent
            };
            let change = |minutes| {
                let target = event_time + Duration::minutes(minutes);
                // Do not look forward from a reaction horizon. A sample must be after the
                // release and no more than 60 seconds before the requested target.
                values
                    .iter()
                    .copied()
                    .filter(|snapshot| {
                        snapshot.timestamp > event_time
                            && snapshot.timestamp <= target
                            && snapshot.timestamp > target - Duration::minutes(1)
                            && snapshot.price.is_finite()
                            && snapshot.price > 0.0
                    })
                    .max_by_key(|snapshot| snapshot.timestamp)
                    .map(|snapshot| {
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
    fn missing_pre_release_sample_never_uses_post_release_price() {
        let time = Utc.with_ymd_and_hms(2026, 9, 10, 12, 30, 0).unwrap();
        let values = vec![snapshot(1, 30, 100.0), snapshot(1, 32, 120.0)];
        assert!(calculate_reactions(1, time, &values).is_empty());
    }

    #[test]
    fn missing_horizon_sample_never_uses_a_later_price() {
        let time = Utc.with_ymd_and_hms(2026, 9, 10, 12, 30, 0).unwrap();
        let values = vec![snapshot(1, 29, 100.0), snapshot(1, 32, 120.0)];
        let result = calculate_reactions(1, time, &values).pop().unwrap();
        assert_eq!(result.change_1m, None);
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
