-- Normalize legacy conversation_messages schemas before versioned migrations run.
-- This keeps baseline-from-legacy databases compatible with V4+ migrations.
DO
$$
BEGIN
    IF to_regclass('public.conversation_messages') IS NULL THEN
        RETURN;
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'conversation_messages'
          AND column_name = 'session_id'
    ) THEN
        ALTER TABLE conversation_messages ADD COLUMN session_id VARCHAR(255);
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'conversation_messages'
          AND column_name = 'thread_id'
    ) THEN
        UPDATE conversation_messages
        SET session_id = COALESCE(session_id, thread_id::text)
        WHERE session_id IS NULL;
    END IF;

    UPDATE conversation_messages
    SET session_id = COALESCE(session_id, 'legacy')
    WHERE session_id IS NULL;

    ALTER TABLE conversation_messages
        ALTER COLUMN session_id SET NOT NULL;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'conversation_messages'
          AND column_name = 'timestamp'
    ) THEN
        ALTER TABLE conversation_messages ADD COLUMN timestamp BIGINT;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'conversation_messages'
          AND column_name = 'message_ts'
    ) THEN
        UPDATE conversation_messages
        SET timestamp = CASE
            WHEN timestamp IS NOT NULL THEN timestamp
            WHEN message_ts ~ '^[0-9]+$' THEN message_ts::BIGINT
            ELSE (EXTRACT(EPOCH FROM COALESCE(created_at, CURRENT_TIMESTAMP)) * 1000)::BIGINT
        END
        WHERE timestamp IS NULL;
    ELSE
        UPDATE conversation_messages
        SET timestamp = (EXTRACT(EPOCH FROM COALESCE(created_at, CURRENT_TIMESTAMP)) * 1000)::BIGINT
        WHERE timestamp IS NULL;
    END IF;

    ALTER TABLE conversation_messages
        ALTER COLUMN timestamp SET NOT NULL;
END
$$;
