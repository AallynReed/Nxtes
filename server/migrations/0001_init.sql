CREATE TABLE users (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    verified INTEGER NOT NULL DEFAULT 0,
    quota_bytes INTEGER,
    created_at INTEGER NOT NULL
);

CREATE TABLE devices (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    last_seen INTEGER NOT NULL
);

CREATE TABLE sessions (
    device_id TEXT PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    access_hash TEXT NOT NULL UNIQUE,
    access_expires INTEGER NOT NULL,
    refresh_hash TEXT NOT NULL UNIQUE,
    refresh_expires INTEGER NOT NULL
);

CREATE TABLE email_tokens (
    token_hash TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    new_email TEXT,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX email_tokens_user ON email_tokens (user_id, kind, created_at);

CREATE TABLE blobs (
    user_id TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sha256 TEXT NOT NULL,
    size INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, sha256)
);

CREATE TABLE items (
    user_id TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    seq INTEGER NOT NULL,
    deleted INTEGER NOT NULL DEFAULT 0,
    body TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, kind, id)
);

CREATE INDEX items_seq ON items (user_id, seq);

CREATE TABLE item_blobs (
    user_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    id TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    PRIMARY KEY (user_id, kind, id, sha256),
    FOREIGN KEY (user_id, kind, id) REFERENCES items (user_id, kind, id) ON DELETE CASCADE
);

CREATE INDEX item_blobs_sha ON item_blobs (user_id, sha256);

CREATE TABLE counters (
    user_id TEXT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    seq INTEGER NOT NULL
);
