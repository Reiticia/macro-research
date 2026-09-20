-- Server-side AI market analysis, cached per (event, language, method).
CREATE TABLE IF NOT EXISTS ai_analysis (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id INTEGER NOT NULL REFERENCES economic_event(id) ON DELETE CASCADE,
    language TEXT NOT NULL,
    method INTEGER NOT NULL,
    timezone TEXT NOT NULL DEFAULT 'UTC',
    revision INTEGER NOT NULL DEFAULT 1,
    chain_json TEXT NOT NULL,
    data_analysis TEXT NOT NULL,
    market_outlook TEXT NOT NULL,
    risks TEXT,
    model TEXT NOT NULL,
    generated_at TEXT NOT NULL,
    UNIQUE(event_id, language, method, timezone)
);

-- Per-token daily request counters backing the API quota.
CREATE TABLE IF NOT EXISTS api_usage (
    token_id TEXT NOT NULL,
    day TEXT NOT NULL,
    scope TEXT NOT NULL,
    count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (token_id, day, scope)
);

-- Current health of every upstream source the server depends on.
CREATE TABLE IF NOT EXISTS alert_state (
    key TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    last_changed_at TEXT,
    last_notified_at TEXT
);

-- Audit log of every notification the bot attempted to send.
CREATE TABLE IF NOT EXISTS alert_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    key TEXT NOT NULL,
    status TEXT NOT NULL,
    severity TEXT NOT NULL,
    message TEXT NOT NULL,
    created_at TEXT NOT NULL
);

-- Reader-submitted event-name translation corrections awaiting admin review.
CREATE TABLE IF NOT EXISTS translation_correction (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_name TEXT NOT NULL,
    proposed_zh_cn TEXT NOT NULL,
    proposed_zh_tw TEXT NOT NULL,
    requested_by TEXT,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    decided_at TEXT,
    telegram_message_id INTEGER
);

CREATE INDEX IF NOT EXISTS idx_translation_correction_name
    ON translation_correction(event_name, created_at DESC);

-- Event names the admin no longer wants correction suggestions for.
CREATE TABLE IF NOT EXISTS translation_correction_mute (
    event_name TEXT PRIMARY KEY,
    created_at TEXT NOT NULL
);

-- Long-polling cursor for the Telegram bot.
CREATE TABLE IF NOT EXISTS telegram_offset (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    update_offset INTEGER NOT NULL DEFAULT 0
);

INSERT OR IGNORE INTO telegram_offset (id, update_offset) VALUES (1, 0);

-- Bookkeeping so a missing release value is only reported once per window.
CREATE TABLE IF NOT EXISTS data_missing_notice (
    event_id INTEGER PRIMARY KEY,
    notified_at TEXT NOT NULL
);
