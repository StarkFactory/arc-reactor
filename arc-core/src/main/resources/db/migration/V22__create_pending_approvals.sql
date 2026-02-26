-- Pending approvals for Human-in-the-Loop tool execution.
-- JDBC-backed coordination store for approval requests.

CREATE TABLE IF NOT EXISTS pending_approvals (
    id                 VARCHAR(36)   PRIMARY KEY,
    run_id             VARCHAR(120)  NOT NULL,
    user_id            VARCHAR(120)  NOT NULL,
    tool_name          VARCHAR(200)  NOT NULL,
    arguments          TEXT          NOT NULL,
    timeout_ms         BIGINT        NOT NULL DEFAULT 300000,
    status             VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    reason             TEXT,
    modified_arguments TEXT,
    requested_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at        TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pending_approvals_status_requested_at
    ON pending_approvals(status, requested_at DESC);

CREATE INDEX IF NOT EXISTS idx_pending_approvals_user_status_requested_at
    ON pending_approvals(user_id, status, requested_at DESC);
