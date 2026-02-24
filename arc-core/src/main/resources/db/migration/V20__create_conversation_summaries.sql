CREATE TABLE IF NOT EXISTS conversation_summaries (
    session_id       VARCHAR(255) PRIMARY KEY,
    narrative        TEXT         NOT NULL,
    facts_json       TEXT         NOT NULL DEFAULT '[]',
    summarized_up_to INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
