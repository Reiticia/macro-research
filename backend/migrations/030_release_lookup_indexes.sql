-- Preserve legacy Z/+00:00 timestamps while indexing the exact expressions used by polling.
CREATE INDEX idx_event_release_instant ON economic_event(julianday(event_time), country);
CREATE INDEX idx_event_pending_release_time ON economic_event(julianday(event_time))
WHERE actual IS NULL AND time_exact=1
  AND status IN ('scheduled','watching','data_unavailable');
