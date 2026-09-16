CREATE TABLE IF NOT EXISTS analysis_report (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id INTEGER NOT NULL REFERENCES economic_event(id) ON DELETE CASCADE,
    raw_surprise TEXT,
    macro_signal TEXT NOT NULL,
    expected_reaction_json TEXT NOT NULL,
    observed_reaction_json TEXT NOT NULL,
    summary TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(event_id)
);

