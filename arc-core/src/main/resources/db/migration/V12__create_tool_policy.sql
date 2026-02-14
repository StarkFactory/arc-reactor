-- Tool policy settings (singleton row, admin-managed).
-- Used to manage write-tool restrictions and channel-based deny rules without redeploy.

CREATE TABLE IF NOT EXISTS tool_policy (
    id                  VARCHAR(64) PRIMARY KEY,
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,
    write_tool_names    TEXT         NOT NULL DEFAULT '[]',
    deny_write_channels TEXT         NOT NULL DEFAULT '[]',
    deny_write_message  TEXT         NOT NULL DEFAULT 'Error: This tool is not allowed in this channel',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
