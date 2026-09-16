use chrono::{DateTime, Utc};
use rust_decimal::Decimal;
use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EventObservation {
    pub id: i64,
    pub event_id: i64,
    pub observed_at: DateTime<Utc>,
    pub actual: Option<Decimal>,
    pub previous: Option<Decimal>,
    pub consensus: Option<Decimal>,
    pub forecast: Option<Decimal>,
}
