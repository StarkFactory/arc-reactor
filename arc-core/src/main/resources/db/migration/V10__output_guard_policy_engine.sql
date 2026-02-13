ALTER TABLE output_guard_rules
    ADD COLUMN IF NOT EXISTS priority INT NOT NULL DEFAULT 100;

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
