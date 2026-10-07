-- Global administrator notification preferences, independent of collection and health tracking.
CREATE TABLE notification_settings (
    key TEXT PRIMARY KEY CHECK (key IN ('data_missing', 'data_sources')),
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1))
);

INSERT INTO notification_settings (key, enabled) VALUES
    ('data_missing', 1),
    ('data_sources', 1);
