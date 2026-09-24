use serde::Serialize;

use crate::model::EconomicEvent;

/// Localized, deterministic explanation for an economic event. Common indicators use a
/// specific template; unknown indicators fall back to their calendar category. No model call is
/// made for this content, so opening a detail page does not consume tokens.
#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct EventDescription {
    pub en: String,
    pub zh_cn: String,
    pub zh_tw: String,
}

pub fn for_event(event: &EconomicEvent) -> EventDescription {
    let name = normalize(&event.event);
    let category = normalize(&event.category);
    if contains_any(
        &name,
        &["nonfarmpayroll", "nonfarmemployment", "payrolls", "nfp"],
    ) {
        return description(
            "Measures the monthly change in nonfarm employment. It is a key gauge of labor-market strength and Federal Reserve policy expectations.",
            "衡量美国非农部门就业人数的月度变化，是判断劳动力市场强弱和美联储政策预期的重要指标。",
            "衡量美國非農部門就業人數的月度變化，是判斷勞動力市場強弱與聯準會政策預期的重要指標。",
        );
    }
    if contains_any(
        &name,
        &["consumerprice", "cpi", "inflationrate", "inflation"],
    ) {
        return description(
            "Measures changes in consumer prices and inflation pressure. It is closely watched for its implications for purchasing power and interest-rate expectations.",
            "衡量消费价格和通胀压力的变化，常用于判断购买力变化以及利率政策预期。",
            "衡量消費價格與通膨壓力的變化，常用於判斷購買力變化以及利率政策預期。",
        );
    }
    if contains_any(&name, &["pce", "personalconsumptionexpenditure"]) {
        return description(
            "Tracks prices paid by consumers and is an important inflation measure for the Federal Reserve. The core reading excludes volatile food and energy components.",
            "追踪消费者支付的价格，是美联储重点关注的通胀指标；核心数据通常剔除波动较大的食品和能源项目。",
            "追蹤消費者支付的價格，是聯準會重點關注的通膨指標；核心數據通常剔除波動較大的食品與能源項目。",
        );
    }
    if contains_any(
        &name,
        &[
            "unemployment",
            "joblessclaim",
            "initialclaim",
            "continuingclaim",
        ],
    ) {
        return description(
            "Describes labor-market slack through unemployment or new jobless claims. A stronger reading can affect expectations for growth and monetary policy.",
            "通过失业率或新增失业救济申请反映劳动力市场的闲置程度，并影响增长和货币政策预期。",
            "透過失業率或新增失業救濟申請反映勞動力市場的閒置程度，並影響成長與貨幣政策預期。",
        );
    }
    if contains_any(&name, &["averagehourlyearnings", "wage", "paygrowth"]) {
        return description(
            "Measures wage growth and labor-cost pressure. It helps assess household income, services inflation and the likely path of monetary policy.",
            "衡量工资增长和劳动力成本压力，有助于判断居民收入、服务业通胀和货币政策路径。",
            "衡量薪資成長與勞動力成本壓力，有助於判斷居民收入、服務業通膨與貨幣政策路徑。",
        );
    }
    if contains_any(&name, &["gdp", "grossdomesticproduct", "economicgrowth"])
        || category.contains("gdp")
    {
        return description(
            "Measures the pace of economic output and growth. It helps evaluate the business cycle and the outlook for monetary policy and risk assets.",
            "衡量经济产出和增长速度，有助于评估经济周期、货币政策前景及风险资产表现。",
            "衡量經濟產出與成長速度，有助於評估經濟週期、貨幣政策前景及風險資產表現。",
        );
    }
    if contains_any(
        &name,
        &[
            "pmi",
            "ismmanufacturing",
            "ismservices",
            "ismnonmanufacturing",
        ],
    ) {
        return description(
            "Surveys business activity and forward-looking demand conditions. Readings above or below 50 generally indicate expansion or contraction.",
            "调查企业活动和前瞻性需求状况，通常以 50 为界判断扩张或收缩。",
            "調查企業活動與前瞻性需求狀況，通常以 50 為界判斷擴張或收縮。",
        );
    }
    if contains_any(
        &name,
        &[
            "fomc",
            "federalfund",
            "interestratedecision",
            "bankrate",
            "centralbankrate",
        ],
    ) || category.contains("interestrate")
    {
        return description(
            "Reports a central-bank interest-rate decision or policy signal. Markets focus on the decision, guidance and implications for yields, currencies and risk assets.",
            "反映央行利率决定或政策信号，市场重点关注决定、指引及其对收益率、汇率和风险资产的影响。",
            "反映央行利率決定或政策訊號，市場重點關注決定、指引及其對收益率、匯率與風險資產的影響。",
        );
    }
    if contains_any(&name, &["retailsales", "retailtrade"]) {
        return description(
            "Measures consumer spending through retail activity. It provides a timely read on household demand and economic momentum.",
            "通过零售活动衡量消费者支出，是观察居民需求和经济动能的及时指标。",
            "透過零售活動衡量消費者支出，是觀察居民需求與經濟動能的及時指標。",
        );
    }
    if contains_any(
        &name,
        &[
            "housingstart",
            "buildingpermit",
            "buildingapproval",
            "home",
            "houseprice",
        ],
    ) || category.contains("housing")
    {
        return description(
            "Tracks housing construction, approvals or prices. It reflects borrowing conditions, household demand and the health of the property cycle.",
            "跟踪住房建设、许可或价格，反映融资条件、居民需求和房地产周期状况。",
            "追蹤住房建設、許可或價格，反映融資條件、居民需求與房地產週期狀況。",
        );
    }
    if contains_any(
        &name,
        &["tradebalance", "balanceoftrade", "exports", "imports"],
    ) || category.contains("trade")
    {
        return description(
            "Measures cross-border trade flows and the trade balance. It provides information about external demand, domestic demand and currency-related flows.",
            "衡量跨境贸易流量和贸易差额，有助于观察外部需求、国内需求及汇率相关资金流。",
            "衡量跨境貿易流量與貿易差額，有助於觀察外部需求、國內需求及匯率相關資金流。",
        );
    }
    if contains_any(
        &name,
        &[
            "crudeoil",
            "crudeoilstocks",
            "crudeoilinventory",
            "oilinventory",
            "eia",
        ],
    ) || category.contains("energy")
    {
        return description(
            "Tracks energy inventories or production conditions. It is primarily used to assess the near-term balance between oil and energy supply and demand.",
            "跟踪能源库存或生产状况，主要用于判断原油及能源市场短期供需平衡。",
            "追蹤能源庫存或生產狀況，主要用於判斷原油與能源市場短期供需平衡。",
        );
    }
    if category.contains("employment") {
        return description(
            "Provides information about labor-market conditions, employment and household income. The market reaction depends on the surprise relative to expectations.",
            "反映劳动力市场、就业和居民收入状况，市场反应取决于数据相对预期的偏离程度。",
            "反映勞動力市場、就業與居民收入狀況，市場反應取決於數據相對預期的偏離程度。",
        );
    }
    if category.contains("inflation") {
        return description(
            "Provides information about price pressure and inflation. The market reaction depends on the surprise and its implications for monetary policy.",
            "反映价格压力和通胀状况，市场反应取决于数据意外及其对货币政策的影响。",
            "反映價格壓力與通膨狀況，市場反應取決於數據意外及其對貨幣政策的影響。",
        );
    }
    description(
        "Summarizes a scheduled macroeconomic release. Compare the published value with expectations and interpret it together with the indicator's economic context.",
        "这是一个宏观经济数据发布事件，应结合实际值、市场预期和指标的经济含义进行判断。",
        "這是一個總體經濟數據發布事件，應結合實際值、市場預期與指標的經濟含義進行判斷。",
    )
}

fn description(en: &str, zh_cn: &str, zh_tw: &str) -> EventDescription {
    EventDescription {
        en: en.into(),
        zh_cn: zh_cn.into(),
        zh_tw: zh_tw.into(),
    }
}

fn contains_any(value: &str, needles: &[&str]) -> bool {
    needles.iter().any(|needle| value.contains(needle))
}

fn normalize(value: &str) -> String {
    value
        .chars()
        .filter(|character| character.is_alphanumeric())
        .flat_map(char::to_lowercase)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{EconomicEvent, EventStatus};
    use chrono::Utc;

    fn event(name: &str, category: &str) -> EconomicEvent {
        EconomicEvent {
            id: 1,
            provider: "test".into(),
            provider_id: "1".into(),
            release_group_id: None,
            country: "United States".into(),
            currency: Some("USD".into()),
            category: category.into(),
            event: name.into(),
            event_zh_cn: None,
            event_zh_tw: None,
            event_time: Utc::now(),
            importance: 3,
            actual: None,
            previous: None,
            consensus: None,
            forecast: None,
            unit: None,
            status: EventStatus::Scheduled,
            time_exact: true,
        }
    }

    #[test]
    fn common_indicators_get_specific_descriptions() {
        let payrolls = for_event(&event("Nonfarm Payrolls", "employment"));
        assert!(payrolls.zh_cn.contains("非农"));
        let cpi = for_event(&event("CPI YoY", "inflation"));
        assert!(cpi.zh_cn.contains("通胀"));
        assert_ne!(payrolls.zh_cn, cpi.zh_cn);
    }

    #[test]
    fn unknown_events_get_category_or_generic_description() {
        let description = for_event(&event("Unusual Indicator", "employment"));
        assert!(description.en.contains("labor-market"));
    }
}
