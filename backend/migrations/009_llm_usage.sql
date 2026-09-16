-- Audit log of every model call: what it was for, which model, how many tokens, how long.
CREATE TABLE IF NOT EXISTS llm_usage (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at TEXT NOT NULL,
    -- Category: which subsystem made the call.
    scope TEXT NOT NULL,
    -- Finer category inside the scope (analysis pass, translation batch, …).
    kind TEXT NOT NULL,
    -- Calling API token, or 'server' for scheduler-initiated work.
    token_id TEXT NOT NULL DEFAULT 'server',
    event_id INTEGER,
    language TEXT,
    method INTEGER,
    model TEXT NOT NULL,
    endpoint_host TEXT NOT NULL,
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    total_tokens INTEGER NOT NULL DEFAULT 0,
    reasoning_tokens INTEGER NOT NULL DEFAULT 0,
    cached_tokens INTEGER NOT NULL DEFAULT 0,
    latency_ms INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL,
    error TEXT
);

CREATE INDEX IF NOT EXISTS idx_llm_usage_created ON llm_usage(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_llm_usage_scope ON llm_usage(scope, kind, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_llm_usage_token ON llm_usage(token_id, created_at DESC);

-- Usage attributed to each cached briefing, so the app can show what a run cost and the
-- audit can be reconciled against the cached content.
ALTER TABLE ai_analysis ADD COLUMN prompt_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_analysis ADD COLUMN completion_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_analysis ADD COLUMN total_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_analysis ADD COLUMN call_count INTEGER NOT NULL DEFAULT 0;
