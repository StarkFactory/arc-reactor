ALTER TABLE personas ADD COLUMN description        TEXT;
ALTER TABLE personas ADD COLUMN response_guideline TEXT;
ALTER TABLE personas ADD COLUMN welcome_message    TEXT;
ALTER TABLE personas ADD COLUMN icon               VARCHAR(20);
ALTER TABLE personas ADD COLUMN is_active          BOOLEAN NOT NULL DEFAULT TRUE;
