CREATE TABLE events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title TEXT NOT NULL CHECK (length(trim(title)) BETWEEN 1 AND 120),
    description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 2000),
    location TEXT NOT NULL DEFAULT '' CHECK (length(location) <= 500),
    start_at TEXT NOT NULL,
    end_at TEXT NOT NULL,
    reminder_minutes INTEGER NOT NULL DEFAULT 0 CHECK (reminder_minutes BETWEEN 0 AND 10080),
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    CHECK (start_at < end_at)
);

CREATE INDEX idx_events_owner_start_at ON events(owner_id, start_at);
CREATE INDEX idx_events_owner_end_at ON events(owner_id, end_at);
