-- Hourly execution aggregates
CREATE MATERIALIZED VIEW IF NOT EXISTS metric_executions_hourly
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', e.time) AS bucket,
    e.tenant_id,
    e.channel,
    COALESCE(t.model, 'unknown') AS model,

    COUNT(*) AS total_requests,
    COUNT(*) FILTER (WHERE e.success = TRUE) AS successful,
    COUNT(*) FILTER (WHERE e.success = FALSE) AS failed,
    COUNT(*) FILTER (WHERE e.guard_rejected = TRUE) AS guard_rejections,

    AVG(e.duration_ms)::BIGINT AS avg_duration_ms,
    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY e.duration_ms)::BIGINT AS p95_duration_ms,
    PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY e.duration_ms)::BIGINT AS p99_duration_ms,
    MAX(e.duration_ms) AS max_duration_ms,

    AVG(e.llm_duration_ms)::BIGINT AS avg_llm_duration_ms,
    AVG(e.tool_duration_ms)::BIGINT AS avg_tool_duration_ms,

    SUM(COALESCE(t.prompt_tokens, 0))::BIGINT AS total_prompt_tokens,
    SUM(COALESCE(t.completion_tokens, 0))::BIGINT AS total_completion_tokens,
    SUM(COALESCE(t.reasoning_tokens, 0))::BIGINT AS total_reasoning_tokens,
    SUM(COALESCE(t.prompt_cached_tokens, 0))::BIGINT AS total_cached_tokens,
    SUM(COALESCE(t.estimated_cost_usd, 0))::NUMERIC(12,8) AS total_cost_usd,

    COUNT(DISTINCT e.user_id) AS unique_users,
    COUNT(DISTINCT e.session_id) AS unique_sessions,

    -- Apdex counts
    COUNT(*) FILTER (WHERE e.duration_ms < 5000) AS apdex_satisfied,
    COUNT(*) FILTER (WHERE e.duration_ms >= 5000 AND e.duration_ms < 20000) AS apdex_tolerating,
    COUNT(*) FILTER (WHERE e.duration_ms >= 20000) AS apdex_frustrated
FROM metric_agent_executions e
LEFT JOIN LATERAL (
    SELECT
        tu.model,
        SUM(tu.prompt_tokens) AS prompt_tokens,
        SUM(tu.completion_tokens) AS completion_tokens,
        SUM(tu.reasoning_tokens) AS reasoning_tokens,
        SUM(tu.prompt_cached_tokens) AS prompt_cached_tokens,
        SUM(tu.estimated_cost_usd) AS estimated_cost_usd
    FROM metric_token_usage tu
    WHERE tu.run_id = e.run_id AND tu.tenant_id = e.tenant_id
    GROUP BY tu.model
) t ON TRUE
GROUP BY bucket, e.tenant_id, e.channel, COALESCE(t.model, 'unknown')
WITH NO DATA;

SELECT add_continuous_aggregate_policy('metric_executions_hourly',
    start_offset => INTERVAL '3 hours',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists => TRUE
);

-- Daily execution aggregates
CREATE MATERIALIZED VIEW IF NOT EXISTS metric_executions_daily
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 day', bucket) AS bucket,
    tenant_id,
    channel,
    model,
    SUM(total_requests) AS total_requests,
    SUM(successful) AS successful,
    SUM(failed) AS failed,
    SUM(guard_rejections) AS guard_rejections,
    AVG(avg_duration_ms)::BIGINT AS avg_duration_ms,
    MAX(p95_duration_ms) AS p95_duration_ms,
    MAX(p99_duration_ms) AS p99_duration_ms,
    MAX(max_duration_ms) AS max_duration_ms,
    AVG(avg_llm_duration_ms)::BIGINT AS avg_llm_duration_ms,
    AVG(avg_tool_duration_ms)::BIGINT AS avg_tool_duration_ms,
    SUM(total_prompt_tokens) AS total_prompt_tokens,
    SUM(total_completion_tokens) AS total_completion_tokens,
    SUM(total_reasoning_tokens) AS total_reasoning_tokens,
    SUM(total_cached_tokens) AS total_cached_tokens,
    SUM(total_cost_usd)::NUMERIC(12,8) AS total_cost_usd,
    SUM(unique_users) AS unique_users,
    SUM(unique_sessions) AS unique_sessions,
    SUM(apdex_satisfied) AS apdex_satisfied,
    SUM(apdex_tolerating) AS apdex_tolerating,
    SUM(apdex_frustrated) AS apdex_frustrated
FROM metric_executions_hourly
GROUP BY time_bucket('1 day', bucket), tenant_id, channel, model
WITH NO DATA;

SELECT add_continuous_aggregate_policy('metric_executions_daily',
    start_offset => INTERVAL '3 days',
    end_offset => INTERVAL '1 day',
    schedule_interval => INTERVAL '1 day',
    if_not_exists => TRUE
);

-- Hourly tool call aggregates
CREATE MATERIALIZED VIEW IF NOT EXISTS metric_tool_calls_hourly
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 hour', time) AS bucket,
    tenant_id,
    tool_name,
    tool_source,
    mcp_server_name,

    COUNT(*) AS total_calls,
    COUNT(*) FILTER (WHERE success = TRUE) AS successful,
    COUNT(*) FILTER (WHERE success = FALSE) AS failed,
    AVG(duration_ms)::BIGINT AS avg_duration_ms,
    PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms)::BIGINT AS p95_duration_ms,
    MAX(duration_ms) AS max_duration_ms
FROM metric_tool_calls
GROUP BY bucket, tenant_id, tool_name, tool_source, mcp_server_name
WITH NO DATA;

SELECT add_continuous_aggregate_policy('metric_tool_calls_hourly',
    start_offset => INTERVAL '3 hours',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour',
    if_not_exists => TRUE
);

-- Daily session aggregates
CREATE MATERIALIZED VIEW IF NOT EXISTS metric_sessions_daily
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 day', time) AS bucket,
    tenant_id,
    channel,

    COUNT(*) AS total_sessions,
    AVG(turn_count)::INT AS avg_turns,
    AVG(total_duration_ms)::BIGINT AS avg_duration_ms,
    SUM(total_tokens) AS total_tokens,
    SUM(total_cost_usd)::NUMERIC(12,8) AS total_cost,

    COUNT(*) FILTER (WHERE outcome = 'resolved') AS resolved,
    COUNT(*) FILTER (WHERE outcome = 'abandoned') AS abandoned,
    COUNT(*) FILTER (WHERE outcome = 'escalated') AS escalated,
    COUNT(*) FILTER (WHERE outcome = 'errored') AS errored,

    AVG(first_response_latency_ms)::BIGINT AS avg_first_response_ms
FROM metric_sessions
GROUP BY bucket, tenant_id, channel
WITH NO DATA;

SELECT add_continuous_aggregate_policy('metric_sessions_daily',
    start_offset => INTERVAL '3 days',
    end_offset => INTERVAL '1 day',
    schedule_interval => INTERVAL '1 day',
    if_not_exists => TRUE
);
