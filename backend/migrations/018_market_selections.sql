CREATE TABLE IF NOT EXISTS event_market_selection (
    event_id INTEGER PRIMARY KEY REFERENCES economic_event(id) ON DELETE CASCADE,
    symbols_json TEXT NOT NULL,
    probabilities_json TEXT NOT NULL,
    source TEXT NOT NULL CHECK (source IN ('jev', 'category_fallback', 'all_symbols_fallback')),
    model TEXT NOT NULL,
    selected_at TEXT NOT NULL
);
