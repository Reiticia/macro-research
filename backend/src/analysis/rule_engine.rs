use std::{fs, path::Path, str::FromStr};

use rust_decimal::Decimal;
use serde::Deserialize;

use crate::{
    error::AppError,
    model::{Direction, EconomicEvent, ExpectedReaction, MacroSignal, MarketSymbol},
};

#[derive(Clone, Debug, Deserialize)]
pub struct RuleEngine {
    strong_surprise_threshold: Decimal,
    #[serde(rename = "indicator")]
    indicators: Vec<IndicatorRule>,
}

#[derive(Clone, Debug, Deserialize)]
struct IndicatorRule {
    name: String,
    #[serde(default)]
    aliases: Vec<String>,
    #[allow(dead_code)]
    category: Option<String>,
    higher_than_expected: RuleSignal,
    lower_than_expected: RuleSignal,
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "snake_case")]
enum RuleSignal {
    Hawkish,
    Dovish,
    BullishOil,
    BearishOil,
    Neutral,
}

impl RuleEngine {
    pub fn from_path(path: impl AsRef<Path>) -> Result<Self, AppError> {
        let contents = fs::read_to_string(path)?;
        toml::from_str(&contents).map_err(AppError::from)
    }

    pub fn signal_for(&self, event: &EconomicEvent, surprise: Option<Decimal>) -> MacroSignal {
        let Some(surprise) = surprise else {
            return MacroSignal::Neutral;
        };
        if surprise.is_zero() {
            return MacroSignal::Neutral;
        }
        let Some(rule) = self.indicators.iter().find(|rule| rule.matches(event)) else {
            return MacroSignal::Neutral;
        };
        let base = if surprise.is_sign_positive() {
            rule.higher_than_expected
        } else {
            rule.lower_than_expected
        };
        let strong = surprise.abs() >= self.strong_surprise_threshold;
        match (base, strong) {
            (RuleSignal::Hawkish, true) => MacroSignal::StrongHawkish,
            (RuleSignal::Hawkish, false) => MacroSignal::Hawkish,
            (RuleSignal::Dovish, true) => MacroSignal::StrongDovish,
            (RuleSignal::Dovish, false) => MacroSignal::Dovish,
            (RuleSignal::BullishOil, _) => MacroSignal::BullishOil,
            (RuleSignal::BearishOil, _) => MacroSignal::BearishOil,
            (RuleSignal::Neutral, _) => MacroSignal::Neutral,
        }
    }

    pub fn expected_reactions(&self, signal: MacroSignal) -> Vec<ExpectedReaction> {
        use Direction::{Down, Flat, Up};
        use MarketSymbol::{Bitcoin, Brent, Dxy, Gold, Nasdaq100, Us2y, Us10y, Wti};
        let rationale = |text: &str| text.to_owned();
        match signal {
            MacroSignal::StrongHawkish | MacroSignal::Hawkish => vec![
                reaction(Dxy, Up, rationale("鹰派预期通常支撑美元")),
                reaction(Us2y, Up, rationale("短端收益率通常随政策利率预期上升")),
                reaction(Us10y, Up, rationale("通胀或增长预期通常推高长端收益率")),
                reaction(Gold, Down, rationale("美元和实际利率上行通常压制黄金")),
                reaction(Nasdaq100, Down, rationale("更高贴现率通常压制成长股估值")),
                reaction(Bitcoin, Down, rationale("紧缩流动性通常压制风险资产")),
            ],
            MacroSignal::StrongDovish | MacroSignal::Dovish => vec![
                reaction(Dxy, Down, rationale("鸽派预期通常压制美元")),
                reaction(Us2y, Down, rationale("短端收益率通常随政策利率预期下降")),
                reaction(Us10y, Down, rationale("宽松预期通常压低长端收益率")),
                reaction(Gold, Up, rationale("美元和实际利率回落通常支撑黄金")),
                reaction(Nasdaq100, Up, rationale("更低贴现率通常支撑成长股估值")),
                reaction(Bitcoin, Up, rationale("宽松流动性通常支撑风险资产")),
            ],
            MacroSignal::BullishOil => vec![
                reaction(Wti, Up, rationale("库存低于预期通常利多原油")),
                reaction(Brent, Up, rationale("库存低于预期通常利多原油")),
            ],
            MacroSignal::BearishOil => vec![
                reaction(Wti, Down, rationale("库存高于预期通常利空原油")),
                reaction(Brent, Down, rationale("库存高于预期通常利空原油")),
            ],
            MacroSignal::Neutral => vec![reaction(
                Dxy,
                Flat,
                rationale("缺少有效 Surprise 或指标规则"),
            )],
        }
    }
}

impl IndicatorRule {
    fn matches(&self, event: &EconomicEvent) -> bool {
        let event_name = normalize(&event.event);
        std::iter::once(&self.name)
            .chain(self.aliases.iter())
            .map(|name| normalize(name))
            .any(|name| {
                event_name == name || event_name.contains(&name) || name.contains(&event_name)
            })
    }
}

fn normalize(value: &str) -> String {
    value
        .chars()
        .filter(|character| character.is_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect()
}

fn reaction(symbol: MarketSymbol, direction: Direction, rationale: String) -> ExpectedReaction {
    ExpectedReaction {
        symbol,
        direction,
        rationale,
    }
}

impl FromStr for RuleSignal {
    type Err = String;
    fn from_str(value: &str) -> Result<Self, Self::Err> {
        match value {
            "hawkish" => Ok(Self::Hawkish),
            "dovish" => Ok(Self::Dovish),
            "bullish_oil" => Ok(Self::BullishOil),
            "bearish_oil" => Ok(Self::BearishOil),
            "neutral" => Ok(Self::Neutral),
            _ => Err(format!("unknown rule signal: {value}")),
        }
    }
}

#[cfg(test)]
mod tests {
    use chrono::Utc;

    use super::*;
    use crate::model::EventStatus;

    fn engine() -> RuleEngine {
        toml::from_str(
            r#"
          strong_surprise_threshold = "0.2"
          [[indicator]]
          name = "Unemployment Rate"
          aliases = []
          higher_than_expected = "dovish"
          lower_than_expected = "hawkish"
        "#,
        )
        .unwrap()
    }

    #[test]
    fn applies_indicator_specific_direction() {
        let event = EconomicEvent {
            id: 1,
            provider: "test".into(),
            provider_id: "1".into(),
            release_group_id: None,
            country: "US".into(),
            currency: Some("USD".into()),
            category: "employment".into(),
            event: "US Unemployment Rate".into(),
            event_zh_cn: None,
            event_zh_tw: None,
            event_time: Utc::now(),
            importance: 3,
            actual: Some(Decimal::new(43, 1)),
            previous: None,
            consensus: Some(Decimal::new(40, 1)),
            forecast: None,
            unit: Some("%".into()),
            status: EventStatus::Released,
            time_exact: true,
        };
        assert_eq!(
            engine().signal_for(&event, Some(Decimal::new(3, 1))),
            MacroSignal::StrongDovish
        );
    }
}
