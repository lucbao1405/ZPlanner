CREATE TABLE google_identities (
    google_sub TEXT PRIMARY KEY,
    user_id TEXT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    email TEXT NOT NULL COLLATE NOCASE,
    created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

ALTER TABLE users ADD COLUMN auth_provider TEXT NOT NULL DEFAULT 'password' CHECK (auth_provider IN ('password', 'google'));
CREATE INDEX idx_google_identities_user_id ON google_identities(user_id);
