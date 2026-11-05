use super::event_from_row;
use crate::{error::AppError, model::EconomicEvent};
use sqlx::SqliteConnection;

/// Current identity wins over an alias if legacy/manual data ever makes them inconsistent.
pub(super) async fn find_identity(
    connection: &mut SqliteConnection,
    provider: &str,
    provider_id: &str,
) -> Result<Option<EconomicEvent>, AppError> {
    let current = sqlx::query("SELECT * FROM economic_event WHERE provider=? AND provider_id=?")
        .bind(provider)
        .bind(provider_id)
        .fetch_optional(&mut *connection)
        .await?;
    if let Some(row) = current {
        return Ok(Some(event_from_row(row)?));
    }
    let row = sqlx::query("SELECT e.* FROM economic_event e JOIN event_provider_alias a ON a.event_id=e.id WHERE a.provider=? AND a.provider_id=?")
        .bind(provider).bind(provider_id).fetch_optional(&mut *connection).await?;
    row.map(event_from_row).transpose()
}

pub(super) async fn remember_identity(
    connection: &mut SqliteConnection,
    event: &EconomicEvent,
    id: i64,
) -> Result<(), AppError> {
    // Never steal an identity from another retained event, including ambiguous legacy rows.
    let result = sqlx::query(
        "INSERT INTO event_provider_alias(provider,provider_id,event_id) VALUES(?,?,?)
        ON CONFLICT(provider,provider_id) DO UPDATE SET event_id=excluded.event_id
        WHERE event_provider_alias.event_id=excluded.event_id",
    )
    .bind(&event.provider)
    .bind(&event.provider_id)
    .bind(id)
    .execute(connection)
    .await?;
    if result.rows_affected() != 1 {
        return Err(AppError::Provider(
            "event provider identity collision".into(),
        ));
    }
    Ok(())
}
