-- Retention policies

-- Raw data: 90 days
SELECT add_retention_policy('metric_agent_executions', INTERVAL '90 days', if_not_exists => TRUE);
SELECT add_retention_policy('metric_tool_calls', INTERVAL '90 days', if_not_exists => TRUE);
SELECT add_retention_policy('metric_token_usage', INTERVAL '90 days', if_not_exists => TRUE);
SELECT add_retention_policy('metric_sessions', INTERVAL '90 days', if_not_exists => TRUE);
SELECT add_retention_policy('metric_guard_events', INTERVAL '90 days', if_not_exists => TRUE);
SELECT add_retention_policy('metric_mcp_health', INTERVAL '90 days', if_not_exists => TRUE);
SELECT add_retention_policy('metric_spans', INTERVAL '90 days', if_not_exists => TRUE);

-- Eval results: 365 days
SELECT add_retention_policy('metric_eval_results', INTERVAL '365 days', if_not_exists => TRUE);

-- Audit trail: NO retention policy (7 years, managed by application)
-- metric_audit_trail has no retention_policy intentionally
