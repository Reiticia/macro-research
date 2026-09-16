ALTER TABLE economic_event ADD COLUMN event_zh_cn TEXT;
ALTER TABLE economic_event ADD COLUMN event_zh_tw TEXT;

CREATE TABLE IF NOT EXISTS event_name_translation (
    source_text TEXT PRIMARY KEY,
    zh_cn TEXT NOT NULL,
    zh_tw TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_economic_event_translation_missing
    ON economic_event(event) WHERE event_zh_cn IS NULL OR event_zh_tw IS NULL;
