-- Independent daily snapshots: never share IDs or lifecycle with macro events.
CREATE TABLE earnings_calendar (
    date TEXT PRIMARY KEY NOT NULL,
    payload TEXT NOT NULL
);
