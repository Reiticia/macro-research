CREATE TABLE IF NOT EXISTS release_group (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_key TEXT NOT NULL UNIQUE,
    country TEXT NOT NULL,
    release_time TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS economic_event (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    provider TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    release_group_id INTEGER REFERENCES release_group(id),
    country TEXT NOT NULL,
    currency TEXT,
    category TEXT NOT NULL,
    event TEXT NOT NULL,
    event_time TEXT NOT NULL,
    importance INTEGER NOT NULL CHECK (importance BETWEEN 0 AND 3),
    actual TEXT,
    previous TEXT,
    consensus TEXT,
    forecast TEXT,
    unit TEXT,
    status TEXT NOT NULL DEFAULT 'scheduled',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(provider, provider_id)
);

CREATE INDEX IF NOT EXISTS idx_economic_event_time ON economic_event(event_time);
CREATE INDEX IF NOT EXISTS idx_economic_event_status_time ON economic_event(status, event_time);
CREATE INDEX IF NOT EXISTS idx_economic_event_country_category ON economic_event(country, category);

