-- Execution history table
CREATE TABLE scheduled_job_executions (
    id VARCHAR(100) PRIMARY KEY,
    job_id VARCHAR(100) NOT NULL,
    job_name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL,
    result TEXT,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    dry_run BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_job_executions_job_id ON scheduled_job_executions(job_id);
CREATE INDEX idx_job_executions_started_at ON scheduled_job_executions(started_at DESC);

-- New columns on scheduled_jobs
ALTER TABLE scheduled_jobs ADD COLUMN retry_on_failure BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE scheduled_jobs ADD COLUMN max_retry_count INTEGER NOT NULL DEFAULT 3;
ALTER TABLE scheduled_jobs ADD COLUMN execution_timeout_ms BIGINT;
