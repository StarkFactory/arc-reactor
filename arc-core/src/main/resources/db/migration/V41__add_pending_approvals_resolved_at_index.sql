-- pending_approvals cleanup 쿼리 성능 개선을 위한 인덱스 추가.
-- cleanupResolvedRows()가 resolved_at 기반 DELETE를 수행한다.
CREATE INDEX IF NOT EXISTS idx_pending_approvals_resolved_at
    ON pending_approvals(resolved_at);
