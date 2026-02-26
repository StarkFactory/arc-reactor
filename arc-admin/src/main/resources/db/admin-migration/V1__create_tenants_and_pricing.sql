-- Tenants table
CREATE TABLE IF NOT EXISTS tenants (
    id                      VARCHAR(36)     PRIMARY KEY,
    name                    VARCHAR(255)    NOT NULL,
    slug                    VARCHAR(100)    NOT NULL UNIQUE,
    plan                    VARCHAR(20)     NOT NULL DEFAULT 'FREE',
    status                  VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',

    max_requests_per_month  BIGINT          NOT NULL DEFAULT 1000,
    max_tokens_per_month    BIGINT          NOT NULL DEFAULT 1000000,
    max_users               INT             NOT NULL DEFAULT 5,
    max_agents              INT             NOT NULL DEFAULT 3,
    max_mcp_servers         INT             NOT NULL DEFAULT 5,

    billing_cycle_start     INT             NOT NULL DEFAULT 1,
    billing_email           VARCHAR(255),

    slo_availability        DOUBLE PRECISION NOT NULL DEFAULT 0.995,
    slo_latency_p99_ms      BIGINT          NOT NULL DEFAULT 10000,

    metadata                JSONB           DEFAULT '{}',
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_slug ON tenants(slug);
CREATE INDEX idx_tenants_status ON tenants(status);

-- Model pricing table
CREATE TABLE IF NOT EXISTS model_pricing (
    id                          VARCHAR(36)     PRIMARY KEY,
    provider                    VARCHAR(50)     NOT NULL,
    model                       VARCHAR(100)    NOT NULL,

    prompt_price_per_1k         NUMERIC(12, 8)  NOT NULL DEFAULT 0,
    completion_price_per_1k     NUMERIC(12, 8)  NOT NULL DEFAULT 0,
    cached_input_price_per_1k   NUMERIC(12, 8)  NOT NULL DEFAULT 0,
    reasoning_price_per_1k      NUMERIC(12, 8)  NOT NULL DEFAULT 0,
    batch_prompt_price_per_1k   NUMERIC(12, 8)  NOT NULL DEFAULT 0,
    batch_completion_price_per_1k NUMERIC(12, 8) NOT NULL DEFAULT 0,

    effective_from              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    effective_to                TIMESTAMPTZ,

    UNIQUE(provider, model, effective_from)
);

CREATE INDEX idx_pricing_provider_model ON model_pricing(provider, model, effective_from DESC);
