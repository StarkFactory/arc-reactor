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
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/** span과 startTime을 함께 보관하여 원자적 insert/remove를 보장하는 복합 데이터 클래스. */
private data class ToolSpanEntry(
    val span: io.micrometer.tracing.Span,
    val startTimeMs: Long
)

/**
 * 에이전트/도구 레벨 span을 생성하는 4종 Hook.
 *
 * Spring AI의 gen_ai.client.operation span은 자동 생성되어 이 span의 자식이 된다.
 * HITL 감지: 도구 총 경과 시간이 실행 시간보다 현저히 큰 경우 HITL 대기로 판단한다.
 *
 * @see TracingAutoConfiguration 트레이싱 자동 설정
 */
class AgentTracingHooks(
    private val tracer: Tracer,
    private val storeUserIdentifiers: Boolean = false,
    private val storeSessionIdentifiers: Boolean = false
) : BeforeAgentStartHook, AfterAgentCompleteHook, BeforeToolCallHook, AfterToolCallHook,
    org.springframework.beans.factory.DisposableBean {

    override val order: Int = 199
    override val failOnError: Boolean = false
    override val enabled: Boolean = true

    private val agentSpans = ConcurrentHashMap<String, io.micrometer.tracing.Span>()
    private val toolSpanEntries = ConcurrentHashMap<String, ToolSpanEntry>()

    override suspend fun beforeAgentStart(context: HookContext): HookResult {
        try {
            var span = tracer.nextSpan()
                .name("gen_ai.agent.execute")
                .tag("run_id", context.runId)
                .tag("tenant_id", context.metadata["tenantId"]?.toString() ?: "default")
                .tag("channel", context.channel.orEmpty())
                .tag("gen_ai.agent.name", context.metadata["agentName"]?.toString() ?: "default")

            if (storeUserIdentifiers) {
                span = span.tag("user_id", context.userId)
            }
            if (storeSessionIdentifiers) {
                span = span.tag("session_id", context.metadata["sessionId"]?.toString().orEmpty())
            }

            span = span.start()

            agentSpans[context.runId] = span

            // trace 식별자를 hook 메타데이터에 저장 (코루틴 안전).
            // suspend 함수에서 MDC.put은 코루틴이 다른 스레드에서 재개될 수 있어 불안정하다.
            context.metadata["traceId"] = span.context().traceId()
            context.metadata["spanId"] = span.context().spanId()
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
            // 단일 원자적 put — span과 startTime은 항상 함께
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
            // 단일 원자적 remove — span과 startTime을 함께 제거
            val entry = toolSpanEntries.remove(key) ?: return
            val toolSpan = entry.span
            val totalElapsed = System.currentTimeMillis() - entry.startTimeMs

            toolSpan.tag("success", result.success.toString())
            toolSpan.tag("duration_ms", result.durationMs.toString())

            // HITL 감지: 총 경과 시간이 도구 실행 시간보다 현저히 큰 경우
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

    override fun destroy() {
        agentSpans.values.forEach { span ->
            runCatching { span.tag("cancelled", "true"); span.end() }
        }
        agentSpans.clear()
        toolSpanEntries.values.forEach { entry ->
            runCatching { entry.span.tag("cancelled", "true"); entry.span.end() }
        }
        toolSpanEntries.clear()
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
