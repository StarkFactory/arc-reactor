-- Add allowlist column for exceptions in deny-write channels.
-- This migration is intentionally additive (do not edit existing migrations).

ALTER TABLE tool_policy
    ADD COLUMN IF NOT EXISTS allow_write_tool_names_in_deny_channels TEXT NOT NULL DEFAULT '[]';

