-- Add channel-scoped allowlist for deny channels.
-- This migration is intentionally additive (do not edit existing migrations).

ALTER TABLE tool_policy
    ADD COLUMN IF NOT EXISTS allow_write_tool_names_by_channel TEXT NOT NULL DEFAULT '{}';

