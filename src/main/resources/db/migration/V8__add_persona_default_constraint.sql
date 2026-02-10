-- Enforce at most one default persona at the database level.
-- Partial unique index: only rows with is_default = TRUE are constrained.
CREATE UNIQUE INDEX IF NOT EXISTS idx_personas_single_default
    ON personas (is_default)
    WHERE is_default = TRUE;
