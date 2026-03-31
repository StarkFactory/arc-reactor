-- =============================================================================
-- V42: Admin 모듈에서 사용하는 모든 테이블을 생성한다.
--
-- arc-admin/src/main/resources/db/admin-migration/ 의 TimescaleDB 전용 마이그레이션을
-- 일반 PostgreSQL 호환 DDL로 통합한 버전이다.
-- TimescaleDB 확장 없이도 동작하며, hypertable/continuous aggregate/compression/retention
-- 정책은 포함하지 않는다 (TimescaleDB 환경에서는 admin-migration을 별도로 적용할 것).
-- =============================================================================

-- ─── 1. tenants ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tenants (
    id                      VARCHAR(36)      PRIMARY KEY,
    name                    VARCHAR(255)     NOT NULL,
    slug                    VARCHAR(100)     NOT NULL UNIQUE,
    plan                    VARCHAR(20)      NOT NULL DEFAULT 'FREE',
    status                  VARCHAR(20)      NOT NULL DEFAULT 'ACTIVE',

    max_requests_per_month  BIGINT           NOT NULL DEFAULT 1000,
    max_tokens_per_month    BIGINT           NOT NULL DEFAULT 1000000,
    max_users               INT              NOT NULL DEFAULT 5,
    max_agents              INT              NOT NULL DEFAULT 3,
    max_mcp_servers         INT              NOT NULL DEFAULT 5,

    billing_cycle_start     INT              NOT NULL DEFAULT 1,
    billing_email           VARCHAR(255),

    slo_availability        DOUBLE PRECISION NOT NULL DEFAULT 0.995,
    slo_latency_p99_ms      BIGINT           NOT NULL DEFAULT 10000,

    metadata                JSONB            DEFAULT '{}',
    created_at              TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tenants_slug ON tenants(slug);
CREATE INDEX IF NOT EXISTS idx_tenants_status ON tenants(status);

-- ─── 2. model_pricing ───────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS model_pricing (
    id                           VARCHAR(36)     PRIMARY KEY,
    provider                     VARCHAR(50)     NOT NULL,
    model                        VARCHAR(100)    NOT NULL,

    prompt_price_per_1k          NUMERIC(12, 8)  NOT NULL DEFAULT 0,
    completion_price_per_1k      NUMERIC(12, 8)  NOT NULL DEFAULT 0,
    cached_input_price_per_1k    NUMERIC(12, 8)  NOT NULL DEFAULT 0,
    reasoning_price_per_1k       NUMERIC(12, 8)  NOT NULL DEFAULT 0,
    batch_prompt_price_per_1k    NUMERIC(12, 8)  NOT NULL DEFAULT 0,
    batch_completion_price_per_1k NUMERIC(12, 8) NOT NULL DEFAULT 0,

    effective_from               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    effective_to                 TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_pricing_provider_model_effective
    ON model_pricing(provider, model, effective_from);
CREATE INDEX IF NOT EXISTS idx_pricing_provider_model
    ON model_pricing(provider, model, effective_from DESC);

-- ─── 3. metric_agent_executions ─────────────────────────────────────────────
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

CREATE INDEX IF NOT EXISTS idx_exec_tenant_time
    ON metric_agent_executions(tenant_id, time DESC);
CREATE INDEX IF NOT EXISTS idx_exec_run_id
    ON metric_agent_executions(run_id);
CREATE INDEX IF NOT EXISTS idx_exec_tenant_user
    ON metric_agent_executions(tenant_id, user_id, time DESC);

-- ─── 4. metric_tool_calls ───────────────────────────────────────────────────
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

CREATE INDEX IF NOT EXISTS idx_tool_tenant_time
    ON metric_tool_calls(tenant_id, time DESC);
CREATE INDEX IF NOT EXISTS idx_tool_run_id
    ON metric_tool_calls(run_id);

-- ─── 5. metric_token_usage ──────────────────────────────────────────────────
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

CREATE INDEX IF NOT EXISTS idx_token_tenant_time
    ON metric_token_usage(tenant_id, time DESC);
CREATE INDEX IF NOT EXISTS idx_token_run_id
    ON metric_token_usage(run_id);

-- ─── 6. metric_sessions ─────────────────────────────────────────────────────
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

CREATE INDEX IF NOT EXISTS idx_session_tenant_time
    ON metric_sessions(tenant_id, time DESC);

-- ─── 7. metric_guard_events ─────────────────────────────────────────────────
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

CREATE INDEX IF NOT EXISTS idx_guard_tenant_time
    ON metric_guard_events(tenant_id, time DESC);

-- ─── 8. metric_mcp_health ───────────────────────────────────────────────────
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

CREATE INDEX IF NOT EXISTS idx_mcp_tenant_time
    ON metric_mcp_health(tenant_id, time DESC);

-- ─── 9. metric_eval_results ─────────────────────────────────────────────────
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

CREATE INDEX IF NOT EXISTS idx_eval_tenant_time
    ON metric_eval_results(tenant_id, time DESC);

-- ─── 10. metric_spans ───────────────────────────────────────────────────────
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

CREATE INDEX IF NOT EXISTS idx_spans_trace
    ON metric_spans(trace_id);
CREATE INDEX IF NOT EXISTS idx_spans_tenant_time
    ON metric_spans(tenant_id, time DESC);
CREATE INDEX IF NOT EXISTS idx_spans_run_id
    ON metric_spans(run_id);

-- ─── 11. metric_audit_trail ─────────────────────────────────────────────────
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

CREATE INDEX IF NOT EXISTS idx_audit_tenant_time
    ON metric_audit_trail(tenant_id, time DESC);
CREATE INDEX IF NOT EXISTS idx_audit_event_type
    ON metric_audit_trail(event_type, time DESC);

-- ─── 12. metric_quota_events ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS metric_quota_events (
    time                TIMESTAMPTZ      NOT NULL,
    tenant_id           VARCHAR(36)      NOT NULL,

    action              VARCHAR(30)      NOT NULL,
    current_usage       BIGINT           NOT NULL DEFAULT 0,
    quota_limit         BIGINT           NOT NULL DEFAULT 0,
    usage_percent       DOUBLE PRECISION NOT NULL DEFAULT 0,
    reason              VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_quota_tenant_time
    ON metric_quota_events(tenant_id, time DESC);
CREATE INDEX IF NOT EXISTS idx_quota_action
    ON metric_quota_events(action, time DESC);

-- ─── 13. metric_hitl_events ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS metric_hitl_events (
    time                TIMESTAMPTZ     NOT NULL,
    tenant_id           VARCHAR(36)     NOT NULL,
    run_id              VARCHAR(120)    NOT NULL,

    tool_name           VARCHAR(255)    NOT NULL,
    approved            BOOLEAN         NOT NULL,
    wait_ms             BIGINT          NOT NULL DEFAULT 0,
    rejection_reason    VARCHAR(500)
);

CREATE INDEX IF NOT EXISTS idx_hitl_tenant_time
    ON metric_hitl_events(tenant_id, time DESC);
CREATE INDEX IF NOT EXISTS idx_hitl_run_id
    ON metric_hitl_events(run_id);

-- ─── 14. alert_rules ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS alert_rules (
    id              VARCHAR(36)      PRIMARY KEY,
    tenant_id       VARCHAR(36)      REFERENCES tenants(id),
    name            VARCHAR(255)     NOT NULL,
    description     TEXT             DEFAULT '',
    type            VARCHAR(30)      NOT NULL,
    severity        VARCHAR(20)      NOT NULL DEFAULT 'WARNING',
    metric          VARCHAR(100)     NOT NULL,
    threshold       DOUBLE PRECISION NOT NULL,
    window_minutes  INT              NOT NULL DEFAULT 15,
    enabled         BOOLEAN          NOT NULL DEFAULT TRUE,
    platform_only   BOOLEAN          NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_alert_rules_tenant
    ON alert_rules(tenant_id);

-- ─── 15. alert_instances ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS alert_instances (
    id              VARCHAR(36)      PRIMARY KEY,
    rule_id         VARCHAR(36)      NOT NULL REFERENCES alert_rules(id),
    tenant_id       VARCHAR(36)      REFERENCES tenants(id),
    severity        VARCHAR(20)      NOT NULL,
    status          VARCHAR(20)      NOT NULL DEFAULT 'ACTIVE',
    message         TEXT             NOT NULL,
    metric_value    DOUBLE PRECISION NOT NULL DEFAULT 0,
    threshold       DOUBLE PRECISION NOT NULL DEFAULT 0,
    fired_at        TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,
    acknowledged_by VARCHAR(120)
);

CREATE INDEX IF NOT EXISTS idx_alert_instances_tenant
    ON alert_instances(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_alert_instances_rule
    ON alert_instances(rule_id);

-- ─── 16. slo_config ─────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS slo_config (
    id                      VARCHAR(36)      PRIMARY KEY,
    tenant_id               VARCHAR(36)      NOT NULL REFERENCES tenants(id) UNIQUE,
    availability_target     DOUBLE PRECISION NOT NULL DEFAULT 0.995,
    latency_p99_target_ms   BIGINT           NOT NULL DEFAULT 10000,
    apdex_satisfied_ms      BIGINT           NOT NULL DEFAULT 5000,
    apdex_tolerating_ms     BIGINT           NOT NULL DEFAULT 20000,
    error_budget_window_days INT             NOT NULL DEFAULT 30,
    created_at              TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

-- ─── 17. users.tenant_id (admin V5) ────────────────────────────────────────
-- tenant_id = NULL 은 Platform Admin을 의미
ALTER TABLE users ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(36) REFERENCES tenants(id);
CREATE INDEX IF NOT EXISTS idx_users_tenant ON users(tenant_id);
