CREATE TABLE IF NOT EXISTS market_snapshot (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id INTEGER NOT NULL REFERENCES economic_event(id) ON DELETE CASCADE,
    symbol TEXT NOT NULL,
    timestamp TEXT NOT NULL,
    price REAL NOT NULL,
    open REAL,
    high REAL,
    low REAL,
    close REAL,
    volume REAL,
    UNIQUE(event_id, symbol, timestamp)
);

CREATE INDEX IF NOT EXISTS idx_snapshot_event_symbol_time
ON market_snapshot(event_id, symbol, timestamp);

CREATE TABLE IF NOT EXISTS market_reaction (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id INTEGER NOT NULL REFERENCES economic_event(id) ON DELETE CASCADE,
    symbol TEXT NOT NULL,
    baseline_price REAL NOT NULL,
    change_1m REAL,
    change_5m REAL,
    change_15m REAL,
    change_30m REAL,
    change_60m REAL,
    calculated_at TEXT NOT NULL,
    UNIQUE(event_id, symbol)
);
