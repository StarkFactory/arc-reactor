-- Admin-managed MCP security policy (singleton row).
-- Allows runtime allowlist changes without redeploying.

CREATE TABLE IF NOT EXISTS mcp_security_policy (
    id                     VARCHAR(64) PRIMARY KEY,
    allowed_server_names   TEXT         NOT NULL DEFAULT '[]',
    max_tool_output_length INTEGER      NOT NULL DEFAULT 50000,
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
