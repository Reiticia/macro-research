-- Only the explicitly reviewed offline repair tool populates this table.
-- No automatic deletion/merge occurs during migration or normal calendar sync.
CREATE TABLE IF NOT EXISTS event_duplicate_merge (
    old_id INTEGER PRIMARY KEY,
    canonical_id INTEGER NOT NULL REFERENCES economic_event(id),
    merged_at TEXT NOT NULL,
    reason TEXT NOT NULL,
    archive_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_event_duplicate_merge_canonical
ON event_duplicate_merge(canonical_id);
