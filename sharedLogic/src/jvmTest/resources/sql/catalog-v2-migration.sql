ALTER TABLE item ADD COLUMN superseded INTEGER NOT NULL DEFAULT 0;
CREATE TABLE commit_log (
    id TEXT NOT NULL PRIMARY KEY,
    item_id TEXT NOT NULL REFERENCES item(id),
    attempt_id TEXT UNIQUE REFERENCES attempt(id),
    restore_of TEXT REFERENCES commit_log(id),
    phase TEXT NOT NULL,
    payload TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE workspace (
    id TEXT NOT NULL PRIMARY KEY,
    volume_id TEXT NOT NULL UNIQUE,
    root TEXT NOT NULL UNIQUE
);
