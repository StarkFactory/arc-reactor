CREATE TABLE IF NOT EXISTS user_identities (
    slack_user_id   VARCHAR(20)  PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255),
    jira_account_id VARCHAR(128),
    bitbucket_uuid  VARCHAR(128),
    created_at      TIMESTAMPTZ  DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_identities_email ON user_identities(email);
