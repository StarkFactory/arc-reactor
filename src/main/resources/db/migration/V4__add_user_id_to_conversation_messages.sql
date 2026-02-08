-- Add user_id column to conversation_messages for per-user session isolation.
-- Existing rows get 'anonymous' as default (backward compatible).

ALTER TABLE conversation_messages
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(36) NOT NULL DEFAULT 'anonymous';

CREATE INDEX IF NOT EXISTS idx_conversation_messages_user_id
    ON conversation_messages (user_id);

CREATE INDEX IF NOT EXISTS idx_conversation_messages_user_session
    ON conversation_messages (user_id, session_id);
