-- 1) metric_agent_executions
CREATE TABLE IF NOT EXISTS metric_agent_executions (
    time                TIMESTAMPTZ     NOT NULL,
    tenant_id           VARCHAR(36)     NOT NULL,
    run_id              VARCHAR(120)    NOT NULL,
    user_id             VARCHAR(120),
    session_id          VARCHAR(120),
    channel             VARCHAR(50),

    success             BOOLEAN         NOT NULL,
    error_code          VARCHAR(50),
    error_class         VARCHAR(50),

    duration_ms         BIGINT          NOT NULL DEFAULT 0,
    llm_duration_ms     BIGINT          NOT NULL DEFAULT 0,
    tool_duration_ms    BIGINT          NOT NULL DEFAULT 0,
    guard_duration_ms   BIGINT          NOT NULL DEFAULT 0,
    queue_wait_ms       BIGINT          NOT NULL DEFAULT 0,

    is_streaming        BOOLEAN         NOT NULL DEFAULT FALSE,
    tool_count          INT             NOT NULL DEFAULT 0,

    persona_id          VARCHAR(120),
    prompt_template_id  VARCHAR(120),
    intent_category     VARCHAR(50),

    guard_rejected      BOOLEAN         NOT NULL DEFAULT FALSE,
    guard_stage         VARCHAR(50),
    guard_category      VARCHAR(50),

    retry_count         INT             NOT NULL DEFAULT 0,
    fallback_used       BOOLEAN         NOT NULL DEFAULT FALSE
);

SELECT create_hypertable('metric_agent_executions', 'time', chunk_time_interval => INTERVAL '1 day', if_not_exists => TRUE);
CREATE INDEX idx_exec_tenant_time ON metric_agent_executions(tenant_id, time DESC);
CREATE INDEX idx_exec_run_id ON metric_agent_executions(run_id);
CREATE INDEX idx_exec_tenant_user ON metric_agent_executions(tenant_id, user_id, time DESC);

-- 2) metric_tool_calls
CREATE TABLE IF NOT EXISTS metric_tool_calls (
    time                TIMESTAMPTZ     NOT NULL,
    tenant_id           VARCHAR(36)     NOT NULL,
    run_id              VARCHAR(120)    NOT NULL,

    tool_name           VARCHAR(255)    NOT NULL,
    tool_source         VARCHAR(20)     NOT NULL DEFAULT 'local',
    mcp_server_name     VARCHAR(100),
    call_index          INT             NOT NULL DEFAULT 0,

    success             BOOLEAN         NOT NULL,
    duration_ms         BIGINT          NOT NULL DEFAULT 0,
    error_class         VARCHAR(50),
    error_message       VARCHAR(500)
);

SELECT create_hypertable('metric_tool_calls', 'time', chunk_time_interval => INTERVAL '1 day', if_not_exists => TRUE);
CREATE INDEX idx_tool_tenant_time ON metric_tool_calls(tenant_id, time DESC);
CREATE INDEX idx_tool_run_id ON metric_tool_calls(run_id);

-- 3) metric_token_usage
CREATE TABLE IF NOT EXISTS metric_token_usage (
    time                    TIMESTAMPTZ     NOT NULL,
    tenant_id               VARCHAR(36)     NOT NULL,
    run_id                  VARCHAR(120)    NOT NULL,

    model                   VARCHAR(100)    NOT NULL,
    provider                VARCHAR(50)     NOT NULL,
    step_type               VARCHAR(30)     NOT NULL DEFAULT 'act',

    prompt_tokens           INT             NOT NULL DEFAULT 0,
    prompt_cached_tokens    INT             NOT NULL DEFAULT 0,
    completion_tokens       INT             NOT NULL DEFAULT 0,
    reasoning_tokens        INT             NOT NULL DEFAULT 0,
    total_tokens            INT             NOT NULL DEFAULT 0,

    estimated_cost_usd      NUMERIC(12, 8)  NOT NULL DEFAULT 0
);

SELECT create_hypertable('metric_token_usage', 'time', chunk_time_interval => INTERVAL '1 day', if_not_exists => TRUE);
CREATE INDEX idx_token_tenant_time ON metric_token_usage(tenant_id, time DESC);
CREATE INDEX idx_token_run_id ON metric_token_usage(run_id);

-- 4) metric_sessions
CREATE TABLE IF NOT EXISTS metric_sessions (
    time                        TIMESTAMPTZ     NOT NULL,
    tenant_id                   VARCHAR(36)     NOT NULL,
    session_id                  VARCHAR(120)    NOT NULL,
    user_id                     VARCHAR(120),
    channel                     VARCHAR(50),

    turn_count                  INT             NOT NULL DEFAULT 0,
    total_duration_ms           BIGINT          NOT NULL DEFAULT 0,
    total_tokens                BIGINT          NOT NULL DEFAULT 0,
    total_cost_usd              NUMERIC(12, 8)  NOT NULL DEFAULT 0,
    first_response_latency_ms   BIGINT          NOT NULL DEFAULT 0,

    outcome                     VARCHAR(30)     NOT NULL DEFAULT 'resolved',
    started_at                  TIMESTAMPTZ     NOT NULL,
    ended_at                    TIMESTAMPTZ     NOT NULL
);

SELECT create_hypertable('metric_sessions', 'time', chunk_time_interval => INTERVAL '7 days', if_not_exists => TRUE);
CREATE INDEX idx_session_tenant_time ON metric_sessions(tenant_id, time DESC);

-- 5) metric_guard_events
CREATE TABLE IF NOT EXISTS metric_guard_events (
    time                TIMESTAMPTZ     NOT NULL,
    tenant_id           VARCHAR(36)     NOT NULL,
    user_id             VARCHAR(120),
    channel             VARCHAR(50),

    stage               VARCHAR(50)     NOT NULL,
    category            VARCHAR(50)     NOT NULL,
    reason_class        VARCHAR(50),
    reason_detail       VARCHAR(500),
    is_output_guard     BOOLEAN         NOT NULL DEFAULT FALSE,
    action              VARCHAR(20)     NOT NULL DEFAULT 'rejected'
);

SELECT create_hypertable('metric_guard_events', 'time', chunk_time_interval => INTERVAL '1 day', if_not_exists => TRUE);
CREATE INDEX idx_guard_tenant_time ON metric_guard_events(tenant_id, time DESC);

-- 6) metric_mcp_health
CREATE TABLE IF NOT EXISTS metric_mcp_health (
    time                TIMESTAMPTZ     NOT NULL,
    tenant_id           VARCHAR(36)     NOT NULL,

    server_name         VARCHAR(100)    NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'CONNECTED',
    response_time_ms    BIGINT          NOT NULL DEFAULT 0,
    error_class         VARCHAR(50),
    error_message       VARCHAR(500),
    tool_count          INT             NOT NULL DEFAULT 0
);

SELECT create_hypertable('metric_mcp_health', 'time', chunk_time_interval => INTERVAL '1 day', if_not_exists => TRUE);
CREATE INDEX idx_mcp_tenant_time ON metric_mcp_health(tenant_id, time DESC);

-- 7) metric_eval_results
CREATE TABLE IF NOT EXISTS metric_eval_results (
    time                TIMESTAMPTZ     NOT NULL,
    tenant_id           VARCHAR(36)     NOT NULL,
    eval_run_id         VARCHAR(120)    NOT NULL,
    test_case_id        VARCHAR(120)    NOT NULL,

    pass                BOOLEAN         NOT NULL,
    score               DOUBLE PRECISION NOT NULL DEFAULT 0,
    latency_ms          BIGINT          NOT NULL DEFAULT 0,
    token_usage         INT             NOT NULL DEFAULT 0,
    cost                NUMERIC(12, 8)  NOT NULL DEFAULT 0,

    assertion_type      VARCHAR(50),
    failure_class       VARCHAR(50),
    failure_detail      VARCHAR(500),
    tags                TEXT
);

SELECT create_hypertable('metric_eval_results', 'time', chunk_time_interval => INTERVAL '7 days', if_not_exists => TRUE);
CREATE INDEX idx_eval_tenant_time ON metric_eval_results(tenant_id, time DESC);

-- 8) metric_spans (OTel SpanExporter target)
CREATE TABLE IF NOT EXISTS metric_spans (
    time                TIMESTAMPTZ     NOT NULL,
    tenant_id           VARCHAR(36)     NOT NULL,
    trace_id            VARCHAR(32)     NOT NULL,
    span_id             VARCHAR(16)     NOT NULL,
    parent_span_id      VARCHAR(16),

    run_id              VARCHAR(120),
    operation_name      VARCHAR(255)    NOT NULL,
    service_name        VARCHAR(100)    NOT NULL,

    duration_ms         BIGINT          NOT NULL,
    success             BOOLEAN         NOT NULL,
    error_class         VARCHAR(50),

    attributes          JSONB           DEFAULT '{}'
);

SELECT create_hypertable('metric_spans', 'time', chunk_time_interval => INTERVAL '1 day', if_not_exists => TRUE);
CREATE INDEX idx_spans_trace ON metric_spans(trace_id);
CREATE INDEX idx_spans_tenant_time ON metric_spans(tenant_id, time DESC);
CREATE INDEX idx_spans_run_id ON metric_spans(run_id);

-- 9) metric_audit_trail (separate retention, immutable)
CREATE TABLE IF NOT EXISTS metric_audit_trail (
    time                TIMESTAMPTZ     NOT NULL,
    tenant_id           VARCHAR(36)     NOT NULL,
    actor_id            VARCHAR(120),
    actor_email         VARCHAR(255),

    event_type          VARCHAR(50)     NOT NULL,
    resource_type       VARCHAR(50),
    resource_id         VARCHAR(120),
    detail              JSONB           DEFAULT '{}',

    source_ip           VARCHAR(45)
);

SELECT create_hypertable('metric_audit_trail', 'time', chunk_time_interval => INTERVAL '30 days', if_not_exists => TRUE);
CREATE INDEX idx_audit_tenant_time ON metric_audit_trail(tenant_id, time DESC);
CREATE INDEX idx_audit_event_type ON metric_audit_trail(event_type, time DESC);

-- Prevent UPDATE/DELETE on audit trail
CREATE OR REPLACE FUNCTION prevent_audit_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit trail records are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_trail_immutable
    BEFORE UPDATE OR DELETE ON metric_audit_trail
    FOR EACH ROW
    EXECUTE FUNCTION prevent_audit_modification();
