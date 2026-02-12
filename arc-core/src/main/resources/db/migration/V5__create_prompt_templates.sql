-- Prompt Templates: named containers for versioned system prompts
CREATE TABLE IF NOT EXISTS prompt_templates (
    id          VARCHAR(36)  PRIMARY KEY,
    name        VARCHAR(255) NOT NULL UNIQUE,
    description TEXT         NOT NULL DEFAULT '',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Prompt Versions: versioned prompt content with lifecycle status
CREATE TABLE IF NOT EXISTS prompt_versions (
    id          VARCHAR(36)  PRIMARY KEY,
    template_id VARCHAR(36)  NOT NULL REFERENCES prompt_templates(id) ON DELETE CASCADE,
    version     INT          NOT NULL,
    content     TEXT         NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    change_log  TEXT         NOT NULL DEFAULT '',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(template_id, version)
);

CREATE INDEX idx_prompt_versions_template_id ON prompt_versions(template_id);
CREATE INDEX idx_prompt_versions_status ON prompt_versions(template_id, status);
