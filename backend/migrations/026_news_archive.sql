-- Public news only: one row per original, normalized publisher URL.
-- Retention is based on publication time, not refresh/last-seen time.
CREATE TABLE news_articles (
    id TEXT PRIMARY KEY NOT NULL,
    original_url TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    source TEXT NOT NULL,
    feed_id TEXT NOT NULL,
    published_at INTEGER NOT NULL,
    summary TEXT,
    first_seen_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    body_text TEXT,
    body_status TEXT NOT NULL DEFAULT 'pending' CHECK (body_status IN ('pending','available','unavailable')),
    body_fetched_at INTEGER,
    body_attempted_at INTEGER,
    body_attempts INTEGER NOT NULL DEFAULT 0,
    body_truncated INTEGER NOT NULL DEFAULT 0,
    resolved_url TEXT,
    body_error TEXT
);
CREATE INDEX news_articles_published ON news_articles(published_at DESC, id);
CREATE INDEX news_articles_body_queue ON news_articles(body_status, body_attempted_at, published_at DESC);

CREATE TABLE news_sources (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    succeeded_at INTEGER,
    attempted_at INTEGER NOT NULL,
    failed INTEGER NOT NULL DEFAULT 0
);
