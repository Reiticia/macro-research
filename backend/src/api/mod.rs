mod calendar;
mod event;
mod market;
mod translation;
mod usage;
mod websocket;

use axum::{
    Json, Router,
    extract::{Extension, State},
    middleware,
    routing::{get, post},
};
use serde_json::json;
use tower_http::trace::TraceLayer;

use crate::{AppState, auth, auth::TokenId};

pub fn router(state: AppState) -> Router {
    let protected = Router::new()
        .route("/api/v1/status", get(status))
        .route("/api/v1/usage", get(usage::usage))
        .route("/api/v1/events/upcoming", get(calendar::upcoming))
        .route("/api/v1/calendar", get(calendar::calendar))
        .route("/api/v1/events/history", get(event::history))
        .route("/api/v1/events/by-provider", get(event::by_provider))
        .route("/api/v1/history/backfill", get(backfill_status))
        .route("/api/v1/events/{id}", get(event::detail))
        .route("/api/v1/events/{id}/refresh", post(event::refresh))
        .route("/api/v1/events/{id}/analysis", get(event::analysis))
        .route("/api/v1/events/{id}/market", get(market::market))
        .route("/api/v1/events/{id}/ai-analysis", get(event::ai_analysis))
        .route(
            "/api/v1/ai-analysis/by-provider",
            get(event::ai_analysis_by_provider),
        )
        .route(
            "/api/v1/events/{id}/analysis-feedback",
            post(event::analysis_feedback),
        )
        .route("/api/v1/market/quotes", get(market::quotes))
        .route("/api/v1/ws", get(websocket::websocket))
        .route_layer(middleware::from_fn_with_state(
            state.auth.clone(),
            auth::require_token,
        ));

    Router::new()
        .route("/health", get(health))
        .route("/api/v1/meta", get(meta))
        // Existing event-name translations contain no private or quota-bearing data. Keeping
        // this lookup public lets direct-mode readers localize names without a full data-source
        // token; the bounded request cannot trigger model work or mutate the cache.
        .route("/api/v1/translations/names", post(translation::names))
        .merge(protected)
        // No browser clients: sending permissive CORS headers would only widen the surface.
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

async fn health() -> &'static str {
    "ok"
}

/// Capability handshake. Public so a client can validate the address, the TLS chain and the
/// protocol version before the user commits a token.
async fn meta(State(state): State<AppState>) -> Json<serde_json::Value> {
    Json(json!({
        "name": "macro-research",
        "version": env!("CARGO_PKG_VERSION"),
        "apiVersion": 1,
        "languages": ["en", "zh-CN", "zh-TW"],
        "capabilities": [
            "calendar",
            "market",
            "analysis",
            "aiAnalysis",
            "translation",
            "translationCorrection",
            "ws",
        ],
        "aiEnabled": state.ai_analysis_service.is_some(),
        "serverTime": chrono::Utc::now().to_rfc3339(),
    }))
}

/// Per-source health plus the caller's quota usage.
async fn status(
    State(state): State<AppState>,
    Extension(token): Extension<TokenId>,
) -> Result<Json<serde_json::Value>, crate::error::AppError> {
    let sources = state.health.snapshot().await?;
    let usage = state.quota.usage_today(&token.0).await?;
    Ok(Json(json!({
        "sources": sources,
        "usage": usage
            .into_iter()
            .map(|(scope, count)| json!({"scope": scope, "count": count}))
            .collect::<Vec<_>>(),
    })))
}

async fn backfill_status(
    State(state): State<AppState>,
) -> Result<Json<Option<crate::backfill::repository::BackfillSummary>>, crate::error::AppError> {
    Ok(Json(state.backfill.latest().await?))
}
