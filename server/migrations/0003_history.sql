CREATE TABLE item_history (
    user_id TEXT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    deleted INTEGER NOT NULL,
    body TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    replaced_at INTEGER NOT NULL,
    PRIMARY KEY (user_id, kind, id, revision)
);

CREATE INDEX item_history_age ON item_history (replaced_at);

CREATE TABLE history_blobs (
    user_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    PRIMARY KEY (user_id, kind, id, revision, sha256),
    FOREIGN KEY (user_id, kind, id, revision) REFERENCES item_history (user_id, kind, id, revision) ON DELETE CASCADE
);

CREATE INDEX history_blobs_sha ON history_blobs (user_id, sha256);
