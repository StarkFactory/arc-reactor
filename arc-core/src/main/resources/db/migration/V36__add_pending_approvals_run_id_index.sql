-- Index for fast lookup by run_id in pending_approvals
CREATE INDEX IF NOT EXISTS idx_pending_approvals_run_id ON pending_approvals(run_id);
