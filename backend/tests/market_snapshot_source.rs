use chrono::{TimeZone, Utc};
use market_event_analyzer::{
    model::{Candle, MarketSymbol, Quote},
    repository::MarketRepository,
};
use sqlx::sqlite::SqlitePoolOptions;

async fn repository() -> MarketRepository {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    sqlx::query(
        "INSERT INTO economic_event (provider, provider_id, country, category, event, event_time, importance, status) VALUES ('test', 'snapshot', 'US', 'test', 'Snapshot', '2026-01-01T00:00:00Z', 1, 'released')",
    )
    .execute(&pool)
    .await
    .unwrap();
    MarketRepository::new(pool)
}

fn candle(timestamp: chrono::DateTime<Utc>, close: f64) -> Candle {
    Candle {
        symbol: MarketSymbol::Gold,
        timestamp,
        open: close,
        high: close,
        low: close,
        close,
        volume: Some(1.0),
    }
}

#[tokio::test]
async fn live_quotes_are_aggregated_into_one_minute_ohlc_bars() {
    let repository = repository().await;
    let base = Utc.with_ymd_and_hms(2026, 1, 1, 0, 1, 10).unwrap();

    for (seconds, price) in [(0, 101.0), (10, 99.0), (40, 100.0)] {
        repository
            .save_quote(
                1,
                &Quote {
                    symbol: MarketSymbol::Gold,
                    timestamp: base + chrono::Duration::seconds(seconds),
                    price,
                },
            )
            .await
            .unwrap();
    }

    let snapshots = repository.snapshots(1).await.unwrap();
    assert_eq!(snapshots.len(), 1);
    assert_eq!(
        snapshots[0].timestamp,
        Utc.with_ymd_and_hms(2026, 1, 1, 0, 1, 0).unwrap()
    );
    assert_eq!(snapshots[0].price, 100.0);
    assert_eq!(snapshots[0].open, Some(101.0));
    assert_eq!(snapshots[0].high, Some(101.0));
    assert_eq!(snapshots[0].low, Some(99.0));
    assert_eq!(snapshots[0].close, Some(100.0));
}

#[tokio::test]
async fn candles_do_not_overwrite_a_live_quote_at_the_same_timestamp() {
    let repository = repository().await;
    let time = Utc.with_ymd_and_hms(2026, 1, 1, 0, 1, 0).unwrap();

    repository
        .save_quote(
            1,
            &Quote {
                symbol: MarketSymbol::Gold,
                timestamp: time,
                price: 101.0,
            },
        )
        .await
        .unwrap();
    repository
        .save_candle_snapshots(1, &[candle(time, 99.0)])
        .await
        .unwrap();

    let snapshots = repository.snapshots(1).await.unwrap();
    assert_eq!(snapshots.len(), 1);
    assert_eq!(snapshots[0].price, 101.0);
}

#[tokio::test]
async fn a_later_live_quote_can_replace_a_candle_snapshot() {
    let repository = repository().await;
    let time = Utc.with_ymd_and_hms(2026, 1, 1, 0, 2, 0).unwrap();

    repository
        .save_candle_snapshots(1, &[candle(time, 99.0)])
        .await
        .unwrap();
    repository
        .save_quote(
            1,
            &Quote {
                symbol: MarketSymbol::Gold,
                timestamp: time,
                price: 101.0,
            },
        )
        .await
        .unwrap();

    let snapshots = repository.snapshots(1).await.unwrap();
    assert_eq!(snapshots[0].price, 101.0);
}
