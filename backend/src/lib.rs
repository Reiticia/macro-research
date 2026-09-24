pub mod ai_analysis;
pub mod alert;
pub mod analysis;
pub mod api;
pub mod auth;
pub mod backfill;
pub mod calendar;
pub mod config;
pub mod error;
pub mod event_description;
pub mod fcm;
pub mod llm_usage;
pub mod market;
pub mod market_selection;
pub mod model;
pub mod openai_compat;
pub mod quota;
pub mod repository;
pub mod scheduler;
pub mod shared_ai;
pub mod translation;
pub mod typesafe;

use std::sync::Arc;

use analysis::AnalysisService;
use calendar::CalendarService;
use market::MarketService;
use repository::{AnalysisRepository, EventRepository, MarketRepository};
use tokio::sync::broadcast;

use crate::{
    ai_analysis::AiAnalysisService, alert::HealthRegistry, auth::AuthState, config::AppConfig,
    llm_usage::LlmUsageRepository, model::AppEvent, quota::QuotaService,
};

#[derive(Clone)]
pub struct AppState {
    pub config: Arc<AppConfig>,
    pub events: EventRepository,
    pub market: MarketRepository,
    pub analyses: AnalysisRepository,
    pub calendar_service: Arc<CalendarService>,
    pub market_service: Arc<MarketService>,
    pub analysis_service: Arc<AnalysisService>,
    pub ai_analysis_service: Option<Arc<AiAnalysisService>>,
    pub health: Arc<HealthRegistry>,
    /// Model-call audit log; absent only when auditing is switched off.
    pub llm_usage: Option<Arc<LlmUsageRepository>>,
    pub auth: AuthState,
    pub quota: Arc<QuotaService>,
    pub event_bus: broadcast::Sender<AppEvent>,
    pub fcm: Option<Arc<fcm::FcmNotifier>>,
    pub market_selector: Option<Arc<market_selection::MarketSelector>>,
    pub backfill: backfill::repository::BackfillRepository,
}
