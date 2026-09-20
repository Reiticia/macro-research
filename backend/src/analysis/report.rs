use rust_decimal::Decimal;

use crate::model::{
    Direction, EconomicEvent, ExpectedReaction, MacroSignal, MarketReaction, ReactionComparison,
};

pub fn compare(
    expected: &[ExpectedReaction],
    observed: &[MarketReaction],
) -> Vec<ReactionComparison> {
    expected
        .iter()
        .map(|expectation| {
            let change = observed
                .iter()
                .find(|reaction| reaction.symbol == expectation.symbol)
                .and_then(preferred_change);
            let conforms = change.map(|change| match expectation.direction {
                Direction::Up => change > 0.0,
                Direction::Down => change < 0.0,
                Direction::Flat => change.abs() < 0.05,
            });
            ReactionComparison {
                symbol: expectation.symbol,
                expected: expectation.direction,
                observed_change: change,
                conforms,
            }
        })
        .collect()
}

fn preferred_change(reaction: &MarketReaction) -> Option<f64> {
    reaction
        .change_5m
        .or(reaction.change_1m)
        .or(reaction.change_15m)
        .or(reaction.change_30m)
        .or(reaction.change_60m)
}

pub fn summary(
    event: &EconomicEvent,
    surprise: Option<Decimal>,
    signal: MacroSignal,
    comparisons: &[ReactionComparison],
) -> String {
    let surprise_text = surprise
        .map(|value| format!("Surprise 为 {:+}", value))
        .unwrap_or_else(|| "缺少 Actual 或 Consensus，无法计算 Surprise".to_owned());
    let compared = comparisons
        .iter()
        .filter_map(|comparison| comparison.conforms)
        .count();
    let conforming = comparisons
        .iter()
        .filter(|comparison| comparison.conforms == Some(true))
        .count();
    if compared == 0 {
        format!(
            "{}：{}；宏观信号为 {}。市场样本尚不足，理论反应与实际反应保持分离。",
            event.event, surprise_text, signal
        )
    } else {
        format!(
            "{}：{}；宏观信号为 {}。在可比较的 {} 个市场中，{} 个符合典型理论方向。",
            event.event, surprise_text, signal, compared, conforming
        )
    }
}
