-- 10) metric_quota_events
CREATE TABLE IF NOT EXISTS metric_quota_events (
    time                TIMESTAMPTZ     NOT NULL,
    tenant_id           VARCHAR(36)     NOT NULL,

    action              VARCHAR(30)     NOT NULL,
    current_usage       BIGINT          NOT NULL DEFAULT 0,
    quota_limit         BIGINT          NOT NULL DEFAULT 0,
    usage_percent       DOUBLE PRECISION NOT NULL DEFAULT 0,
    reason              VARCHAR(500)
);

SELECT create_hypertable('metric_quota_events', 'time', chunk_time_interval => INTERVAL '7 days', if_not_exists => TRUE);
CREATE INDEX idx_quota_tenant_time ON metric_quota_events(tenant_id, time DESC);
CREATE INDEX idx_quota_action ON metric_quota_events(action, time DESC);

-- 11) metric_hitl_events
CREATE TABLE IF NOT EXISTS metric_hitl_events (
    time                TIMESTAMPTZ     NOT NULL,
    tenant_id           VARCHAR(36)     NOT NULL,
    run_id              VARCHAR(120)    NOT NULL,

    tool_name           VARCHAR(255)    NOT NULL,
    approved            BOOLEAN         NOT NULL,
    wait_ms             BIGINT          NOT NULL DEFAULT 0,
    rejection_reason    VARCHAR(500)
);

SELECT create_hypertable('metric_hitl_events', 'time', chunk_time_interval => INTERVAL '7 days', if_not_exists => TRUE);
CREATE INDEX idx_hitl_tenant_time ON metric_hitl_events(tenant_id, time DESC);
CREATE INDEX idx_hitl_run_id ON metric_hitl_events(run_id);

-- Compression policies (match existing 7-day schedule)
ALTER TABLE metric_quota_events SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id',
    timescaledb.compress_orderby = 'time DESC'
);
SELECT add_compression_policy('metric_quota_events', INTERVAL '7 days', if_not_exists => TRUE);

ALTER TABLE metric_hitl_events SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id',
    timescaledb.compress_orderby = 'time DESC'
);
SELECT add_compression_policy('metric_hitl_events', INTERVAL '7 days', if_not_exists => TRUE);

-- Retention policies (90-day, match existing raw tables)
SELECT add_retention_policy('metric_quota_events', INTERVAL '90 days', if_not_exists => TRUE);
SELECT add_retention_policy('metric_hitl_events', INTERVAL '90 days', if_not_exists => TRUE);
