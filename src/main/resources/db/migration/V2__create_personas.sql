-- Personas table for managing system prompt templates.
-- Each persona has a name and a system prompt that can be selected by users.
-- At most one persona should have is_default = TRUE.

CREATE TABLE IF NOT EXISTS personas (
    id            VARCHAR(36)   PRIMARY KEY,
    name          VARCHAR(200)  NOT NULL,
    system_prompt TEXT          NOT NULL,
    is_default    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Default persona seed data
INSERT INTO personas (id, name, system_prompt, is_default)
VALUES (
    'default',
    'Default Assistant',
    'You are a helpful AI assistant. You can use tools when needed. Answer in the same language as the user''s message.',
    TRUE
);
