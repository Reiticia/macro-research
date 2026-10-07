use sqlx::SqlitePool;

use crate::error::AppError;

/// Global admin preferences. These only gate delivery, never upstream collection or health.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum NotificationKind {
    DataMissing,
    DataSources,
}

impl NotificationKind {
    pub const ALL: [Self; 2] = [Self::DataMissing, Self::DataSources];

    pub fn key(self) -> &'static str {
        match self {
            Self::DataMissing => "data_missing",
            Self::DataSources => "data_sources",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::DataMissing => "公布值缺失通知",
            Self::DataSources => "数据源异常/恢复通知",
        }
    }

    pub fn parse(key: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|kind| kind.key() == key)
    }

    pub fn for_alert(key: &str) -> Option<Self> {
        if key == "calendar.data_missing" {
            Some(Self::DataMissing)
        } else if key.starts_with("calendar.") || key.starts_with("market.") {
            Some(Self::DataSources)
        } else {
            None
        }
    }
}

pub async fn enabled(pool: &SqlitePool, kind: NotificationKind) -> Result<bool, AppError> {
    Ok(
        sqlx::query_scalar::<_, bool>("SELECT enabled FROM notification_settings WHERE key = ?")
            .bind(kind.key())
            .fetch_optional(pool)
            .await?
            .unwrap_or(true),
    )
}

pub async fn set_enabled(
    pool: &SqlitePool,
    kind: NotificationKind,
    enabled: bool,
) -> Result<(), AppError> {
    sqlx::query(
        "INSERT INTO notification_settings (key, enabled) VALUES (?, ?) \
         ON CONFLICT(key) DO UPDATE SET enabled = excluded.enabled",
    )
    .bind(kind.key())
    .bind(enabled)
    .execute(pool)
    .await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn notification_categories_do_not_overlap() {
        assert_eq!(
            NotificationKind::for_alert("calendar.data_missing"),
            Some(NotificationKind::DataMissing)
        );
        for key in [
            "calendar.primary",
            "calendar.fallback",
            "calendar.all_sources",
            "market.yahoo",
            "market.cnbc",
            "market.biquote",
            "market.binance",
        ] {
            assert_eq!(
                NotificationKind::for_alert(key),
                Some(NotificationKind::DataSources)
            );
        }
        for key in [
            "service.startup",
            "analysis.ai",
            "translation.relay",
            "typesafe",
            "fcm",
        ] {
            assert_eq!(NotificationKind::for_alert(key), None);
        }
    }

    #[tokio::test]
    async fn settings_default_on_and_remain_independent() {
        let pool = sqlx::sqlite::SqlitePoolOptions::new()
            .max_connections(1)
            .connect("sqlite::memory:")
            .await
            .unwrap();
        sqlx::migrate!("./migrations").run(&pool).await.unwrap();
        for kind in NotificationKind::ALL {
            assert!(enabled(&pool, kind).await.unwrap());
        }
        set_enabled(&pool, NotificationKind::DataMissing, false)
            .await
            .unwrap();
        assert!(!enabled(&pool, NotificationKind::DataMissing).await.unwrap());
        assert!(enabled(&pool, NotificationKind::DataSources).await.unwrap());
        set_enabled(&pool, NotificationKind::DataSources, false)
            .await
            .unwrap();
        set_enabled(&pool, NotificationKind::DataMissing, true)
            .await
            .unwrap();
        assert!(enabled(&pool, NotificationKind::DataMissing).await.unwrap());
        assert!(!enabled(&pool, NotificationKind::DataSources).await.unwrap());
    }
}
