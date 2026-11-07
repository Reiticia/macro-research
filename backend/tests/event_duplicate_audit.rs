//! Regression cases from event-duplicates-20261107, without production IDs or DB access.
use chrono::{DateTime, Utc};
use market_event_analyzer::{
    calendar::parse::same_occurrence,
    model::{EconomicEvent, EventStatus},
    repository::EventRepository,
};
use rust_decimal::Decimal;
use sqlx::sqlite::SqlitePoolOptions;

fn event(provider: &str, name: &str) -> EconomicEvent {
    EconomicEvent {
        id: 0,
        provider: provider.into(),
        provider_id: format!("{provider}:{name}"),
        release_group_id: None,
        release_metadata: Default::default(),
        country: "United States".into(),
        currency: Some("USD".into()),
        category: name.into(),
        event: name.into(),
        event_zh_cn: None,
        event_zh_tw: None,
        event_time: DateTime::parse_from_rfc3339("2026-11-04T13:30:00Z")
            .unwrap()
            .with_timezone(&Utc),
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

async fn repository() -> EventRepository {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    EventRepository::new(pool)
}

const PAIRS: &[(&str, &str, &str)] = &[
    (
        "Fed Waller Speech",
        "forex_factory",
        "FOMC Member Waller Speaks",
    ),
    (
        "Fed Musalem Speech",
        "forex_factory",
        "FOMC Member Musalem Speaks",
    ),
    (
        "Fed Bowman Speech",
        "forex_factory",
        "FOMC Member Bowman Speaks",
    ),
    (
        "Fed Hammack Speech",
        "forex_factory",
        "FOMC Member Hammack Speaks",
    ),
    (
        "Fed Daly Speech",
        "forex_factory",
        "FOMC Member Daly Speaks",
    ),
    (
        "Fed Williams Speech",
        "forex_factory",
        "FOMC Member Williams Speaks",
    ),
    ("Fed Bowman Speech", "biquote", "Fed Governor Bowman Speech"),
    ("Core PPI MoM", "forex_factory", "Core PPI m/m"),
    ("PPI MoM", "forex_factory", "PPI m/m"),
    (
        "NY Empire State Manufacturing Index",
        "forex_factory",
        "Empire State Manufacturing Index",
    ),
    (
        "Philadelphia Fed Manufacturing Index",
        "forex_factory",
        "Philly Fed Manufacturing Index",
    ),
    (
        "Retail Sales Ex Autos MoM",
        "forex_factory",
        "Core Retail Sales m/m",
    ),
    ("Retail Sales MoM", "forex_factory", "Retail Sales m/m"),
    (
        "Business Inventories MoM",
        "forex_factory",
        "Business Inventories m/m",
    ),
    (
        "Wholesale Inventories MoM Adv",
        "forex_factory",
        "Prelim Wholesale Inventories m/m",
    ),
    (
        "Fed Interest Rate Decision",
        "forex_factory",
        "Federal Funds Rate",
    ),
    (
        "Fed Press Conference",
        "forex_factory",
        "FOMC Press Conference",
    ),
    (
        "Core PCE Price Index MoM",
        "forex_factory",
        "Core PCE Price Index m/m",
    ),
    (
        "GDP Growth Rate QoQ Adv",
        "forex_factory",
        "Advance GDP q/q",
    ),
    (
        "GDP Price Index QoQ Adv",
        "forex_factory",
        "Advance GDP Price Index q/q",
    ),
    (
        "Personal Income MoM",
        "forex_factory",
        "Personal Income m/m",
    ),
    (
        "Personal Spending MoM",
        "forex_factory",
        "Personal Spending m/m",
    ),
    (
        "Employment Cost Index QoQ",
        "forex_factory",
        "Employment Cost Index q/q",
    ),
    (
        "ADP Employment Change",
        "biquote",
        "ADP Nonfarm Employment Change",
    ),
    ("Balance of Trade", "biquote", "Trade Balance"),
    (
        "ISM Services Business Activity",
        "biquote",
        "ISM Non-Manufacturing Business Activity",
    ),
    (
        "ISM Services Employment",
        "biquote",
        "ISM Non-Manufacturing Employment",
    ),
    (
        "ISM Services New Orders",
        "biquote",
        "ISM Non-Manufacturing New Orders",
    ),
    (
        "ISM Services Prices",
        "biquote",
        "ISM Non-Manufacturing Prices Paid",
    ),
    (
        "EIA Distillate Stocks Change",
        "biquote",
        "EIA Distillates Stocks Change",
    ),
    (
        "EIA Refinery Crude Runs Change",
        "biquote",
        "EIA Refinery Crude Oil Daily Inputs Change",
    ),
    (
        "EIA Refinery Utilization Change",
        "biquote",
        "EIA Refinery Utilization Rate Change",
    ),
    (
        "Non Farm Payrolls",
        "forex_factory",
        "Non-Farm Employment Change",
    ),
    (
        "Nonfarm Payrolls Private",
        "biquote",
        "Private Nonfarm Payrolls",
    ),
    (
        "Consumer Credit Change",
        "biquote",
        "Fed Consumer Credit m/m",
    ),
];

#[tokio::test]
async fn audited_aliases_reuse_one_id_in_both_orders_and_preserve_provider_lookups() {
    for &(name, provider, alias) in PAIRS {
        for reverse in [false, true] {
            let repo = repository().await;
            let mut pair = [event("trading_view", name), event(provider, alias)];
            if reverse {
                pair.reverse();
            }
            let ids = repo.save_events(&pair).await.unwrap();
            assert_eq!(ids.len(), 2, "{name} / {alias}");
            assert_eq!(ids[0], ids[1], "{name} / {alias}");
            for row in pair {
                assert_eq!(
                    repo.find_provider_event(&row.provider, &row.provider_id)
                        .await
                        .unwrap()
                        .unwrap()
                        .id,
                    ids[0]
                );
            }
        }
    }
}

#[test]
fn explicit_period_phase_and_metric_boundaries_are_not_relaxed() {
    for (a, b) in [
        ("S&P Global Services PMI Final", "S&P Global Services PMI"),
        ("S&P Global Composite PMI Final", "S&P Global Composite PMI"),
        (
            "Jobless Claims 4-week Average",
            "Initial Jobless Claims 4-Week Average",
        ),
        (
            "Baker Hughes Oil Rig Count",
            "Baker Hughes US Oil Rig Count",
        ),
        ("Nonfarm Productivity QoQ Prel", "Nonfarm Productivity q/q"),
        (
            "Durable Goods Orders Ex Transp MoM Final",
            "Durable Goods Orders Excluding Transportation",
        ),
        (
            "Michigan Consumer Sentiment Prel",
            "Michigan Consumer Sentiment",
        ),
        (
            "ISM Services Employment",
            "ISM Non-Manufacturing New Orders",
        ),
        ("Non Farm Payrolls", "Private Nonfarm Payrolls"),
        ("Fed Interest Rate Decision", "FOMC Statement"),
        ("Core PPI MoM", "PPI m/m"),
    ] {
        assert!(
            !same_occurrence(&event("trading_view", a), &event("biquote", b)),
            "{a} / {b}"
        );
    }
    let mut a = event("trading_view", "Consumer Credit Change");
    let mut b = event("biquote", "Fed Consumer Credit m/m");
    a.release_metadata.period = Some("Sep. 2026".into());
    b.release_metadata.period = Some("2026-09-01T00:00:00Z".into());
    assert!(same_occurrence(&a, &b));
    b.release_metadata.period = Some("2026-10-01T00:00:00Z".into());
    assert!(!same_occurrence(&a, &b));
    a = event("trading_view", "EIA Distillate Stocks Change");
    b = event("biquote", "EIA Distillates Stocks Change");
    a.release_metadata.period = Some("2026-10-01".into());
    b.release_metadata.period = Some("2026-10-08".into());
    assert!(!same_occurrence(&a, &b));
    for country in ["Canada", "United Kingdom"] {
        a = event("trading_view", "Retail Sales Ex Autos MoM");
        b = event("forex_factory", "Core Retail Sales m/m");
        a.country = country.into();
        b.country = country.into();
        assert!(!same_occurrence(&a, &b));
    }
}

#[tokio::test]
async fn new_identity_does_not_authorize_incompatible_value_conversion() {
    let repo = repository().await;
    let mut primary = event("trading_view", "EIA Distillate Stocks Change");
    primary.unit = Some("M".into());
    let id = repo.save_events(&[primary]).await.unwrap()[0];
    let mut secondary = event("biquote", "EIA Distillates Stocks Change");
    secondary.unit = Some("M barrels".into());
    secondary.actual = Some(Decimal::from(2));
    repo.save_events(&[secondary]).await.unwrap();
    assert!(repo.get(id).await.unwrap().actual.is_none());
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM economic_event")
        .fetch_one(repo.pool())
        .await
        .unwrap();
    assert_eq!(count, 1);
}

#[tokio::test]
async fn offset_timestamp_and_rotated_tradingview_ids_do_not_insert_a_new_event() {
    let repo = repository().await;
    let mut old = event("trading_view", "ADP Employment Change");
    old.provider_id = "397221".into();
    let id = repo.save_events(&[old.clone()]).await.unwrap()[0];
    sqlx::query("UPDATE economic_event SET event_time='2026-11-04T21:30:00+08:00'")
        .execute(repo.pool())
        .await
        .unwrap();
    old.provider_id = "1001659136201".into();
    assert_eq!(repo.save_events(&[old]).await.unwrap(), vec![id]);
}
