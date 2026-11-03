-- Separate opening window; never mix its baseline/samples with release detection.
CREATE TABLE earnings_stock_opening (
    id INTEGER PRIMARY KEY REFERENCES earnings_stock_tracking(id) ON DELETE CASCADE,
    starts_at INTEGER NOT NULL,
    ends_at INTEGER NOT NULL CHECK (ends_at = starts_at + 3600),
    status TEXT NOT NULL DEFAULT 'collecting' CHECK (status IN ('collecting', 'completed')),
    currency TEXT,
    last_attempt_at INTEGER,
    last_success_at INTEGER,
    last_error TEXT
);
CREATE INDEX earnings_stock_opening_active ON earnings_stock_opening(status, starts_at, ends_at, last_attempt_at);

CREATE TABLE earnings_stock_opening_snapshot (
    tracking_id INTEGER NOT NULL REFERENCES earnings_stock_opening(id) ON DELETE CASCADE,
    timestamp INTEGER NOT NULL,
    first_quote_at INTEGER NOT NULL,
    last_quote_at INTEGER NOT NULL,
    open REAL NOT NULL CHECK (open > 0),
    high REAL NOT NULL CHECK (high > 0),
    low REAL NOT NULL CHECK (low > 0),
    close REAL NOT NULL CHECK (close > 0),
    timestamp_basis TEXT NOT NULL,
    PRIMARY KEY (tracking_id, timestamp)
);
-- Do not replay pre-upgrade releases: their release-session evidence was not persisted.
