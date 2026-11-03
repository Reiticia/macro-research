-- Company-specific earnings observations and price timelines stay separate from macro events.
CREATE TABLE earnings_release_watch (
    date TEXT NOT NULL,
    symbol TEXT NOT NULL,
    fiscal_quarter_ending TEXT,
    actual_eps TEXT,
    last_seen_at INTEGER NOT NULL,
    observation_status TEXT NOT NULL CHECK (observation_status IN (
        'watching', 'tracking', 'already_reported', 'missed_after_gap', 'quarter_changed'
    )),
    PRIMARY KEY (date, symbol)
);

CREATE TABLE earnings_stock_tracking (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL,
    symbol TEXT NOT NULL,
    company_name TEXT NOT NULL,
    fiscal_quarter_ending TEXT,
    actual_eps TEXT NOT NULL,
    detected_at INTEGER NOT NULL,
    ends_at INTEGER NOT NULL CHECK (ends_at = detected_at + 3600),
    status TEXT NOT NULL DEFAULT 'collecting' CHECK (status IN ('collecting', 'completed')),
    currency TEXT,
    last_attempt_at INTEGER,
    last_success_at INTEGER,
    last_error TEXT,
    UNIQUE (date, symbol)
);
CREATE INDEX earnings_stock_active ON earnings_stock_tracking(status, ends_at, last_attempt_at);

CREATE TABLE earnings_stock_snapshot (
    tracking_id INTEGER NOT NULL REFERENCES earnings_stock_tracking(id) ON DELETE CASCADE,
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
