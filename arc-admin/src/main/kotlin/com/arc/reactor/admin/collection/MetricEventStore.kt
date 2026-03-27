package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.AgentExecutionEvent
import com.arc.reactor.admin.model.EvalResultEvent
import com.arc.reactor.admin.model.GuardEvent
import com.arc.reactor.admin.model.HitlEvent
import com.arc.reactor.admin.model.McpHealthEvent
import com.arc.reactor.admin.model.MetricEvent
import com.arc.reactor.admin.model.QuotaEvent
import com.arc.reactor.admin.model.SessionEvent
import com.arc.reactor.admin.model.TokenUsageEvent
import com.arc.reactor.admin.model.ToolCallEvent
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.PreparedStatement
import java.sql.Timestamp

/**
 * 메트릭 이벤트를 영구 저장소에 일괄 삽입하는 인터페이스.
 *
 * @see JdbcMetricEventStore JDBC 기반 구현
 * @see MetricWriter 링 버퍼에서 drain하여 이 스토어로 쓰는 writer
 */
interface MetricEventStore {
    /** [MetricEvent] 목록을 일괄 삽입한다. */
    fun batchInsert(events: List<MetricEvent>)
}

/**
 * [MetricEventStore]의 JDBC 구현체.
 *
 * 이벤트 타입별로 분류한 뒤 각 테이블에 batch insert를 수행한다.
 * [MetricWriter]가 주기적으로 호출한다.
 *
 * @see MetricRingBuffer 이벤트가 버퍼링되는 링 버퍼
 */
class JdbcMetricEventStore(
    private val jdbcTemplate: JdbcTemplate
) : MetricEventStore {

    /** 이벤트를 타입별로 분류하고 각 테이블에 일괄 삽입을 위임한다. */
    override fun batchInsert(events: List<MetricEvent>) {
        if (events.isEmpty()) return

        val grouped = events.groupBy { it::class }
        grouped[AgentExecutionEvent::class]?.let { insertExecutionEvents(it.filterIsInstance<AgentExecutionEvent>()) }
        grouped[ToolCallEvent::class]?.let { insertToolCallEvents(it.filterIsInstance<ToolCallEvent>()) }
        grouped[TokenUsageEvent::class]?.let { insertTokenUsageEvents(it.filterIsInstance<TokenUsageEvent>()) }
        grouped[SessionEvent::class]?.let { insertSessionEvents(it.filterIsInstance<SessionEvent>()) }
        grouped[GuardEvent::class]?.let { insertGuardEvents(it.filterIsInstance<GuardEvent>()) }
        grouped[McpHealthEvent::class]?.let { insertMcpHealthEvents(it.filterIsInstance<McpHealthEvent>()) }
        grouped[EvalResultEvent::class]?.let { insertEvalResultEvents(it.filterIsInstance<EvalResultEvent>()) }
        grouped[QuotaEvent::class]?.let { insertQuotaEvents(it.filterIsInstance<QuotaEvent>()) }
        grouped[HitlEvent::class]?.let { insertHitlEvents(it.filterIsInstance<HitlEvent>()) }
    }

    // ── 공통 헬퍼 ──

    /**
     * SQL과 파라미터 바인딩 함수를 받아 batch insert를 실행한다.
     * [BatchPreparedStatementSetter] 보일러플레이트를 제거한다.
     */
    private fun <T> executeBatchInsert(
        sql: String,
        events: List<T>,
        binder: (PreparedStatement, T) -> Unit
    ) {
        jdbcTemplate.batchUpdate(
            sql,
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) = binder(ps, events[i])
                override fun getBatchSize() = events.size
            }
        )
    }

    // ── 이벤트 타입별 삽입 ──

    /** 에이전트 실행 이벤트를 metric_agent_executions 테이블에 삽입한다. */
    private fun insertExecutionEvents(events: List<AgentExecutionEvent>) {
        executeBatchInsert(
            """INSERT INTO metric_agent_executions
               (time, tenant_id, run_id, user_id, session_id, channel,
                success, error_code, error_class,
                duration_ms, llm_duration_ms, tool_duration_ms, guard_duration_ms, queue_wait_ms,
                is_streaming, tool_count, persona_id, prompt_template_id, intent_category,
                guard_rejected, guard_stage, guard_category, retry_count, fallback_used)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            events
        ) { ps, e ->
            ps.setTimestamp(1, Timestamp.from(e.time))
            ps.setString(2, e.tenantId)
            ps.setString(3, e.runId)
            ps.setString(4, e.userId)
            ps.setString(5, e.sessionId)
            ps.setString(6, e.channel)
            ps.setBoolean(7, e.success)
            ps.setString(8, e.errorCode)
            ps.setString(9, e.errorClass)
            ps.setLong(10, e.durationMs)
            ps.setLong(11, e.llmDurationMs)
            ps.setLong(12, e.toolDurationMs)
            ps.setLong(13, e.guardDurationMs)
            ps.setLong(14, e.queueWaitMs)
            ps.setBoolean(15, e.isStreaming)
            ps.setInt(16, e.toolCount)
            ps.setString(17, e.personaId)
            ps.setString(18, e.promptTemplateId)
            ps.setString(19, e.intentCategory)
            ps.setBoolean(20, e.guardRejected)
            ps.setString(21, e.guardStage)
            ps.setString(22, e.guardCategory)
            ps.setInt(23, e.retryCount)
            ps.setBoolean(24, e.fallbackUsed)
        }
    }

    /** 도구 호출 이벤트를 metric_tool_calls 테이블에 삽입한다. */
    private fun insertToolCallEvents(events: List<ToolCallEvent>) {
        executeBatchInsert(
            """INSERT INTO metric_tool_calls
               (time, tenant_id, run_id, tool_name, tool_source, mcp_server_name, call_index,
                success, duration_ms, error_class, error_message)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            events
        ) { ps, e ->
            ps.setTimestamp(1, Timestamp.from(e.time))
            ps.setString(2, e.tenantId)
            ps.setString(3, e.runId)
            ps.setString(4, e.toolName)
            ps.setString(5, e.toolSource)
            ps.setString(6, e.mcpServerName)
            ps.setInt(7, e.callIndex)
            ps.setBoolean(8, e.success)
            ps.setLong(9, e.durationMs)
            ps.setString(10, e.errorClass)
            ps.setString(11, e.errorMessage?.take(500))
        }
    }

    /** 토큰 사용량 이벤트를 metric_token_usage 테이블에 삽입한다. */
    private fun insertTokenUsageEvents(events: List<TokenUsageEvent>) {
        executeBatchInsert(
            """INSERT INTO metric_token_usage
               (time, tenant_id, run_id, model, provider, step_type,
                prompt_tokens, prompt_cached_tokens, completion_tokens, reasoning_tokens, total_tokens,
                estimated_cost_usd)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            events
        ) { ps, e ->
            ps.setTimestamp(1, Timestamp.from(e.time))
            ps.setString(2, e.tenantId)
            ps.setString(3, e.runId)
            ps.setString(4, e.model)
            ps.setString(5, e.provider)
            ps.setString(6, e.stepType)
            ps.setInt(7, e.promptTokens)
            ps.setInt(8, e.promptCachedTokens)
            ps.setInt(9, e.completionTokens)
            ps.setInt(10, e.reasoningTokens)
            ps.setInt(11, e.totalTokens)
            ps.setBigDecimal(12, e.estimatedCostUsd)
        }
    }

    /** 세션 이벤트를 metric_sessions 테이블에 삽입한다. */
    private fun insertSessionEvents(events: List<SessionEvent>) {
        executeBatchInsert(
            """INSERT INTO metric_sessions
               (time, tenant_id, session_id, user_id, channel,
                turn_count, total_duration_ms, total_tokens, total_cost_usd,
                first_response_latency_ms, outcome, started_at, ended_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            events
        ) { ps, e ->
            ps.setTimestamp(1, Timestamp.from(e.time))
            ps.setString(2, e.tenantId)
            ps.setString(3, e.sessionId)
            ps.setString(4, e.userId)
            ps.setString(5, e.channel)
            ps.setInt(6, e.turnCount)
            ps.setLong(7, e.totalDurationMs)
            ps.setLong(8, e.totalTokens)
            ps.setBigDecimal(9, e.totalCostUsd)
            ps.setLong(10, e.firstResponseLatencyMs)
            ps.setString(11, e.outcome)
            ps.setTimestamp(12, Timestamp.from(e.startedAt))
            ps.setTimestamp(13, Timestamp.from(e.endedAt))
        }
    }

    /** 가드 이벤트를 metric_guard_events 테이블에 삽입한다. */
    private fun insertGuardEvents(events: List<GuardEvent>) {
        executeBatchInsert(
            """INSERT INTO metric_guard_events
               (time, tenant_id, user_id, channel, stage, category,
                reason_class, reason_detail, is_output_guard, action)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            events
        ) { ps, e ->
            ps.setTimestamp(1, Timestamp.from(e.time))
            ps.setString(2, e.tenantId)
            ps.setString(3, e.userId)
            ps.setString(4, e.channel)
            ps.setString(5, e.stage)
            ps.setString(6, e.category)
            ps.setString(7, e.reasonClass)
            ps.setString(8, e.reasonDetail?.take(500))
            ps.setBoolean(9, e.isOutputGuard)
            ps.setString(10, e.action)
        }
    }

    /** MCP 서버 상태 이벤트를 metric_mcp_health 테이블에 삽입한다. */
    private fun insertMcpHealthEvents(events: List<McpHealthEvent>) {
        executeBatchInsert(
            """INSERT INTO metric_mcp_health
               (time, tenant_id, server_name, status, response_time_ms,
                error_class, error_message, tool_count)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            events
        ) { ps, e ->
            ps.setTimestamp(1, Timestamp.from(e.time))
            ps.setString(2, e.tenantId)
            ps.setString(3, e.serverName)
            ps.setString(4, e.status)
            ps.setLong(5, e.responseTimeMs)
            ps.setString(6, e.errorClass)
            ps.setString(7, e.errorMessage?.take(500))
            ps.setInt(8, e.toolCount)
        }
    }

    /** 평가 결과 이벤트를 metric_eval_results 테이블에 삽입한다. */
    private fun insertEvalResultEvents(events: List<EvalResultEvent>) {
        executeBatchInsert(
            """INSERT INTO metric_eval_results
               (time, tenant_id, eval_run_id, test_case_id,
                pass, score, latency_ms, token_usage, cost,
                assertion_type, failure_class, failure_detail, tags)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            events
        ) { ps, e ->
            ps.setTimestamp(1, Timestamp.from(e.time))
            ps.setString(2, e.tenantId)
            ps.setString(3, e.evalRunId)
            ps.setString(4, e.testCaseId)
            ps.setBoolean(5, e.pass)
            ps.setDouble(6, e.score)
            ps.setLong(7, e.latencyMs)
            ps.setInt(8, e.tokenUsage)
            ps.setBigDecimal(9, e.cost)
            ps.setString(10, e.assertionType)
            ps.setString(11, e.failureClass)
            ps.setString(12, e.failureDetail?.take(500))
            ps.setString(13, e.tags.joinToString(","))
        }
    }

    /** 쿼터 이벤트를 metric_quota_events 테이블에 삽입한다. */
    private fun insertQuotaEvents(events: List<QuotaEvent>) {
        executeBatchInsert(
            """INSERT INTO metric_quota_events
               (time, tenant_id, action, current_usage, quota_limit, usage_percent, reason)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            events
        ) { ps, e ->
            ps.setTimestamp(1, Timestamp.from(e.time))
            ps.setString(2, e.tenantId)
            ps.setString(3, e.action)
            ps.setLong(4, e.currentUsage)
            ps.setLong(5, e.quotaLimit)
            ps.setDouble(6, e.usagePercent)
            ps.setString(7, e.reason?.take(500))
        }
    }

    /** HITL(Human-in-the-Loop) 이벤트를 metric_hitl_events 테이블에 삽입한다. */
    private fun insertHitlEvents(events: List<HitlEvent>) {
        executeBatchInsert(
            """INSERT INTO metric_hitl_events
               (time, tenant_id, run_id, tool_name, approved, wait_ms, rejection_reason)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            events
        ) { ps, e ->
            ps.setTimestamp(1, Timestamp.from(e.time))
            ps.setString(2, e.tenantId)
            ps.setString(3, e.runId)
            ps.setString(4, e.toolName)
            ps.setBoolean(5, e.approved)
            ps.setLong(6, e.waitMs)
            ps.setString(7, e.rejectionReason?.take(500))
        }
    }
}
