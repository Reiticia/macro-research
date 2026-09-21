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
    upgrade.on_upgrade(move |socket| stream(socket, receiver))
}

async fn stream(mut socket: WebSocket, mut receiver: broadcast::Receiver<AppEvent>) {
    loop {
        tokio::select! {
            event = receiver.recv() => match event {
                Ok(event) => {
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
