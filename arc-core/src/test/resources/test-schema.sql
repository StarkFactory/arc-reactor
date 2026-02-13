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

CREATE TABLE IF NOT EXISTS output_guard_rules (
    id          VARCHAR(36)     PRIMARY KEY,
    name        VARCHAR(120)    NOT NULL,
    pattern     TEXT            NOT NULL,
    action      VARCHAR(16)     NOT NULL,
    priority    INT             NOT NULL DEFAULT 100,
    enabled     BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_output_guard_rules_enabled
    ON output_guard_rules(enabled);

CREATE INDEX IF NOT EXISTS idx_output_guard_rules_priority
    ON output_guard_rules(enabled, priority, created_at);

CREATE TABLE IF NOT EXISTS output_guard_rule_audits (
    id          VARCHAR(36)     PRIMARY KEY,
    rule_id     VARCHAR(36),
    action      VARCHAR(32)     NOT NULL,
    actor       VARCHAR(120)    NOT NULL,
    detail      TEXT,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_output_guard_rule_audits_created_at
    ON output_guard_rule_audits(created_at);

CREATE INDEX IF NOT EXISTS idx_output_guard_rule_audits_rule_id
    ON output_guard_rule_audits(rule_id);
