-- Conversation Messages table for JdbcMemoryStore
-- Stores multi-turn conversation history per session.
-- This migration is written for PostgreSQL. For other databases,
-- replace BIGSERIAL with your DB's auto-increment syntax.

CREATE TABLE IF NOT EXISTS conversation_messages (
    id          BIGSERIAL PRIMARY KEY,
    session_id  VARCHAR(255)  NOT NULL,
    role        VARCHAR(20)   NOT NULL,
    content     TEXT          NOT NULL,
    timestamp   BIGINT        NOT NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_conversation_messages_session_id
    ON conversation_messages (session_id);

CREATE INDEX IF NOT EXISTS idx_conversation_messages_session_timestamp
    ON conversation_messages (session_id, timestamp);
