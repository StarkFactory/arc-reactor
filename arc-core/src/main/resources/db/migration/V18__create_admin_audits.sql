-- Unified admin audit trail for compliance-sensitive operations.
-- Records who changed what (tool policy, MCP policy/server management, etc).

CREATE TABLE IF NOT EXISTS admin_audits (
    id            VARCHAR(36)   PRIMARY KEY,
    category      VARCHAR(64)   NOT NULL,
    action        VARCHAR(64)   NOT NULL,
    actor         VARCHAR(120)  NOT NULL,
    resource_type VARCHAR(120),
    resource_id   VARCHAR(200),
    detail        TEXT,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_admin_audits_created_at
    ON admin_audits(created_at);

CREATE INDEX IF NOT EXISTS idx_admin_audits_category_action
    ON admin_audits(category, action);
