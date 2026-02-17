CREATE TABLE IF NOT EXISTS feedback (
    feedback_id VARCHAR(36) PRIMARY KEY,
    query TEXT NOT NULL,
    response TEXT NOT NULL,
    rating VARCHAR(20) NOT NULL,
    timestamp TIMESTAMP NOT NULL,
    comment TEXT,
    session_id VARCHAR(255),
    run_id VARCHAR(36),
    user_id VARCHAR(255),
    intent VARCHAR(50),
    domain VARCHAR(50),
    model VARCHAR(100),
    prompt_version INTEGER,
    tools_used TEXT,
    duration_ms BIGINT,
    tags TEXT
);

CREATE INDEX IF NOT EXISTS idx_feedback_rating ON feedback (rating);
CREATE INDEX IF NOT EXISTS idx_feedback_timestamp ON feedback (timestamp);
CREATE INDEX IF NOT EXISTS idx_feedback_session_id ON feedback (session_id);
CREATE INDEX IF NOT EXISTS idx_feedback_run_id ON feedback (run_id);
