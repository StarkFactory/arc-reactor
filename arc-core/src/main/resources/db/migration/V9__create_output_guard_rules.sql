CREATE TABLE IF NOT EXISTS output_guard_rules (
    id          VARCHAR(36)     PRIMARY KEY,
    name        VARCHAR(120)    NOT NULL,
    pattern     TEXT            NOT NULL,
    action      VARCHAR(16)     NOT NULL,
    enabled     BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_output_guard_rules_enabled
    ON output_guard_rules(enabled);
