CREATE TABLE IF NOT EXISTS scheduled_jobs (
    id                VARCHAR(36)     PRIMARY KEY,
    name              VARCHAR(200)    NOT NULL UNIQUE,
    description       TEXT,
    cron_expression   VARCHAR(100)    NOT NULL,
    timezone          VARCHAR(50)     NOT NULL DEFAULT 'Asia/Seoul',
    mcp_server_name   VARCHAR(100)    NOT NULL,
    tool_name         VARCHAR(200)    NOT NULL,
    tool_arguments    TEXT            DEFAULT '{}',
    slack_channel_id  VARCHAR(100),
    enabled           BOOLEAN         NOT NULL DEFAULT TRUE,
    last_run_at       TIMESTAMP,
    last_status       VARCHAR(20),
    last_result       TEXT,
    created_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_scheduled_jobs_enabled
    ON scheduled_jobs(enabled);
