-- Persist live event quotes as one UTC-minute OHLC bar per event and symbol.
-- The live quote cache remains independent and continues refreshing every few seconds.

-- Remove older sub-minute rows, keeping the latest sample in each minute.
DELETE FROM market_snapshot
WHERE id IN (
    SELECT id
    FROM (
        SELECT
            id,
            ROW_NUMBER() OVER (
                PARTITION BY event_id, symbol, substr(timestamp, 1, 16)
                ORDER BY timestamp DESC, id DESC
            ) AS row_number
        FROM market_snapshot
    )
    WHERE row_number > 1
);

-- Normalize retained rows so future minute upserts use the same key.
UPDATE market_snapshot
SET timestamp = substr(timestamp, 1, 16) || ':00+00:00'
WHERE timestamp != substr(timestamp, 1, 16) || ':00+00:00';

-- The UNIQUE(event_id, symbol, timestamp) constraint already provides this index.
DROP INDEX IF EXISTS idx_snapshot_event_symbol_time;
