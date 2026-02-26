-- Index to support retention cleanup of resolved approvals.
CREATE INDEX IF NOT EXISTS idx_pending_approvals_status_resolved_at
    ON pending_approvals(status, resolved_at);
