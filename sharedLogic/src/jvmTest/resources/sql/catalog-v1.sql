CREATE TABLE job (
    id TEXT NOT NULL PRIMARY KEY,
    snapshot TEXT NOT NULL,
    state TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    next_sequence INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    hidden INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE item (
    id TEXT NOT NULL PRIMARY KEY,
    job_id TEXT NOT NULL REFERENCES job(id),
    ordinal INTEGER NOT NULL,
    plan TEXT NOT NULL,
    state TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    lease_owner TEXT,
    lease_epoch INTEGER NOT NULL DEFAULT 0,
    attempt_sequence INTEGER NOT NULL DEFAULT 0,
    active_attempt TEXT,
    UNIQUE(job_id, ordinal)
);
CREATE TABLE attempt (
    id TEXT NOT NULL PRIMARY KEY,
    item_id TEXT NOT NULL REFERENCES item(id),
    sequence INTEGER NOT NULL,
    state TEXT NOT NULL,
    started_at INTEGER NOT NULL,
    ended_at INTEGER,
    stop_requested INTEGER NOT NULL DEFAULT 0,
    workspace TEXT,
    validation TEXT,
    output TEXT,
    diagnostic TEXT,
    UNIQUE(item_id, sequence)
);
CREATE TABLE event (
    job_id TEXT NOT NULL REFERENCES job(id),
    sequence INTEGER NOT NULL,
    item_id TEXT,
    type TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY(job_id, sequence)
);
CREATE TABLE reservation (
    target TEXT NOT NULL PRIMARY KEY,
    item_id TEXT NOT NULL UNIQUE REFERENCES item(id)
);
CREATE INDEX item_ready ON item(job_id, state, ordinal);
CREATE INDEX attempt_item ON attempt(item_id, sequence);
CREATE UNIQUE INDEX one_active_attempt ON attempt(item_id) WHERE state IN ('RUNNING','VALIDATED','PUBLISHING','PUBLISHED');
