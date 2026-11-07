//! Closed US title mappings reviewed against the 2026-11-07 duplicate audit.
//! This is identity evidence, NOT permission to copy values across different units.
//! Missing-phase titles (PMI, durable goods, productivity, Michigan) are deliberately
//! excluded; Michigan continues to use the stricter period/source rules.
use crate::model::EconomicEvent;

pub(super) fn key(event: &EconomicEvent) -> Option<(&'static str, bool)> {
    if event.country != "United States" {
        return None;
    }
    let name = super::parse::normalized_event_name(&event.event);
    let key = match (event.provider.as_str(), name.as_str()) {
        ("trading_view", "nyempirestatemanufacturingindex")
        | ("forex_factory", "empirestatemanufacturingindex") => ("empire", true),
        ("trading_view", "philadelphiafedmanufacturingindex")
        | ("forex_factory", "phillyfedmanufacturingindex") => ("philly", true),
        ("trading_view", "retailsalesexautosmom") | ("forex_factory", "coreretailsalesmm") => {
            ("retail.exautos.mom", true)
        }
        ("trading_view", "coreppimom") | ("forex_factory", "coreppimm") => ("ppi.core.mom", true),
        ("trading_view", "ppimom") | ("forex_factory", "ppimm") => ("ppi.mom", true),
        ("trading_view", "retailsalesmom") | ("forex_factory", "retailsalesmm") => {
            ("retail.mom", true)
        }
        ("trading_view", "businessinventoriesmom") | ("forex_factory", "businessinventoriesmm") => {
            ("business.inventories.mom", true)
        }
        ("trading_view", "corepcepriceindexmom") | ("forex_factory", "corepcepriceindexmm") => {
            ("pce.core.mom", true)
        }
        ("trading_view", "personalincomemom") | ("forex_factory", "personalincomemm") => {
            ("income.mom", true)
        }
        ("trading_view", "personalspendingmom") | ("forex_factory", "personalspendingmm") => {
            ("spending.mom", true)
        }
        ("trading_view", "employmentcostindexqoq") | ("forex_factory", "employmentcostindexqq") => {
            ("employment.cost.qoq", false)
        }
        ("trading_view", "wholesaleinventoriesmomadv")
        | ("forex_factory", "prelimwholesaleinventoriesmm") => ("wholesale.advance.mom", true),
        ("trading_view", "gdpgrowthrateqoqadv") | ("forex_factory", "advancegdpqq") => {
            ("gdp.advance.qoq", false)
        }
        ("trading_view", "gdppriceindexqoqadv") | ("forex_factory", "advancegdppriceindexqq") => {
            ("gdp.price.advance.qoq", false)
        }
        ("trading_view", "fedinterestratedecision") | ("forex_factory", "federalfundsrate") => {
            ("fed.rate", false)
        }
        ("trading_view", "fedpressconference") | ("forex_factory", "fomcpressconference") => {
            ("fed.press", false)
        }
        ("trading_view", "adpemploymentchange") | ("biquote", "adpnonfarmemploymentchange") => {
            ("adp.monthly", true)
        }
        ("trading_view", "balanceoftrade") | ("biquote", "tradebalance") => ("trade.balance", true),
        ("trading_view", "ismservicesbusinessactivity")
        | ("biquote", "ismnonmanufacturingbusinessactivity") => ("ism.services.activity", true),
        ("trading_view", "ismservicesemployment")
        | ("biquote", "ismnonmanufacturingemployment") => ("ism.services.employment", true),
        ("trading_view", "ismservicesneworders") | ("biquote", "ismnonmanufacturingneworders") => {
            ("ism.services.orders", true)
        }
        ("trading_view", "ismservicesprices") | ("biquote", "ismnonmanufacturingpricespaid") => {
            ("ism.services.prices", true)
        }
        ("trading_view", "eiadistillatestockschange")
        | ("biquote", "eiadistillatesstockschange") => ("eia.distillates", false),
        ("trading_view", "eiarefinerycruderunschange")
        | ("biquote", "eiarefinerycrudeoildailyinputschange") => ("eia.refinery.inputs", false),
        ("trading_view", "eiarefineryutilizationchange")
        | ("biquote", "eiarefineryutilizationratechange") => ("eia.refinery.utilization", false),
        ("trading_view", "nonfarmpayrolls") | ("forex_factory", "nonfarmemploymentchange") => {
            ("payrolls.total", true)
        }
        ("trading_view", "nonfarmpayrollsprivate") | ("biquote", "privatenonfarmpayrolls") => {
            ("payrolls.private", true)
        }
        ("trading_view", "consumercreditchange") | ("biquote", "fedconsumercreditmm") => {
            ("consumer.credit", true)
        }
        ("trading_view", "fedwallerspeech") | ("forex_factory", "fomcmemberwallerspeaks") => {
            ("speech.waller", false)
        }
        ("trading_view", "fedmusalemspeech") | ("forex_factory", "fomcmembermusalemspeaks") => {
            ("speech.musalem", false)
        }
        ("trading_view", "fedbowmanspeech")
        | ("forex_factory", "fomcmemberbowmanspeaks")
        | ("biquote", "fedgovernorbowmanspeech") => ("speech.bowman", false),
        ("trading_view", "fedhammackspeech") | ("forex_factory", "fomcmemberhammackspeaks") => {
            ("speech.hammack", false)
        }
        ("trading_view", "feddalyspeech") | ("forex_factory", "fomcmemberdalyspeaks") => {
            ("speech.daly", false)
        }
        ("trading_view", "fedwilliamsspeech") | ("forex_factory", "fomcmemberwilliamsspeaks") => {
            ("speech.williams", false)
        }
        _ => return None,
    };
    Some(key)
}

pub(super) fn same_metric(left: &EconomicEvent, right: &EconomicEvent) -> bool {
    matches!((key(left), key(right)), (Some(l), Some(r)) if l == r)
}

pub(super) fn same_monthly_metric(left: &EconomicEvent, right: &EconomicEvent) -> bool {
    matches!((key(left), key(right)), (Some((l, true)), Some((r, true))) if l == r)
}
