-- Experiment tables for Prompt Lab persistent storage.
-- Stores experiments, individual trial results, and generated reports.

CREATE TABLE IF NOT EXISTS experiments (
    id                    VARCHAR(36)   PRIMARY KEY,
    name                  VARCHAR(255)  NOT NULL,
    description           TEXT          NOT NULL DEFAULT '',
    template_id           VARCHAR(255)  NOT NULL,
    baseline_version_id   VARCHAR(255)  NOT NULL,
    candidate_version_ids TEXT          NOT NULL,
    test_queries          TEXT          NOT NULL,
    evaluation_config     TEXT          NOT NULL,
    model                 VARCHAR(100),
    judge_model           VARCHAR(100),
    temperature           DOUBLE PRECISION NOT NULL DEFAULT 0.3,
    repetitions           INTEGER       NOT NULL DEFAULT 1,
    auto_generated        BOOLEAN       NOT NULL DEFAULT FALSE,
    status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    created_by            VARCHAR(255)  NOT NULL DEFAULT 'system',
    created_at            TIMESTAMP     NOT NULL,
    started_at            TIMESTAMP,
    completed_at          TIMESTAMP,
    error_message         TEXT
);

CREATE INDEX IF NOT EXISTS idx_experiments_status ON experiments (status);
CREATE INDEX IF NOT EXISTS idx_experiments_template_id ON experiments (template_id);
CREATE INDEX IF NOT EXISTS idx_experiments_created_at ON experiments (created_at DESC);

CREATE TABLE IF NOT EXISTS trials (
    id                    VARCHAR(36)   PRIMARY KEY,
    experiment_id         VARCHAR(36)   NOT NULL,
    prompt_version_id     VARCHAR(255)  NOT NULL,
    prompt_version_number INTEGER       NOT NULL,
    test_query            TEXT          NOT NULL,
    repetition_index      INTEGER       NOT NULL DEFAULT 0,
    response              TEXT,
    success               BOOLEAN       NOT NULL DEFAULT FALSE,
    error_message         TEXT,
    tools_used            TEXT,
    token_usage           TEXT,
    duration_ms           BIGINT        NOT NULL DEFAULT 0,
    evaluations           TEXT,
    executed_at           TIMESTAMP     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trials_experiment_id ON trials (experiment_id);

CREATE TABLE IF NOT EXISTS experiment_reports (
    experiment_id         VARCHAR(36)   PRIMARY KEY,
    report_data           TEXT          NOT NULL,
    created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
