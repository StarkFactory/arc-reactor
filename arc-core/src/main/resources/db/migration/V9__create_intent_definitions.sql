-- Intent definitions for intent classification (admin-managed).
CREATE TABLE IF NOT EXISTS intent_definitions (
    name         VARCHAR(100) PRIMARY KEY,
    description  TEXT NOT NULL,
    examples     TEXT NOT NULL DEFAULT '[]',
    keywords     TEXT NOT NULL DEFAULT '[]',
    profile      TEXT NOT NULL DEFAULT '{}',
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_intent_definitions_enabled ON intent_definitions(enabled);

