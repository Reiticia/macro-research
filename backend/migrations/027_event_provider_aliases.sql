-- Preserve public event identity when an upstream ID changes or a source is promoted.
-- No event rows are deleted or merged by this migration.
CREATE INDEX idx_economic_event_country_time ON economic_event(country,event_time);
CREATE TABLE event_provider_alias (
    provider TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    event_id INTEGER NOT NULL REFERENCES economic_event(id) ON DELETE CASCADE,
    PRIMARY KEY (provider, provider_id)
);
CREATE INDEX event_provider_alias_event ON event_provider_alias(event_id);
INSERT INTO event_provider_alias(provider,provider_id,event_id)
SELECT provider,provider_id,id FROM economic_event;
