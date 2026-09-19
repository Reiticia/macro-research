//! Curated Chinese names for the most common calendar titles.
//!
//! Seeded into `event_name_translation` on every startup so the backend can serve translated
//! event names with zero model configuration: `[translation]` may stay disabled, readers see
//! Chinese for the indicators covered here, and a configured relay or an administrator-approved
//! correction still wins (`INSERT OR IGNORE` never overwrites existing rows; the companion
//! row backfill only touches events whose translation columns are NULL).
//!
//! Display-only data: a wrong entry can never corrupt observations or merges. Keep the Kotlin
//! copy (`android/app/src/main/java/com/macroresearch/data/EventNameDictionary.kt`) in sync.

/// `(TradingView/ForexFactory title, zh-CN, zh-TW)`. Titles must match the feeds exactly.
pub const BUILTIN_TRANSLATIONS: &[(&str, &str, &str)] = &[
    // Inflation
    ("CPI m/m", "消费者物价指数月率", "消費者物價指數月率"),
    ("CPI y/y", "消费者物价指数年率", "消費者物價指數年率"),
    (
        "Core CPI m/m",
        "核心消费者物价指数月率",
        "核心消費者物價指數月率",
    ),
    (
        "Core CPI y/y",
        "核心消费者物价指数年率",
        "核心消費者物價指數年率",
    ),
    ("Inflation Rate MoM", "通货膨胀率月率", "通貨膨脹率月率"),
    ("Inflation Rate YoY", "通货膨胀率年率", "通貨膨脹率年率"),
    (
        "Core Inflation Rate MoM",
        "核心通货膨胀率月率",
        "核心通貨膨脹率月率",
    ),
    (
        "Core Inflation Rate YoY",
        "核心通货膨胀率年率",
        "核心通貨膨脹率年率",
    ),
    (
        "CPI Flash Estimate y/y",
        "消费者物价指数初值年率",
        "消費者物價指數初值年率",
    ),
    ("PPI m/m", "生产者物价指数月率", "生產者物價指數月率"),
    ("PPI y/y", "生产者物价指数年率", "生產者物價指數年率"),
    (
        "Core PPI m/m",
        "核心生产者物价指数月率",
        "核心生產者物價指數月率",
    ),
    (
        "Core PPI y/y",
        "核心生产者物价指数年率",
        "核心生產者物價指數年率",
    ),
    ("PCE Price Index m/m", "PCE物价指数月率", "PCE物價指數月率"),
    ("PCE Price Index y/y", "PCE物价指数年率", "PCE物價指數年率"),
    (
        "Core PCE Price Index m/m",
        "核心PCE物价指数月率",
        "核心PCE物價指數月率",
    ),
    (
        "Core PCE Price Index y/y",
        "核心PCE物价指数年率",
        "核心PCE物價指數年率",
    ),
    // Employment
    ("Nonfarm Payrolls", "非农就业人数", "非農就業人數"),
    (
        "Non-Farm Employment Change",
        "非农就业人数变动",
        "非農就業人數變動",
    ),
    ("Unemployment Rate", "失业率", "失業率"),
    (
        "Unemployment Claims",
        "初请失业金人数",
        "初次申請失業金人數",
    ),
    (
        "Initial Jobless Claims",
        "初请失业金人数",
        "初次申請失業金人數",
    ),
    (
        "Continuing Jobless Claims",
        "续请失业金人数",
        "續請失業金人數",
    ),
    (
        "Average Hourly Earnings m/m",
        "平均时薪月率",
        "平均時薪月率",
    ),
    ("ADP Employment Change", "ADP就业人数", "ADP就業人數"),
    (
        "ADP Weekly Employment Change",
        "ADP周度就业变动",
        "ADP週度就業變動",
    ),
    ("Employment Change", "就业人数变动", "就業人數變動"),
    (
        "Claimant Count Change",
        "申领失业金人数变动",
        "申領失業金人數變動",
    ),
    (
        "Average Earnings Index 3m/y",
        "平均收入指数3个月年率",
        "平均所得指數3個月年率",
    ),
    ("Job Openings", "职位空缺", "職位空缺"),
    ("JOLTS Job Openings", "JOLTS职位空缺", "JOLTS職位空缺"),
    // Growth, consumption and trade
    ("GDP m/m", "国内生产总值月率", "國內生產總值月率"),
    ("GDP q/q", "国内生产总值季率", "國內生產總值季率"),
    ("GDP y/y", "国内生产总值年率", "國內生產總值年率"),
    ("GDP QoQ", "国内生产总值季率", "國內生產總值季率"),
    ("GDP YoY", "国内生产总值年率", "國內生產總值年率"),
    ("GDP Annualized", "国内生产总值年化", "國內生產總值年化"),
    ("Industrial Production m/m", "工业产出月率", "工業產出月率"),
    ("Capacity Utilization Rate", "产能利用率", "產能利用率"),
    ("Factory Orders m/m", "工厂订单月率", "工廠訂單月率"),
    (
        "Durable Goods Orders m/m",
        "耐用品订单月率",
        "耐用品訂單月率",
    ),
    (
        "Core Durable Goods Orders m/m",
        "核心耐用品订单月率",
        "核心耐用品訂單月率",
    ),
    ("Retail Sales m/m", "零售销售月率", "零售銷售月率"),
    ("Retail Sales MoM", "零售销售月率", "零售銷售月率"),
    (
        "Core Retail Sales m/m",
        "核心零售销售月率",
        "核心零售銷售月率",
    ),
    (
        "Core Retail Sales MoM",
        "核心零售销售月率",
        "核心零售銷售月率",
    ),
    (
        "Retail Sales Control Group",
        "控制组零售销售",
        "控制組零售銷售",
    ),
    (
        "Retail Sales Ex Autos m/m",
        "剔除汽车零售销售月率",
        "剔除汽車零售銷售月率",
    ),
    ("Wholesale Inventories m/m", "批发库存月率", "批發庫存月率"),
    ("Wholesale Inventories MoM", "批发库存月率", "批發庫存月率"),
    ("Business Inventories m/m", "商业库存月率", "商業庫存月率"),
    ("Personal Spending m/m", "个人支出月率", "個人支出月率"),
    ("Personal Income m/m", "个人收入月率", "個人收入月率"),
    ("Consumer Credit", "消费信贷", "消費信貸"),
    ("Consumer Credit MoM", "消费信贷月率", "消費信貸月率"),
    ("Consumer Credit Change", "消费信贷变动", "消費信貸變動"),
    ("Trade Balance", "贸易帐", "貿易帳"),
    ("Current Account", "经常帐", "經常帳"),
    ("Government Budget Balance", "政府预算", "政府預算"),
    ("Budget Balance", "财政预算", "財政預算"),
    // Surveys and PMIs
    ("ISM Manufacturing PMI", "ISM制造业PMI", "ISM製造業PMI"),
    ("ISM Services PMI", "ISM服务业PMI", "ISM服務業PMI"),
    (
        "ISM Non-Manufacturing PMI",
        "ISM非制造业PMI",
        "ISM非製造業PMI",
    ),
    ("Manufacturing PMI", "制造业PMI", "製造業PMI"),
    ("Services PMI", "服务业PMI", "服務業PMI"),
    ("Composite PMI", "综合PMI", "綜合PMI"),
    ("Caixin Manufacturing PMI", "财新制造业PMI", "財新製造業PMI"),
    ("Caixin Services PMI", "财新服务业PMI", "財新服務業PMI"),
    (
        "CB Consumer Confidence",
        "谘商会消费者信心",
        "諮商會消費者信心",
    ),
    ("Consumer Confidence", "消费者信心指数", "消費者信心指數"),
    (
        "Michigan Consumer Sentiment Prel",
        "密歇根大学消费者信心初值",
        "密西根大學消費者信心初值",
    ),
    (
        "Michigan Consumer Sentiment Final",
        "密歇根大学消费者信心终值",
        "密西根大學消費者信心終值",
    ),
    (
        "Prelim UoM Consumer Sentiment",
        "密歇根大学消费者信心初值",
        "密西根大學消費者信心初值",
    ),
    (
        "Final UoM Consumer Sentiment",
        "密歇根大学消费者信心终值",
        "密西根大學消費者信心終值",
    ),
    (
        "Michigan Inflation Expectations Prel",
        "密歇根大学通胀预期初值",
        "密西根大學通膨預期初值",
    ),
    ("Inflation Expectations", "通胀预期", "通膨預期"),
    (
        "ZEW Economic Sentiment",
        "ZEW经济景气指数",
        "ZEW經濟景氣指數",
    ),
    (
        "German ZEW Economic Sentiment",
        "德国ZEW经济景气指数",
        "德國ZEW經濟景氣指數",
    ),
    ("Ifo Business Climate", "Ifo商业景气指数", "Ifo商業景氣指數"),
    (
        "GfK Consumer Confidence",
        "GfK消费者信心指数",
        "GfK消費者信心指數",
    ),
    (
        "Philadelphia Fed Manufacturing Index",
        "费城联储制造业指数",
        "費城聯儲製造業指數",
    ),
    (
        "Empire State Manufacturing Index",
        "纽约联储制造业指数",
        "紐約聯儲製造業指數",
    ),
    ("Chicago PMI", "芝加哥PMI", "芝加哥PMI"),
    (
        "NAHB Housing Market Index",
        "NAHB房产市场指数",
        "NAHB房產市場指數",
    ),
    // Housing
    ("Building Permits", "建筑许可", "建築許可"),
    ("Housing Starts", "新屋开工", "新屋開工"),
    ("New Home Sales", "新屋销售", "新屋銷售"),
    ("Existing Home Sales", "成屋销售", "成屋銷售"),
    // Energy inventories
    ("Crude Oil Inventories", "原油库存", "原油庫存"),
    (
        "EIA Crude Oil Stocks Change",
        "EIA原油库存变动",
        "EIA原油庫存變動",
    ),
    ("Natural Gas Storage", "天然气库存", "天然氣庫存"),
    (
        "EIA Natural Gas Stocks Change",
        "EIA天然气库存变动",
        "EIA天然氣庫存變動",
    ),
    // Central banks
    ("Federal Funds Rate", "联邦基金利率", "聯邦基金利率"),
    (
        "Fed Interest Rate Decision",
        "美联储利率决议",
        "美聯儲利率決議",
    ),
    ("FOMC Statement", "FOMC声明", "FOMC聲明"),
    ("FOMC Press Conference", "FOMC新闻发布会", "FOMC新聞發布會"),
    (
        "ECB Interest Rate Decision",
        "欧洲央行利率决议",
        "歐洲央行利率決議",
    ),
    ("Main Refinancing Rate", "主要再融资利率", "主要再融資利率"),
    ("ECB Deposit Facility Rate", "存款便利利率", "存款便利利率"),
    ("Monetary Policy Statement", "货币政策声明", "貨幣政策聲明"),
    (
        "ECB Press Conference",
        "欧洲央行新闻发布会",
        "歐洲央行新聞發布會",
    ),
    (
        "BoE Interest Rate Decision",
        "英国央行利率决议",
        "英國央行利率決議",
    ),
    ("Bank Rate", "银行利率", "銀行利率"),
    (
        "BoJ Interest Rate Decision",
        "日本央行利率决议",
        "日本央行利率決議",
    ),
    ("BoJ Policy Rate", "日本央行政策利率", "日本央行政策利率"),
    (
        "BoC Interest Rate Decision",
        "加拿大央行利率决议",
        "加拿大央行利率決議",
    ),
    ("Overnight Rate", "隔夜利率", "隔夜利率"),
    (
        "RBA Interest Rate Decision",
        "澳洲央行利率决议",
        "澳洲央行利率決議",
    ),
    ("Cash Rate", "现金利率", "現金利率"),
    (
        "RBNZ Interest Rate Decision",
        "新西兰央行利率决议",
        "紐西蘭央行利率決議",
    ),
    ("Official Cash Rate", "官方现金利率", "官方現金利率"),
    (
        "1-Year Loan Prime Rate",
        "1年期贷款市场报价利率",
        "1年期貸款市場報價利率",
    ),
    (
        "5-Year Loan Prime Rate",
        "5年期贷款市场报价利率",
        "5年期貸款市場報價利率",
    ),
];

/// The dictionary shaped for `EventRepository::save_event_name_translations`.
pub fn rows() -> Vec<(String, String, String)> {
    BUILTIN_TRANSLATIONS
        .iter()
        .map(|(source, zh_cn, zh_tw)| {
            (
                (*source).to_owned(),
                (*zh_cn).to_owned(),
                (*zh_tw).to_owned(),
            )
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn builtin_entries_are_unique_and_well_formed() {
        let mut seen = HashSet::new();
        for (source, zh_cn, zh_tw) in BUILTIN_TRANSLATIONS {
            assert!(!source.is_empty() && !zh_cn.is_empty() && !zh_tw.is_empty());
            assert!(seen.insert(*source), "duplicate dictionary title: {source}");
        }
    }
}
