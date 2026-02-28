-- Add agent execution mode support to scheduled_jobs table.
-- Existing rows default to MCP_TOOL (original behavior preserved).

ALTER TABLE scheduled_jobs ADD COLUMN job_type VARCHAR(20) NOT NULL DEFAULT 'MCP_TOOL';

-- Make MCP-specific columns nullable — not required for AGENT mode jobs.
ALTER TABLE scheduled_jobs ALTER COLUMN mcp_server_name DROP NOT NULL;
ALTER TABLE scheduled_jobs ALTER COLUMN tool_name DROP NOT NULL;

-- New columns for AGENT mode.
ALTER TABLE scheduled_jobs ADD COLUMN agent_prompt        TEXT;
ALTER TABLE scheduled_jobs ADD COLUMN persona_id          VARCHAR(100);
ALTER TABLE scheduled_jobs ADD COLUMN agent_system_prompt TEXT;
ALTER TABLE scheduled_jobs ADD COLUMN agent_model         VARCHAR(100);
ALTER TABLE scheduled_jobs ADD COLUMN agent_max_tool_calls INTEGER;
