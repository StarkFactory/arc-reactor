package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.AgentExecutionEvent
import com.arc.reactor.admin.model.EvalResultEvent
import com.arc.reactor.admin.model.GuardEvent
import com.arc.reactor.admin.model.McpHealthEvent
import com.arc.reactor.admin.model.MetricEvent
import com.arc.reactor.admin.model.SessionEvent
import com.arc.reactor.admin.model.TokenUsageEvent
import com.arc.reactor.admin.model.ToolCallEvent
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.PreparedStatement
import java.sql.Timestamp

interface MetricEventStore {
    fun batchInsert(events: List<MetricEvent>)
}

class JdbcMetricEventStore(
    private val jdbcTemplate: JdbcTemplate
) : MetricEventStore {

    override fun batchInsert(events: List<MetricEvent>) {
        if (events.isEmpty()) return

        val executions = mutableListOf<AgentExecutionEvent>()
        val toolCalls = mutableListOf<ToolCallEvent>()
        val tokenUsages = mutableListOf<TokenUsageEvent>()
        val sessions = mutableListOf<SessionEvent>()
        val guardEvents = mutableListOf<GuardEvent>()
        val mcpHealths = mutableListOf<McpHealthEvent>()
        val evalResults = mutableListOf<EvalResultEvent>()

        for (event in events) {
            when (event) {
                is AgentExecutionEvent -> executions.add(event)
                is ToolCallEvent -> toolCalls.add(event)
                is TokenUsageEvent -> tokenUsages.add(event)
                is SessionEvent -> sessions.add(event)
                is GuardEvent -> guardEvents.add(event)
                is McpHealthEvent -> mcpHealths.add(event)
                is EvalResultEvent -> evalResults.add(event)
            }
        }

        if (executions.isNotEmpty()) insertExecutions(executions)
        if (toolCalls.isNotEmpty()) insertToolCalls(toolCalls)
        if (tokenUsages.isNotEmpty()) insertTokenUsages(tokenUsages)
        if (sessions.isNotEmpty()) insertSessions(sessions)
        if (guardEvents.isNotEmpty()) insertGuardEvents(guardEvents)
        if (mcpHealths.isNotEmpty()) insertMcpHealths(mcpHealths)
        if (evalResults.isNotEmpty()) insertEvalResults(evalResults)
    }

    private fun insertExecutions(events: List<AgentExecutionEvent>) {
        jdbcTemplate.batchUpdate(
            """INSERT INTO metric_agent_executions
               (time, tenant_id, run_id, user_id, session_id, channel,
                success, error_code, error_class,
                duration_ms, llm_duration_ms, tool_duration_ms, guard_duration_ms, queue_wait_ms,
                is_streaming, tool_count, persona_id, prompt_template_id, intent_category,
                guard_rejected, guard_stage, guard_category, retry_count, fallback_used)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val e = events[i]
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
                override fun getBatchSize() = events.size
            }
        )
    }

    private fun insertToolCalls(events: List<ToolCallEvent>) {
        jdbcTemplate.batchUpdate(
            """INSERT INTO metric_tool_calls
               (time, tenant_id, run_id, tool_name, tool_source, mcp_server_name, call_index,
                success, duration_ms, error_class, error_message)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val e = events[i]
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
                override fun getBatchSize() = events.size
            }
        )
    }

    private fun insertTokenUsages(events: List<TokenUsageEvent>) {
        jdbcTemplate.batchUpdate(
            """INSERT INTO metric_token_usage
               (time, tenant_id, run_id, model, provider, step_type,
                prompt_tokens, prompt_cached_tokens, completion_tokens, reasoning_tokens, total_tokens,
                estimated_cost_usd)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val e = events[i]
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
                override fun getBatchSize() = events.size
            }
        )
    }

    private fun insertSessions(events: List<SessionEvent>) {
        jdbcTemplate.batchUpdate(
            """INSERT INTO metric_sessions
               (time, tenant_id, session_id, user_id, channel,
                turn_count, total_duration_ms, total_tokens, total_cost_usd,
                first_response_latency_ms, outcome, started_at, ended_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val e = events[i]
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
                override fun getBatchSize() = events.size
            }
        )
    }

    private fun insertGuardEvents(events: List<GuardEvent>) {
        jdbcTemplate.batchUpdate(
            """INSERT INTO metric_guard_events
               (time, tenant_id, user_id, channel, stage, category,
                reason_class, reason_detail, is_output_guard, action)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val e = events[i]
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
                override fun getBatchSize() = events.size
            }
        )
    }

    private fun insertMcpHealths(events: List<McpHealthEvent>) {
        jdbcTemplate.batchUpdate(
            """INSERT INTO metric_mcp_health
               (time, tenant_id, server_name, status, response_time_ms,
                error_class, error_message, tool_count)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val e = events[i]
                    ps.setTimestamp(1, Timestamp.from(e.time))
                    ps.setString(2, e.tenantId)
                    ps.setString(3, e.serverName)
                    ps.setString(4, e.status)
                    ps.setLong(5, e.responseTimeMs)
                    ps.setString(6, e.errorClass)
                    ps.setString(7, e.errorMessage?.take(500))
                    ps.setInt(8, e.toolCount)
                }
                override fun getBatchSize() = events.size
            }
        )
    }

    private fun insertEvalResults(events: List<EvalResultEvent>) {
        jdbcTemplate.batchUpdate(
            """INSERT INTO metric_eval_results
               (time, tenant_id, eval_run_id, test_case_id,
                pass, score, latency_ms, token_usage, cost,
                assertion_type, failure_class, failure_detail, tags)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val e = events[i]
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
                override fun getBatchSize() = events.size
            }
        )
    }
}
