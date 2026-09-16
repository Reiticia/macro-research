use async_trait::async_trait;
use chrono::{DateTime, Utc};

use crate::{error::AppError, model::EconomicEvent};

#[async_trait]
pub trait CalendarProvider: Send + Sync {
    async fn fetch_events(
        &self,
        start: DateTime<Utc>,
        end: DateTime<Utc>,
    ) -> Result<Vec<EconomicEvent>, AppError>;
}
