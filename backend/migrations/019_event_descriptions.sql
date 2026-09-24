CREATE TABLE IF NOT EXISTS event_description (
    event_id INTEGER PRIMARY KEY REFERENCES economic_event(id) ON DELETE CASCADE,
    en TEXT NOT NULL,
    zh_cn TEXT NOT NULL,
    zh_tw TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
