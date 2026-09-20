-- A reader opening an older event may need its already-planned nine-row bundle before the
-- historical recovery backlog. Priority changes ordering only; it never creates extra cache
-- shapes or lets the request invoke the model directly.
ALTER TABLE shared_ai_job ADD COLUMN priority INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_shared_ai_job_ready
    ON shared_ai_job(status, priority DESC, retry_at, event_id);
