package com.arc.reactor.admin.query

import com.arc.reactor.admin.model.TenantUsage
import com.arc.reactor.admin.model.TimeSeriesPoint
import com.arc.reactor.admin.model.ToolUsageSummary
import com.arc.reactor.admin.model.UserUsageSummary
import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

private val logger = KotlinLogging.logger {}

/**
 * Selects query source based on granularity:
 * - < 24h: raw tables
 * - 24h - 30d: hourly aggregates
 * - > 30d: daily aggregates
 */
class MetricQueryService(private val jdbcTemplate: JdbcTemplate) {

    fun getCurrentMonthUsage(tenantId: String): TenantUsage {
        val monthStart = Instant.now().truncatedTo(ChronoUnit.DAYS)
            .atZone(java.time.ZoneOffset.UTC)
            .withDayOfMonth(1)
            .toInstant()
        val ts = Timestamp.from(monthStart)

        // Separate queries to avoid JOIN inflation (1 execution × N token rows → N counts)
        val results = jdbcTemplate.queryForMap(
            """SELECT
                (SELECT COALESCE(COUNT(*), 0)
                   FROM metric_agent_executions
                   WHERE tenant_id = ? AND time >= ?) AS requests,
                (SELECT COALESCE(SUM(total_tokens), 0)
                   FROM metric_token_usage
                   WHERE tenant_id = ? AND time >= ?) AS tokens,
                (SELECT COALESCE(SUM(estimated_cost_usd), 0)
                   FROM metric_token_usage
                   WHERE tenant_id = ? AND time >= ?) AS cost""",
            tenantId, ts, tenantId, ts, tenantId, ts
        )

        return TenantUsage(
            tenantId = tenantId,
            requests = (results["requests"] as Number).toLong(),
            tokens = (results["tokens"] as Number).toLong(),
            costUsd = results["cost"] as? BigDecimal ?: BigDecimal.ZERO
        )
    }

    fun getRequestTimeSeries(
        tenantId: String,
        from: Instant,
        to: Instant,
        granularity: String = "auto"
    ): List<TimeSeriesPoint> {
        val hours = ChronoUnit.HOURS.between(from, to)
        val (table, bucket) = selectSource(hours, granularity)

        return jdbcTemplate.query(
            """SELECT bucket AS time, SUM(total_requests) AS value
               FROM $table
               WHERE tenant_id = ? AND bucket >= ? AND bucket < ?
               GROUP BY bucket ORDER BY bucket""",
            { rs, _ ->
                TimeSeriesPoint(
                    time = rs.getTimestamp("time").toInstant(),
                    value = rs.getDouble("value")
                )
            },
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
    }

    fun getSuccessRate(tenantId: String, from: Instant, to: Instant): Double {
        val result = jdbcTemplate.queryForMap(
            """SELECT
                COUNT(*) AS total,
                COUNT(*) FILTER (WHERE success = TRUE) AS successful
               FROM metric_agent_executions
               WHERE tenant_id = ? AND time >= ? AND time < ?""",
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
        val total = (result["total"] as Number).toLong()
        val successful = (result["successful"] as Number).toLong()
        return if (total == 0L) 1.0 else successful.toDouble() / total
    }

    fun getLatencyPercentiles(tenantId: String, from: Instant, to: Instant): Map<String, Long> {
        val result = jdbcTemplate.queryForMap(
            """SELECT
                PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY duration_ms)::BIGINT AS p50,
                PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms)::BIGINT AS p95,
                PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY duration_ms)::BIGINT AS p99
               FROM metric_agent_executions
               WHERE tenant_id = ? AND time >= ? AND time < ?""",
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
        val p50: Long = (result["p50"] as? Number)?.toLong() ?: 0L
        val p95: Long = (result["p95"] as? Number)?.toLong() ?: 0L
        val p99: Long = (result["p99"] as? Number)?.toLong() ?: 0L
        return mapOf("p50" to p50, "p95" to p95, "p99" to p99)
    }

    fun getTopUsers(tenantId: String, from: Instant, to: Instant, limit: Int = 10): List<UserUsageSummary> {
        return jdbcTemplate.query(
            """SELECT user_id, COUNT(*) AS requests, MAX(time) AS last_activity
               FROM metric_agent_executions
               WHERE tenant_id = ? AND time >= ? AND time < ? AND user_id IS NOT NULL
               GROUP BY user_id ORDER BY requests DESC LIMIT ?""",
            { rs, _ ->
                UserUsageSummary(
                    userId = rs.getString("user_id"),
                    requests = rs.getLong("requests"),
                    lastActivity = rs.getTimestamp("last_activity").toInstant()
                )
            },
            tenantId, Timestamp.from(from), Timestamp.from(to), limit
        )
    }

    fun getToolRanking(tenantId: String, from: Instant, to: Instant): List<ToolUsageSummary> {
        return jdbcTemplate.query(
            """SELECT
                tool_name,
                COUNT(*) AS calls,
                COUNT(*) FILTER (WHERE success = TRUE)::DOUBLE PRECISION / GREATEST(COUNT(*), 1) AS success_rate,
                AVG(duration_ms)::BIGINT AS avg_duration_ms,
                PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms)::BIGINT AS p95_duration_ms,
                MAX(mcp_server_name) AS mcp_server_name
               FROM metric_tool_calls
               WHERE tenant_id = ? AND time >= ? AND time < ?
               GROUP BY tool_name ORDER BY calls DESC""",
            { rs, _ ->
                ToolUsageSummary(
                    toolName = rs.getString("tool_name"),
                    calls = rs.getLong("calls"),
                    successRate = rs.getDouble("success_rate"),
                    avgDurationMs = rs.getLong("avg_duration_ms"),
                    p95DurationMs = rs.getLong("p95_duration_ms"),
                    mcpServerName = rs.getString("mcp_server_name")
                )
            },
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
    }

    fun getErrorDistribution(tenantId: String, from: Instant, to: Instant): Map<String, Long> {
        return jdbcTemplate.query(
            """SELECT COALESCE(error_class, 'unknown') AS error_class, COUNT(*) AS count
               FROM metric_agent_executions
               WHERE tenant_id = ? AND time >= ? AND time < ? AND success = FALSE
               GROUP BY error_class ORDER BY count DESC""",
            { rs, _ ->
                rs.getString("error_class") to rs.getLong("count")
            },
            tenantId, Timestamp.from(from), Timestamp.from(to)
        ).toMap()
    }

    fun getMaxConsecutiveMcpFailures(tenantId: String): Long? {
        return try {
            jdbcTemplate.queryForObject(
                """SELECT COALESCE(MAX(consecutive), 0) FROM (
                       SELECT server_name,
                              COUNT(*) FILTER (WHERE status NOT IN ('healthy', 'CONNECTED')) AS consecutive
                       FROM (
                           SELECT server_name, status,
                                  SUM(CASE WHEN status IN ('healthy', 'CONNECTED') THEN 1 ELSE 0 END)
                                      OVER (PARTITION BY server_name ORDER BY time) AS grp
                           FROM metric_mcp_health
                           WHERE tenant_id = ? AND time >= NOW() - INTERVAL '1 hour'
                       ) sub
                       GROUP BY server_name, grp
                   ) counts""",
                Long::class.java, tenantId
            )
        } catch (e: Exception) {
            logger.debug(e) { "Failed to query MCP consecutive failures for tenant=$tenantId" }
            null
        }
    }

    fun getHourlyCost(tenantId: String, from: Instant, to: Instant): Double {
        val result = jdbcTemplate.queryForMap(
            """SELECT COALESCE(AVG(total_cost_usd), 0) AS avg_hourly_cost
               FROM metric_executions_hourly
               WHERE tenant_id = ? AND bucket >= ? AND bucket < ?""",
            tenantId, Timestamp.from(from), Timestamp.from(to)
        )
        return (result["avg_hourly_cost"] as? Number)?.toDouble() ?: 0.0
    }

    fun getAggregateRefreshLagMs(): Long? {
        return try {
            jdbcTemplate.queryForObject(
                """SELECT EXTRACT(EPOCH FROM (NOW() - MAX(bucket)))::BIGINT * 1000
                   FROM metric_executions_hourly""",
                Long::class.java
            )
        } catch (e: Exception) {
            logger.debug(e) { "Failed to query aggregate refresh lag" }
            null
        }
    }

    private fun selectSource(hours: Long, granularity: String): Pair<String, String> {
        return when {
            granularity == "hourly" -> HOURLY_SOURCE
            granularity == "daily" -> DAILY_SOURCE
            hours <= 720 -> HOURLY_SOURCE
            else -> DAILY_SOURCE
        }
    }

    companion object {
        // Whitelisted table names to prevent SQL injection via string interpolation
        private val HOURLY_SOURCE = "metric_executions_hourly" to "1 hour"
        private val DAILY_SOURCE = "metric_executions_daily" to "1 day"
    }
}
