-- Additive metadata only. Do not delete/reassign legacy event IDs, aliases, history or topics.
ALTER TABLE economic_event ADD COLUMN release_metadata TEXT;
