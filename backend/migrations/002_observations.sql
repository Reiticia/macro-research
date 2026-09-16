CREATE TABLE IF NOT EXISTS event_observation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id INTEGER NOT NULL REFERENCES economic_event(id) ON DELETE CASCADE,
    observed_at TEXT NOT NULL,
    actual TEXT,
    previous TEXT,
    consensus TEXT,
    forecast TEXT
);

CREATE INDEX IF NOT EXISTS idx_observation_event_time
ON event_observation(event_id, observed_at);

