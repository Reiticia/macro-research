-- Backend access keys are issued by Telegram, never read from auth.tokens.
CREATE TABLE api_key_request (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    requester_id INTEGER NOT NULL CHECK (requester_id > 0),
    message_id INTEGER NOT NULL CHECK (message_id > 0),
    requested_kind TEXT NOT NULL CHECK (requested_kind IN ('general', 'device')),
    state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'delivering', 'approved', 'rejected')),
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),
    decided_by INTEGER,
    UNIQUE (requester_id, message_id)
);
CREATE UNIQUE INDEX one_pending_key_request ON api_key_request(requester_id)
    WHERE state IN ('pending', 'delivering');

CREATE TABLE api_key (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id INTEGER NOT NULL REFERENCES api_key_request(id),
    owner_id INTEGER NOT NULL CHECK (owner_id > 0),
    kind TEXT NOT NULL CHECK (kind IN ('general', 'device')),
    token_hash TEXT NOT NULL UNIQUE CHECK (length(token_hash) = 64 AND token_hash NOT GLOB '*[^0-9a-f]*'),
    token_prefix TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'delivery_pending' CHECK (status IN ('delivery_pending', 'active', 'revoked')),
    device_hash TEXT CHECK (device_hash IS NULL OR (length(device_hash) = 64 AND device_hash NOT GLOB '*[^0-9a-f]*')),
    created_at INTEGER NOT NULL DEFAULT (unixepoch()),
    issued_by INTEGER NOT NULL,
    first_authorized_at INTEGER,
    last_used_at INTEGER,
    revoked_at INTEGER,
    revoked_by INTEGER,
    CHECK (kind = 'device' OR device_hash IS NULL)
);
CREATE INDEX api_key_owner ON api_key(owner_id, id DESC);
CREATE UNIQUE INDEX one_live_key_per_request ON api_key(request_id) WHERE status != 'revoked';
