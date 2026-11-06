//! Thirty-day, URL-deduplicated public news archive. Reads never perform network IO.
use crate::{
    error::AppError,
    news::{NewsArticle, NewsSource, NewsSourceStatus, safe_article_url},
};
use chrono::{DateTime, Utc};
use serde::Serialize;
use sqlx::{Row, SqlitePool, sqlite::SqliteRow};

pub const RETENTION_SECONDS: i64 = 30 * 24 * 3600;
const RETRY_SECONDS: i64 = 3600;
#[derive(Clone)]
pub struct NewsArchive {
    pub(crate) pool: SqlitePool,
}
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ArchivedArticle {
    pub archive_id: String,
    pub url: String,
    pub title: String,
    pub title_translations: crate::news_translation::Titles,
    pub source: String,
    pub published_at: DateTime<Utc>,
    pub summary: Option<String>,
    pub text: Option<String>,
    pub truncated: bool,
    pub fetched_at: Option<DateTime<Utc>>,
    pub body_status: String,
}
#[derive(Clone)]
pub struct BodyJob {
    pub id: String,
    pub url: String,
}
fn stamp(value: i64) -> Result<DateTime<Utc>, AppError> {
    DateTime::from_timestamp(value, 0)
        .ok_or_else(|| AppError::Internal("Invalid news archive timestamp".into()))
}
pub fn article_id(url: &str) -> String {
    ring::digest::digest(&ring::digest::SHA256, url.as_bytes())
        .as_ref()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}
fn metadata(row: &SqliteRow) -> Result<NewsArticle, AppError> {
    Ok(NewsArticle {
        archive_id: row.try_get("id")?,
        title: row.try_get("title")?,
        title_translations: crate::news_translation::stored_titles(
            &row.try_get::<String, _>("title_translations")?,
        ),
        url: row.try_get("original_url")?,
        source: row.try_get("source")?,
        feed_id: row.try_get("feed_id")?,
        published_at: stamp(row.try_get("published_at")?)?,
        summary: row.try_get("summary")?,
    })
}
impl NewsArchive {
    pub fn new(pool: SqlitePool) -> Self {
        Self { pool }
    }
    pub async fn ingest(
        &self,
        articles: &[NewsArticle],
        now: DateTime<Utc>,
    ) -> Result<(), AppError> {
        let mut tx = self.pool.begin().await?;
        for row in articles.iter().take(60) {
            if row.published_at > now
                || row.published_at.timestamp() < now.timestamp() - RETENTION_SECONDS
            {
                continue;
            }
            let Some(url) = safe_article_url(&row.url) else {
                continue;
            };
            sqlx::query("INSERT INTO news_articles(id,original_url,title,source,feed_id,published_at,summary,first_seen_at,last_seen_at)
                VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(original_url) DO UPDATE SET
                title_translations=CASE WHEN news_articles.title!=excluded.title THEN '{}' ELSE news_articles.title_translations END,
                title_translation_status=CASE WHEN news_articles.title!=excluded.title THEN 'pending' ELSE news_articles.title_translation_status END,
                title_translation_attempts=CASE WHEN news_articles.title!=excluded.title THEN 0 ELSE news_articles.title_translation_attempts END,
                title_translation_attempted_at=CASE WHEN news_articles.title!=excluded.title THEN NULL ELSE news_articles.title_translation_attempted_at END,
                title_translation_claim=CASE WHEN news_articles.title!=excluded.title THEN NULL ELSE news_articles.title_translation_claim END,
                title=excluded.title,summary=excluded.summary,last_seen_at=excluded.last_seen_at,published_at=MIN(news_articles.published_at,excluded.published_at)")
                .bind(article_id(&url)).bind(&url).bind(&row.title).bind(&row.source).bind(&row.feed_id)
                .bind(row.published_at.timestamp()).bind(&row.summary).bind(now.timestamp()).bind(now.timestamp())
                .execute(&mut *tx).await?;
        }
        tx.commit().await?;
        Ok(())
    }
    pub async fn record_source(
        &self,
        source: &NewsSource,
        success: bool,
        now: DateTime<Utc>,
    ) -> Result<(), AppError> {
        sqlx::query("INSERT INTO news_sources(id,name,succeeded_at,attempted_at,failed) VALUES(?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET name=excluded.name,attempted_at=excluded.attempted_at,
            failed=excluded.failed,succeeded_at=COALESCE(excluded.succeeded_at,news_sources.succeeded_at)")
            .bind(&source.id).bind(&source.name).bind(success.then_some(now.timestamp())).bind(now.timestamp()).bind(!success)
            .execute(&self.pool).await?;
        Ok(())
    }
    pub async fn saved_source(
        &self,
        id: &str,
        now: DateTime<Utc>,
    ) -> Result<Option<NewsSourceStatus>, AppError> {
        let row = sqlx::query("SELECT * FROM news_sources WHERE id=? AND succeeded_at>=?")
            .bind(id)
            .bind(now.timestamp() - RETENTION_SECONDS)
            .fetch_optional(&self.pool)
            .await?;
        row.map(|r| {
            Ok(NewsSourceStatus {
                id: r.try_get("id")?,
                name: r.try_get("name")?,
                status: "stale",
                updated_at: Some(stamp(r.try_get("succeeded_at")?)?),
            })
        })
        .transpose()
    }
    pub async fn list(
        &self,
        offset: i64,
        now: DateTime<Utc>,
    ) -> Result<(Vec<NewsArticle>, Option<i64>), AppError> {
        if !(0..=100_000).contains(&offset) {
            return Err(AppError::InvalidRequest(
                "news offset must be between 0 and 100000".into(),
            ));
        }
        let rows = sqlx::query("SELECT id,original_url,title,title_translations,source,feed_id,published_at,summary FROM news_articles
            WHERE published_at>=? AND published_at<=? ORDER BY published_at DESC,id LIMIT 101 OFFSET ?")
            .bind(now.timestamp()-RETENTION_SECONDS).bind(now.timestamp()).bind(offset).fetch_all(&self.pool).await?;
        let next = (rows.len() > 100 && offset + 100 <= 100_000).then_some(offset + 100);
        Ok((
            rows.iter()
                .take(100)
                .map(metadata)
                .collect::<Result<Vec<_>, _>>()?,
            next,
        ))
    }
    pub async fn article(&self, id: &str, now: DateTime<Utc>) -> Result<ArchivedArticle, AppError> {
        if id.len() != 64
            || !id
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err(AppError::InvalidRequest("Invalid news archive ID".into()));
        }
        let row = sqlx::query(
            "SELECT * FROM news_articles WHERE id=? AND published_at>=? AND published_at<=?",
        )
        .bind(id)
        .bind(now.timestamp() - RETENTION_SECONDS)
        .bind(now.timestamp())
        .fetch_optional(&self.pool)
        .await?
        .ok_or(AppError::NotFound)?;
        let base = metadata(&row)?;
        Ok(ArchivedArticle {
            archive_id: base.archive_id,
            url: base.url,
            title: base.title,
            title_translations: base.title_translations,
            source: base.source,
            published_at: base.published_at,
            summary: base.summary,
            text: row.try_get("body_text")?,
            truncated: row.try_get("body_truncated")?,
            fetched_at: row
                .try_get::<Option<i64>, _>("body_fetched_at")?
                .map(stamp)
                .transpose()?,
            body_status: row.try_get("body_status")?,
        })
    }
    pub async fn prune(&self, now: DateTime<Utc>) -> Result<u64, AppError> {
        let removed = sqlx::query("DELETE FROM news_articles WHERE published_at<?")
            .bind(now.timestamp() - RETENTION_SECONDS)
            .execute(&self.pool)
            .await?
            .rows_affected();
        sqlx::query("DELETE FROM news_sources WHERE attempted_at<?")
            .bind(now.timestamp() - RETENTION_SECONDS)
            .execute(&self.pool)
            .await?;
        // A process killed during its final attempt must not leave a permanent 'pending' row.
        sqlx::query(
            "UPDATE news_articles SET body_status='unavailable',body_error='interrupted'
            WHERE body_status='pending' AND body_attempts>=3 AND body_attempted_at<?",
        )
        .bind(now.timestamp() - 300)
        .execute(&self.pool)
        .await?;
        sqlx::query("UPDATE news_articles SET title_translation_status='unavailable',title_translation_claim=NULL
            WHERE title_translation_status='pending' AND title_translation_attempts>=3 AND title_translation_attempted_at<?")
            .bind(now.timestamp()-300).execute(&self.pool).await?;
        Ok(removed)
    }
    pub async fn pending(&self, now: DateTime<Utc>) -> Result<Vec<BodyJob>, AppError> {
        let rows = sqlx::query("SELECT id,original_url FROM news_articles WHERE published_at>=? AND published_at<=?
            AND body_status!='available' AND body_attempts<3 AND (body_attempted_at IS NULL OR body_attempted_at<=?)
            ORDER BY published_at DESC,id LIMIT 12")
            .bind(now.timestamp()-RETENTION_SECONDS).bind(now.timestamp()).bind(now.timestamp()-RETRY_SECONDS).fetch_all(&self.pool).await?;
        rows.into_iter()
            .map(|r| {
                Ok(BodyJob {
                    id: r.try_get("id")?,
                    url: r.try_get("original_url")?,
                })
            })
            .collect()
    }
    /// Atomic claim and bounded retry cooldown survive concurrent workers and process restarts.
    pub async fn claim(&self, id: &str, now: DateTime<Utc>) -> Result<bool, AppError> {
        Ok(sqlx::query("UPDATE news_articles SET body_attempts=body_attempts+1,body_attempted_at=?,body_status='pending'
            WHERE id=? AND published_at>=? AND published_at<=? AND body_status!='available' AND body_attempts<3
            AND (body_attempted_at IS NULL OR body_attempted_at<=?)")
            .bind(now.timestamp()).bind(id).bind(now.timestamp()-RETENTION_SECONDS).bind(now.timestamp())
            .bind(now.timestamp()-RETRY_SECONDS).execute(&self.pool).await?.rows_affected()==1)
    }
    pub async fn save_body(
        &self,
        id: &str,
        body: &crate::news_page::PageText,
        now: DateTime<Utc>,
    ) -> Result<(), AppError> {
        if body.text.is_empty() || body.text.chars().count() > crate::news_page::MAX_BODY_CHARS {
            return Err(AppError::Provider("news_body_invalid_length".into()));
        }
        sqlx::query("UPDATE news_articles SET body_text=?,body_truncated=?,body_fetched_at=?,resolved_url=?,body_status='available',body_error=NULL
            WHERE id=? AND published_at>=?")
            .bind(&body.text).bind(body.truncated).bind(now.timestamp()).bind(&body.url).bind(id)
            .bind(now.timestamp()-RETENTION_SECONDS).execute(&self.pool).await?;
        Ok(())
    }
    pub async fn fail_body(&self, id: &str, code: &str) -> Result<(), AppError> {
        sqlx::query("UPDATE news_articles SET body_status='unavailable',body_error=? WHERE id=? AND body_status!='available'")
            .bind(code).bind(id).execute(&self.pool).await?;
        Ok(())
    }
}

#[cfg(test)]
#[path = "news_archive_tests.rs"]
mod tests;
