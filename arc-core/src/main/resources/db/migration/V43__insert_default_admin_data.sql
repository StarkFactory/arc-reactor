-- =====================================================
-- V43: Admin 모듈 기본 데이터 (default tenant + 기본 가격표)
-- =====================================================
-- arc-reactor-admin UI가 정상 동작하려면 최소 1개의 tenant가 필요하다.
-- JWT의 기본 tenantId("default")에 매핑되는 slug를 가진 tenant를 생성한다.

-- 1. Default Tenant
INSERT INTO tenants (id, name, slug, plan, status,
                     max_requests_per_month, max_tokens_per_month, max_users, max_agents, max_mcp_servers,
                     billing_cycle_start, slo_availability, slo_latency_p99_ms,
                     created_at, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Default', 'default', 'ENTERPRISE', 'ACTIVE',
    9223372036854775807, 9223372036854775807, 2147483647, 2147483647, 2147483647,
    1, 0.995, 10000,
    NOW(), NOW()
)
ON CONFLICT (id) DO NOTHING;

-- 2. 기본 모델 가격표 (주요 LLM 모델)
INSERT INTO model_pricing (id, provider, model, input_token_price, output_token_price, cached_input_price, effective_from, created_at)
VALUES
    ('price-gemini-flash',    'gemini', 'gemini-2.5-flash',       0.15, 0.60, 0.0375, '2025-01-01', NOW()),
    ('price-gemini-pro',      'gemini', 'gemini-2.5-pro',         1.25, 10.00, 0.3125, '2025-01-01', NOW()),
    ('price-gpt4o',           'openai', 'gpt-4o',                 2.50, 10.00, 1.25,   '2025-01-01', NOW()),
    ('price-gpt4o-mini',      'openai', 'gpt-4o-mini',            0.15, 0.60, 0.075,   '2025-01-01', NOW()),
    ('price-claude-sonnet',   'anthropic', 'claude-sonnet-4-6',   3.00, 15.00, 1.50,   '2025-01-01', NOW()),
    ('price-claude-haiku',    'anthropic', 'claude-haiku-4-5',    0.80, 4.00, 0.40,    '2025-01-01', NOW())
ON CONFLICT (id) DO NOTHING;
