CREATE TABLE IF NOT EXISTS mcp_servers (
    id              VARCHAR(36)     PRIMARY KEY,
    name            VARCHAR(100)    NOT NULL,
    description     VARCHAR(500),
    transport_type  VARCHAR(20)     NOT NULL,
    config          TEXT            NOT NULL DEFAULT '{}',
    version         VARCHAR(50),
    auto_connect    BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_mcp_servers_name ON mcp_servers(name);

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

