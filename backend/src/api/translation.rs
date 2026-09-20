use axum::{Json, extract::State};

use crate::{AppState, error::AppError};

/// Read existing server translations without spending a reader's or server's model quota.
pub async fn names(
    State(state): State<AppState>,
    Json(names): Json<Vec<String>>,
) -> Result<Json<std::collections::HashMap<String, (String, String)>>, AppError> {
    if names.len() > 100 || names.iter().any(|name| name.len() > 1000) {
        return Err(AppError::InvalidRequest(
            "at most 100 event names per request".into(),
        ));
    }
    Ok(Json(
        state.events.cached_event_name_translations(&names).await?,
    ))
}
