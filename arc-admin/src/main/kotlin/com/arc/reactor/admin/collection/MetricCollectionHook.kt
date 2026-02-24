package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.AgentExecutionEvent
import com.arc.reactor.admin.model.ToolCallEvent
import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.AfterToolCallHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import mu.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Hook that enriches metric events with context from the hook system.
 *
 * Captures latency breakdown (LLM/tool/guard), userId, sessionId,
 * and other context not available via AgentMetrics interface alone.
 *
 * Order 200: runs after standard hooks, capturing final state.
 */
class MetricCollectionHook(
    private val ringBuffer: MetricRingBuffer,
    private val tenantResolver: TenantResolver,
    private val healthMonitor: PipelineHealthMonitor
) : AfterAgentCompleteHook, AfterToolCallHook {

    override val order: Int = 200
    override val enabled: Boolean = true
    override val failOnError: Boolean = false

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        try {
            val event = AgentExecutionEvent(
                tenantId = tenantResolver.currentTenantId(),
                runId = context.runId,
                userId = context.userId,
                sessionId = context.metadata["sessionId"]?.toString(),
                channel = context.channel,
                success = response.success,
                errorCode = if (!response.success) context.metadata["errorCode"]?.toString() else null,
                durationMs = response.totalDurationMs,
                llmDurationMs = context.metadata["llmDurationMs"]?.toString()?.toLongOrNull() ?: 0,
                toolDurationMs = context.metadata["toolDurationMs"]?.toString()?.toLongOrNull() ?: 0,
                guardDurationMs = context.metadata["guardDurationMs"]?.toString()?.toLongOrNull() ?: 0,
                queueWaitMs = context.metadata["queueWaitMs"]?.toString()?.toLongOrNull() ?: 0,
                toolCount = response.toolsUsed.size,
                personaId = context.metadata["personaId"]?.toString(),
                promptTemplateId = context.metadata["promptTemplateId"]?.toString(),
                intentCategory = context.metadata["intentCategory"]?.toString()
            )
            if (!ringBuffer.publish(event)) {
                healthMonitor.recordDrop(1)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            healthMonitor.recordDrop(1)
            logger.warn(e) { "Failed to record agent completion metric for runId=${context.runId}" }
        }
    }

    override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
        try {
            val event = ToolCallEvent(
                tenantId = tenantResolver.currentTenantId(),
                runId = context.agentContext.runId,
                toolName = context.toolName,
                toolSource = context.agentContext.metadata["toolSource_${context.toolName}"]?.toString() ?: "local",
                mcpServerName = context.agentContext.metadata["mcpServer_${context.toolName}"]?.toString(),
                callIndex = context.callIndex,
                success = result.success,
                durationMs = result.durationMs,
                errorClass = if (!result.success) classifyToolError(result.errorMessage) else null,
                errorMessage = result.errorMessage?.take(500)
            )
            if (!ringBuffer.publish(event)) {
                healthMonitor.recordDrop(1)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            healthMonitor.recordDrop(1)
            logger.warn(e) { "Failed to record tool call metric for ${context.toolName}" }
        }
    }

    private fun classifyToolError(errorMessage: String?): String? {
        if (errorMessage == null) return null
        return when {
            errorMessage.contains("timeout", ignoreCase = true) -> "timeout"
            errorMessage.contains("connection", ignoreCase = true) -> "connection_error"
            errorMessage.contains("permission", ignoreCase = true) -> "permission_denied"
            errorMessage.contains("not found", ignoreCase = true) -> "not_found"
            else -> "unknown"
        }
    }
}
