-- Performance indexes for frequently executed queries on large datasets.

-- Index for session preview subquery (listSessions, listSessionsByUserId):
-- WHERE session_id = ? AND role = 'user' ORDER BY id ASC LIMIT 1
CREATE INDEX IF NOT EXISTS idx_conv_msg_session_role_id
    ON conversation_messages(session_id, role, id);

-- Index for eviction query (evictOldMessages):
-- WHERE session_id = ? ORDER BY id DESC LIMIT ?
CREATE INDEX IF NOT EXISTS idx_conv_msg_session_id_desc
    ON conversation_messages(session_id, id DESC);

-- Index for feedback list by session:
-- WHERE session_id = ? ORDER BY timestamp DESC
CREATE INDEX IF NOT EXISTS idx_feedback_session_ts
    ON feedback(session_id, timestamp DESC);
