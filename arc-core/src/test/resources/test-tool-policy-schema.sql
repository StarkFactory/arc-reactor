CREATE TABLE IF NOT EXISTS tool_policy (
    id                  VARCHAR(64) PRIMARY KEY,
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,
    write_tool_names    TEXT         NOT NULL DEFAULT '[]',
    deny_write_channels TEXT         NOT NULL DEFAULT '[]',
    allow_write_tool_names_in_deny_channels TEXT NOT NULL DEFAULT '[]',
    deny_write_message  TEXT         NOT NULL DEFAULT 'Error: This tool is not allowed in this channel',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
