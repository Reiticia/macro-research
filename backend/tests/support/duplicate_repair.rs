use super::*;

#[tokio::test]
async fn repaired_numeric_ids_resolve_all_public_event_routes_without_changing_auth() {
    let app = app().await;
    let target = app
        .state
        .events
        .save_events(&[fixture_event(-1)])
        .await
        .unwrap()[0];
    let old = 987654;
    sqlx::query(
        "INSERT INTO event_duplicate_merge VALUES(?,?, '2026-11-07T00:00:00Z','reviewed','{}')",
    )
    .bind(old)
    .bind(target)
    .execute(&app.pool)
    .await
    .unwrap();
    sqlx::query("INSERT INTO analysis_report(event_id,macro_signal,expected_reaction_json,observed_reaction_json,summary,created_at,updated_at)
        VALUES(?,'neutral','[]','{\"reactions\":[],\"comparisons\":[]}','retained analysis','2026-11-07T00:00:00Z','2026-11-07T00:00:00Z')")
        .bind(target).execute(&app.pool).await.unwrap();
    sqlx::query("INSERT INTO market_snapshot(event_id,symbol,timestamp,price) VALUES(?,'BTC','2026-11-07T00:00:00Z',100)")
        .bind(target).execute(&app.pool).await.unwrap();
    let router = api::router(app.state);
    for (method, suffix) in [
        ("GET", ""),
        ("POST", "/refresh"),
        ("GET", "/analysis"),
        ("GET", "/market"),
    ] {
        let path = format!("/api/v1/events/{old}{suffix}");
        assert_eq!(
            router
                .clone()
                .oneshot(request(method, &path, None))
                .await
                .unwrap()
                .status(),
            StatusCode::UNAUTHORIZED
        );
        let response = router
            .clone()
            .oneshot(request(method, &path, Some("secret-token")))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK, "{path}");
        let bytes = axum::body::to_bytes(response.into_body(), 65536)
            .await
            .unwrap();
        let body: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        match suffix {
            "/analysis" => assert_eq!(body["eventId"], target),
            "/market" => assert_eq!(body["snapshots"][0]["eventId"], target),
            _ => {
                assert_eq!(body["event"]["id"], target);
                assert_eq!(body["observations"][0]["eventId"], target);
            }
        }
    }
    // Description lazy creation must also use the retained FK, not the removed ID.
    let description_id: i64 = sqlx::query_scalar("SELECT event_id FROM event_description")
        .fetch_one(&app.pool)
        .await
        .unwrap();
    assert_eq!(description_id, target);
}
