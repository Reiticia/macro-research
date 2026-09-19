-- Durable shared analysis jobs. Only the scheduler and administrator can enqueue generation.
CREATE TABLE shared_ai_job (
    event_id INTEGER NOT NULL REFERENCES economic_event(id),
    method INTEGER NOT NULL CHECK(method BETWEEN 1 AND 3),
    language TEXT NOT NULL,
    target_revision INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'pending',
    retry_at TEXT NOT NULL DEFAULT '',
    last_error TEXT,
    PRIMARY KEY(event_id, method, language)
);
CREATE TABLE analysis_feedback (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    analysis_id INTEGER NOT NULL REFERENCES ai_analysis(id),
    revision INTEGER NOT NULL,
    requested_by TEXT NOT NULL,
    message TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    notified INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    decided_at TEXT,
    UNIQUE(analysis_id, revision)
);
