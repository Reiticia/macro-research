use axum::{
    Router,
    body::Body,
    extract::Extension,
    http::{Request, StatusCode},
    middleware,
    routing::get,
};
use market_event_analyzer::{
    access_keys::{AccessKeys, KeyKind, device_hash, hash},
    auth::{AuthState, TokenId, TokenStore},
    config::LimitConfig,
    quota::QuotaService,
};
use sqlx::sqlite::SqlitePoolOptions;
use std::sync::Arc;
use tower::ServiceExt;

const UUID_A: &str = "11111111-1111-4111-8111-111111111111";
const UUID_B: &str = "22222222-2222-4222-8222-222222222222";
const ANDROID_A: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const ANDROID_B: &str = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

async fn fixture() -> (AccessKeys, TokenStore, sqlx::SqlitePool) {
    let pool = SqlitePoolOptions::new()
        .max_connections(4)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::migrate!("./migrations").run(&pool).await.unwrap();
    (
        AccessKeys::new(pool.clone()),
        TokenStore::new(true, pool.clone()),
        pool,
    )
}
async fn issued(
    keys: &AccessKeys,
    message: i64,
    kind: KeyKind,
) -> market_event_analyzer::access_keys::IssuedKey {
    let (request, _) = keys.request(99, message, kind).await.unwrap();
    let key = keys.approve(request.id, 42, kind).await.unwrap().unwrap();
    keys.finish_delivery(&key, true).await.unwrap();
    key
}

#[tokio::test]
async fn empty_store_stays_closed_and_only_digests_are_persisted() {
    let (keys, store, pool) = fixture().await;
    assert!(
        store
            .authenticate(None, None, None)
            .await
            .unwrap()
            .is_none()
    );
    assert!(
        store
            .authenticate(Some("Bearer old-config-token"), None, None)
            .await
            .unwrap()
            .is_none()
    );
    let key = issued(&keys, 1, KeyKind::General).await;
    assert_eq!(key.secret.len(), 68);
    let saved: String = sqlx::query_scalar("SELECT token_hash FROM api_key WHERE id = ?")
        .bind(key.id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(saved, hash(&key.secret));
    assert_ne!(saved, key.secret);
    let reference = &keys.list(None, 0).await.unwrap()[0].token_prefix;
    assert_eq!(reference.len(), 12);
    assert!(!reference.contains(&key.secret));
    let bearer = format!("Bearer {}", key.secret);
    assert_eq!(
        store.authenticate(Some(&bearer), None, None).await.unwrap(),
        Some(format!("api_key:{}", key.id))
    );
    assert!(
        store
            .authenticate(Some(&bearer), Some(UUID_B), Some(ANDROID_B))
            .await
            .unwrap()
            .is_some()
    );
    let bound: Option<String> = sqlx::query_scalar("SELECT device_hash FROM api_key WHERE id = ?")
        .bind(key.id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert!(bound.is_none());
}

#[tokio::test]
async fn first_device_is_atomic_missing_or_changed_identifiers_are_rejected_and_binding_survives_restart()
 {
    let (keys, store, pool) = fixture().await;
    let key = issued(&keys, 1, KeyKind::Device).await;
    let bearer = format!("Bearer {}", key.secret);
    for (uuid, android) in [
        (None, None),
        (Some(UUID_A), None),
        (None, Some(ANDROID_A)),
        (Some("bad"), Some(ANDROID_A)),
    ] {
        assert!(
            store
                .authenticate(Some(&bearer), uuid, android)
                .await
                .unwrap()
                .is_none()
        );
    }
    let bound: Option<String> = sqlx::query_scalar("SELECT device_hash FROM api_key WHERE id = ?")
        .bind(key.id)
        .fetch_one(&pool)
        .await
        .unwrap();
    assert!(bound.is_none());
    let (a, b) = tokio::join!(
        store.authenticate(Some(&bearer), Some(UUID_A), Some(ANDROID_A)),
        store.authenticate(Some(&bearer), Some(UUID_B), Some(ANDROID_B))
    );
    let a_wins = a.unwrap().is_some();
    assert_ne!(a_wins, b.unwrap().is_some());
    let (uuid, android) = if a_wins {
        (UUID_A, ANDROID_A)
    } else {
        (UUID_B, ANDROID_B)
    };
    let restarted = TokenStore::new(true, pool);
    assert!(
        restarted
            .authenticate(Some(&bearer), Some(uuid), Some(android))
            .await
            .unwrap()
            .is_some()
    );
    assert!(
        restarted
            .authenticate(
                Some(&bearer),
                Some(uuid),
                Some(if a_wins { ANDROID_B } else { ANDROID_A })
            )
            .await
            .unwrap()
            .is_none()
    );
    assert!(
        restarted
            .authenticate(
                Some(&bearer),
                Some(if a_wins { UUID_B } else { UUID_A }),
                Some(android)
            )
            .await
            .unwrap()
            .is_none()
    );
    keys.revoke(key.id, 42).await.unwrap();
    assert!(
        !restarted
            .is_active(&format!("api_key:{}", key.id))
            .await
            .unwrap()
    );
    assert!(
        restarted
            .authenticate(Some(&bearer), Some(uuid), Some(android))
            .await
            .unwrap()
            .is_none()
    );
}

#[tokio::test]
async fn approval_delivery_replay_failure_and_restart_do_not_duplicate_or_enable_unreceived_keys() {
    let (keys, store, pool) = fixture().await;
    let (request, created) = keys.request(99, 1, KeyKind::Device).await.unwrap();
    assert!(created);
    assert!(!keys.request(99, 2, KeyKind::General).await.unwrap().1);
    let (one, two) = tokio::join!(
        keys.approve(request.id, 42, KeyKind::Device),
        keys.approve(request.id, 43, KeyKind::General)
    );
    let one = one.unwrap();
    let two = two.unwrap();
    assert_ne!(one.is_some(), two.is_some());
    let key = one.or(two).unwrap();
    let bearer = format!("Bearer {}", key.secret);
    assert!(
        store
            .authenticate(Some(&bearer), Some(UUID_A), Some(ANDROID_A))
            .await
            .unwrap()
            .is_none()
    );
    keys.finish_delivery(&key, false).await.unwrap();
    assert!(
        keys.pending(0)
            .await
            .unwrap()
            .iter()
            .any(|pending| pending.id == request.id)
    );
    let retry = keys
        .approve(request.id, 42, KeyKind::Device)
        .await
        .unwrap()
        .unwrap();
    assert_ne!(key.secret, retry.secret);
    keys.recover_deliveries().await.unwrap();
    assert_eq!(keys.pending(0).await.unwrap().len(), 1);
    let final_key = keys
        .approve(request.id, 42, KeyKind::Device)
        .await
        .unwrap()
        .unwrap();
    keys.finish_delivery(&final_key, true).await.unwrap();
    assert!(!keys.request(99, 1, KeyKind::Device).await.unwrap().1);
    assert!(
        keys.approve(request.id, 42, KeyKind::General)
            .await
            .unwrap()
            .is_none()
    );
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM api_key WHERE status = 'active'")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(count, 1);
    assert_eq!(keys.list(Some(98), 0).await.unwrap().len(), 0);
}

#[tokio::test]
async fn quota_and_revocation_during_delivery_are_safe() {
    let (keys, _, pool) = fixture().await;
    for message in 1..=3 {
        let (request, _) = keys.request(99, message, KeyKind::Device).await.unwrap();
        keys.reject(request.id, 42).await.unwrap();
    }
    assert!(keys.request(99, 4, KeyKind::Device).await.is_err());
    let (request, _) = keys.request(98, 1, KeyKind::Device).await.unwrap();
    let key = keys
        .approve(request.id, 42, KeyKind::Device)
        .await
        .unwrap()
        .unwrap();
    keys.revoke(key.id, 42).await.unwrap();
    keys.finish_delivery(&key, true).await.unwrap();
    assert_eq!(
        sqlx::query_scalar::<_, String>("SELECT status FROM api_key WHERE id = ?")
            .bind(key.id)
            .fetch_one(&pool)
            .await
            .unwrap(),
        "revoked"
    );
    assert!(device_hash(Some(UUID_A), Some("bad")).is_none());
}

#[tokio::test]
async fn middleware_requires_bound_identity_for_every_protected_request() {
    let (keys, store, pool) = fixture().await;
    let key = issued(&keys, 1, KeyKind::Device).await;
    let state = AuthState {
        store: Arc::new(store),
        quota: Arc::new(QuotaService::new(pool, LimitConfig::default())),
    };
    let router = Router::new()
        .route(
            "/protected",
            get(|Extension(id): Extension<TokenId>| async move { id.0 }),
        )
        .route_layer(middleware::from_fn_with_state(
            state,
            market_event_analyzer::auth::require_token,
        ));
    let request = |uuid: Option<&str>| {
        let mut req = Request::builder()
            .uri("/protected")
            .header("authorization", format!("Bearer {}", key.secret));
        if let Some(uuid) = uuid {
            req = req
                .header("X-Installation-Id", uuid)
                .header("X-Android-Id-Hash", ANDROID_A);
        }
        req.body(Body::empty()).unwrap()
    };
    assert_eq!(
        router
            .clone()
            .oneshot(request(None))
            .await
            .unwrap()
            .status(),
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        router
            .clone()
            .oneshot(request(Some(UUID_A)))
            .await
            .unwrap()
            .status(),
        StatusCode::OK
    );
    assert_eq!(
        router
            .clone()
            .oneshot(request(Some(UUID_B)))
            .await
            .unwrap()
            .status(),
        StatusCode::UNAUTHORIZED
    );
    keys.revoke(key.id, 42).await.unwrap();
    assert_eq!(
        router
            .oneshot(request(Some(UUID_A)))
            .await
            .unwrap()
            .status(),
        StatusCode::UNAUTHORIZED
    );
}
