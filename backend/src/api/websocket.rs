use axum::{
    extract::{
        Extension, State, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    response::Response,
};
use tokio::sync::broadcast;

use crate::{AppState, auth::TokenId, model::AppEvent};

pub async fn websocket(
    State(state): State<AppState>,
    Extension(token): Extension<TokenId>,
    upgrade: WebSocketUpgrade,
) -> Response {
    tracing::info!(client = %token.0, "client authenticated and connected to backend");
    let receiver = state.event_bus.subscribe();
    upgrade.on_upgrade(move |socket| stream(socket, receiver, state.auth.store, token))
}

async fn stream(
    mut socket: WebSocket,
    mut receiver: broadcast::Receiver<AppEvent>,
    store: std::sync::Arc<crate::auth::TokenStore>,
    token: TokenId,
) {
    let mut recheck = tokio::time::interval(std::time::Duration::from_secs(5));
    loop {
        tokio::select! {
            _ = recheck.tick() => {
                if !store.is_active(&token.0).await.unwrap_or(false) {
                    let _ = socket.send(Message::Close(None)).await;
                    break;
                }
            },
            event = receiver.recv() => match event {
                Ok(event) => {
                    if !store.is_active(&token.0).await.unwrap_or(false) { break; }
                    let Ok(payload) = serde_json::to_string(&event) else {
                        continue;
                    };
                    if socket.send(Message::Text(payload.into())).await.is_err() {
                        break;
                    }
                }
                Err(broadcast::error::RecvError::Lagged(skipped)) => {
                    tracing::warn!(skipped, "websocket subscriber lagged");
                }
                Err(broadcast::error::RecvError::Closed) => break,
            },
            message = socket.recv() => match message {
                Some(Ok(Message::Ping(payload))) => {
                    if socket.send(Message::Pong(payload)).await.is_err() {
                        break;
                    }
                }
                Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                Some(Ok(Message::Pong(_)))
                | Some(Ok(Message::Text(_)))
                | Some(Ok(Message::Binary(_))) => {}
            },
        }
    }
}
