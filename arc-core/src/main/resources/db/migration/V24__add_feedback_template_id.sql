ALTER TABLE feedback ADD COLUMN template_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_feedback_template_id ON feedback (template_id);
