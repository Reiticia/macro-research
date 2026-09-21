-- release_group was persisted but never read by the backend or clients. Keep the nullable
-- releaseGroupId API field for wire compatibility, but remove the unused relational structure.
ALTER TABLE economic_event DROP COLUMN release_group_id;
DROP TABLE release_group;
