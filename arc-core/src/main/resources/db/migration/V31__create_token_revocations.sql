CREATE TABLE IF NOT EXISTS auth_token_revocations (
    token_id        VARCHAR(255) PRIMARY KEY,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_auth_token_revocations_expires_at
    ON auth_token_revocations (expires_at);
