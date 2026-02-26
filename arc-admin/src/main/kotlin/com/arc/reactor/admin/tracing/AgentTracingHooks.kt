package com.arc.reactor.admin.tracing

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.AfterToolCallHook
import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.BeforeToolCallHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import io.micrometer.tracing.Tracer
import mu.KotlinLogging
import org.slf4j.MDC
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Compound data class to keep span + startTime together,
 * ensuring atomic insert/remove across both values.
 */
private data class ToolSpanEntry(
    val span: io.micrometer.tracing.Span,
    val startTimeMs: Long
)

/**
 * 4-type Hook that creates agent/tool level spans.
 * Spring AI's gen_ai.client.operation span is auto-created and becomes a child.
 */
class AgentTracingHooks(
    private val tracer: Tracer
) : BeforeAgentStartHook, AfterAgentCompleteHook, BeforeToolCallHook, AfterToolCallHook {

    override val order: Int = 199
    override val failOnError: Boolean = false
    override val enabled: Boolean = true

    private val agentSpans = ConcurrentHashMap<String, io.micrometer.tracing.Span>()
    private val toolSpanEntries = ConcurrentHashMap<String, ToolSpanEntry>()

    override suspend fun beforeAgentStart(context: HookContext): HookResult {
        try {
            val span = tracer.nextSpan()
                .name("gen_ai.agent.execute")
                .tag("run_id", context.runId)
                .tag("user_id", context.userId)
                .tag("session_id", context.metadata["sessionId"]?.toString().orEmpty())
                .tag("tenant_id", context.metadata["tenantId"]?.toString() ?: "default")
                .tag("channel", context.channel.orEmpty())
                .tag("gen_ai.agent.name", context.metadata["agentName"]?.toString() ?: "default")
                .start()

            agentSpans[context.runId] = span

            MDC.put("traceId", span.context().traceId())
            MDC.put("spanId", span.context().spanId())

            context.metadata["traceId"] = span.context().traceId()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to start agent span for runId=${context.runId}" }
        }

        return HookResult.Continue
    }

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        try {
            val span = agentSpans.remove(context.runId) ?: return

            span.tag("success", response.success.toString())
            span.tag("total_duration_ms", response.totalDurationMs.toString())
            span.tag("total_tool_count", response.toolsUsed.size.toString())

            if (!response.success) {
                val errorCode = context.metadata["errorCode"]?.toString() ?: "UNKNOWN"
                span.tag("gen_ai.error.code", errorCode)
                response.errorMessage?.let { span.tag("error.message", it.take(500)) }
                span.error(RuntimeException(response.errorMessage ?: "Agent execution failed"))
            }

            span.end()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to end agent span for runId=${context.runId}" }
        } finally {
            MDC.remove("traceId")
            MDC.remove("spanId")
        }
    }

    override suspend fun beforeToolCall(context: ToolCallContext): HookResult {
        try {
            val toolSpan = tracer.nextSpan()
                .name("gen_ai.tool.execute")
                .tag("gen_ai.tool.name", context.toolName)
                .tag("gen_ai.tool.call_index", context.callIndex.toString())
                .start()

            val key = toolSpanKey(context)
            // Single atomic put — span and startTime are always together
            toolSpanEntries[key] = ToolSpanEntry(toolSpan, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to start tool span for ${context.toolName}" }
        }

        return HookResult.Continue
    }

    override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
        try {
            val key = toolSpanKey(context)
            // Single atomic remove — both span and startTime removed together
            val entry = toolSpanEntries.remove(key) ?: return
            val toolSpan = entry.span
            val totalElapsed = System.currentTimeMillis() - entry.startTimeMs

            toolSpan.tag("success", result.success.toString())
            toolSpan.tag("duration_ms", result.durationMs.toString())

            // HITL detection: if total elapsed is significantly more than tool execution time
            val hitlWaitMs = (totalElapsed - result.durationMs).coerceAtLeast(0)
            if (hitlWaitMs > 100) {
                toolSpan.tag("gen_ai.tool.hitl.required", "true")
                toolSpan.tag("gen_ai.tool.hitl.wait_ms", hitlWaitMs.toString())

                val output = result.output.orEmpty()
                if (output.startsWith("Rejected:") || output.startsWith("Error: Tool call rejected")) {
                    toolSpan.tag("gen_ai.tool.hitl.approved", "false")
                    toolSpan.tag("gen_ai.tool.hitl.rejection_reason", output.take(500))
                } else {
                    toolSpan.tag("gen_ai.tool.hitl.approved", "true")
                }
            }

            if (!result.success) {
                val errorType = result.errorMessage?.let { classifyErrorType(it) } ?: "unknown"
                toolSpan.tag("error.type", errorType)
                result.errorMessage?.let { toolSpan.tag("error.message", it.take(500)) }
                toolSpan.error(RuntimeException(result.errorMessage ?: "Tool call failed"))
            }

            toolSpan.end()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to end tool span for ${context.toolName}" }
        }
    }

    private fun toolSpanKey(context: ToolCallContext): String =
        "${context.agentContext.runId}:${context.toolName}:${context.callIndex}"

    private fun classifyErrorType(errorMessage: String): String = when {
        errorMessage.contains("timeout", ignoreCase = true) -> "TimeoutException"
        errorMessage.contains("connection", ignoreCase = true) -> "ConnectionException"
        errorMessage.contains("permission", ignoreCase = true) -> "PermissionDenied"
        else -> "RuntimeException"
    }
}
