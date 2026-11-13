-- Durable verdict snapshots and human review; no changes to existing translations/history.
CREATE TABLE translation_review_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    fingerprint TEXT NOT NULL UNIQUE,
    source TEXT NOT NULL,
    zh_cn TEXT NOT NULL,
    zh_tw TEXT NOT NULL,
    reviewer TEXT NOT NULL,
    confidence REAL CHECK(confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    threshold REAL CHECK(threshold IS NULL OR (threshold >= 0 AND threshold <= 1)),
    outcome TEXT NOT NULL CHECK(outcome IN ('approved','rejected','unavailable')),
    status TEXT NOT NULL CHECK(status IN ('recorded','pending','accepted','rejected','superseded')),
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),
    decided_at INTEGER,
    decided_by INTEGER
);
CREATE INDEX translation_review_pending ON translation_review_audit(status, id);
CREATE INDEX translation_review_source ON translation_review_audit(source, status);
CREATE TABLE translation_review_delivery (
    review_id INTEGER NOT NULL REFERENCES translation_review_audit(id) ON DELETE CASCADE,
    chat_id INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    attempted_at INTEGER,
    delivered_at INTEGER,
    PRIMARY KEY(review_id, chat_id)
);
