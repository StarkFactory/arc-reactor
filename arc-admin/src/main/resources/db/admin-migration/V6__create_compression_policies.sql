-- Compression policies (7 days after write, 90-95% storage savings)

ALTER TABLE metric_agent_executions SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id',
    timescaledb.compress_orderby = 'time DESC'
);
SELECT add_compression_policy('metric_agent_executions', INTERVAL '7 days', if_not_exists => TRUE);

ALTER TABLE metric_tool_calls SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id',
    timescaledb.compress_orderby = 'time DESC'
);
SELECT add_compression_policy('metric_tool_calls', INTERVAL '7 days', if_not_exists => TRUE);

ALTER TABLE metric_token_usage SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id',
    timescaledb.compress_orderby = 'time DESC'
);
SELECT add_compression_policy('metric_token_usage', INTERVAL '7 days', if_not_exists => TRUE);

ALTER TABLE metric_sessions SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id',
    timescaledb.compress_orderby = 'time DESC'
);
SELECT add_compression_policy('metric_sessions', INTERVAL '7 days', if_not_exists => TRUE);

ALTER TABLE metric_guard_events SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id',
    timescaledb.compress_orderby = 'time DESC'
);
SELECT add_compression_policy('metric_guard_events', INTERVAL '7 days', if_not_exists => TRUE);

ALTER TABLE metric_mcp_health SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id',
    timescaledb.compress_orderby = 'time DESC'
);
SELECT add_compression_policy('metric_mcp_health', INTERVAL '7 days', if_not_exists => TRUE);

ALTER TABLE metric_eval_results SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id',
    timescaledb.compress_orderby = 'time DESC'
);
SELECT add_compression_policy('metric_eval_results', INTERVAL '7 days', if_not_exists => TRUE);

ALTER TABLE metric_spans SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'tenant_id',
    timescaledb.compress_orderby = 'time DESC'
);
SELECT add_compression_policy('metric_spans', INTERVAL '7 days', if_not_exists => TRUE);
