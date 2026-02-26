-- Alert rules
CREATE TABLE IF NOT EXISTS alert_rules (
    id              VARCHAR(36)     PRIMARY KEY,
    tenant_id       VARCHAR(36)     REFERENCES tenants(id),
    name            VARCHAR(255)    NOT NULL,
    description     TEXT            DEFAULT '',
    type            VARCHAR(30)     NOT NULL,
    severity        VARCHAR(20)     NOT NULL DEFAULT 'WARNING',
    metric          VARCHAR(100)    NOT NULL,
    threshold       DOUBLE PRECISION NOT NULL,
    window_minutes  INT             NOT NULL DEFAULT 15,
    enabled         BOOLEAN         NOT NULL DEFAULT TRUE,
    platform_only   BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alert_rules_tenant ON alert_rules(tenant_id);

-- Alert instances
CREATE TABLE IF NOT EXISTS alert_instances (
    id              VARCHAR(36)     PRIMARY KEY,
    rule_id         VARCHAR(36)     NOT NULL REFERENCES alert_rules(id),
    tenant_id       VARCHAR(36)     REFERENCES tenants(id),
    severity        VARCHAR(20)     NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    message         TEXT            NOT NULL,
    metric_value    DOUBLE PRECISION NOT NULL DEFAULT 0,
    threshold       DOUBLE PRECISION NOT NULL DEFAULT 0,
    fired_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,
    acknowledged_by VARCHAR(120)
);

CREATE INDEX idx_alert_instances_tenant ON alert_instances(tenant_id, status);
CREATE INDEX idx_alert_instances_rule ON alert_instances(rule_id);

-- SLO configuration per tenant
CREATE TABLE IF NOT EXISTS slo_config (
    id                      VARCHAR(36)     PRIMARY KEY,
    tenant_id               VARCHAR(36)     NOT NULL REFERENCES tenants(id) UNIQUE,
    availability_target     DOUBLE PRECISION NOT NULL DEFAULT 0.995,
    latency_p99_target_ms   BIGINT          NOT NULL DEFAULT 10000,
    apdex_satisfied_ms      BIGINT          NOT NULL DEFAULT 5000,
    apdex_tolerating_ms     BIGINT          NOT NULL DEFAULT 20000,
    error_budget_window_days INT            NOT NULL DEFAULT 30,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
