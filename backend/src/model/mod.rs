mod analysis;
mod event;
mod historical;
mod market;
mod observation;
pub use historical::*;

pub use analysis::*;
pub use event::*;
pub use market::*;
pub use observation::*;

use serde::Serialize;

#[derive(Clone, Debug, Serialize)]
#[serde(
    tag = "type",
    rename_all = "snake_case",
    rename_all_fields = "camelCase"
)]
pub enum AppEvent {
    EconomicEventReleased {
        event_id: i64,
        event: String,
        event_zh_cn: Option<String>,
        event_zh_tw: Option<String>,
        actual: Option<String>,
        consensus: Option<String>,
    },
    MarketDataCollected {
        event_id: i64,
    },
    AnalysisCompleted {
        event_id: i64,
    },
}

#[cfg(test)]
mod tests {
    use super::AppEvent;

    #[test]
    fn websocket_event_fields_use_the_rest_api_casing() {
        let value = serde_json::to_value(AppEvent::EconomicEventReleased {
            event_id: 7,
            event: "CPI YoY".into(),
            event_zh_cn: Some("消费者价格指数同比".into()),
            event_zh_tw: Some("消費者價格指數同比".into()),
            actual: Some("3.2".into()),
            consensus: Some("3.1".into()),
        })
        .unwrap();

        assert_eq!(value["type"], "economic_event_released");
        assert_eq!(value["eventId"], 7);
        assert_eq!(value["eventZhCn"], "消费者价格指数同比");
        assert!(value.get("event_id").is_none());
    }
}
