ALTER TABLE economic_event ADD COLUMN time_exact INTEGER NOT NULL DEFAULT 1;

CREATE TABLE backfill_run (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    from_date TEXT NOT NULL,
    to_date TEXT NOT NULL,
    status TEXT NOT NULL,
    started_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    error TEXT,
    UNIQUE(from_date, to_date)
);
CREATE TABLE backfill_day (
    run_id INTEGER NOT NULL REFERENCES backfill_run(id),
    date TEXT NOT NULL,
    status TEXT NOT NULL,
    events INTEGER NOT NULL DEFAULT 0,
    analyses INTEGER NOT NULL DEFAULT 0,
    error TEXT,
    updated_at TEXT NOT NULL,
    PRIMARY KEY(run_id, date)
);
CREATE TABLE historical_fetch (
    source TEXT NOT NULL,
    symbol TEXT NOT NULL,
    interval_seconds INTEGER NOT NULL,
    from_time TEXT NOT NULL,
    to_time TEXT NOT NULL,
    PRIMARY KEY(source, symbol, interval_seconds, from_time, to_time)
);

-- Cache real historical candles independently of the live quote snapshots.
-- Keep interval/source in the key: coarse bars must never masquerade as 1m quotes.
CREATE TABLE historical_candle (
    source TEXT NOT NULL,
    symbol TEXT NOT NULL,
    interval_seconds INTEGER NOT NULL,
    timestamp TEXT NOT NULL,
    open REAL NOT NULL,
    high REAL NOT NULL,
    low REAL NOT NULL,
    close REAL NOT NULL,
    volume REAL,
    PRIMARY KEY(source, symbol, interval_seconds, timestamp)
);
