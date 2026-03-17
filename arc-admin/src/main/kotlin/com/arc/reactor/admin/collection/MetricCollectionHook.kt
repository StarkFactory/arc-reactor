package com.arc.reactor.admin.collection

import com.arc.reactor.admin.AdminClassifiers
import com.arc.reactor.admin.model.AgentExecutionEvent
import com.arc.reactor.admin.model.GuardEvent
import com.arc.reactor.admin.model.McpHealthEvent
import com.arc.reactor.admin.model.SessionEvent
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
 * Hook 시스템의 컨텍스트로 메트릭 이벤트를 보강하는 Hook.
 *
 * 지연 분해(LLM/도구/가드)와 컨텍스트 메타데이터를 캡처한다.
 * user/session 식별자는 명시적으로 활성화된 경우에만 저장한다.
 *
 * Order 200: 표준 Hook 이후에 실행되어 최종 상태를 캡처한다.
 *
 * @see HitlEventHook HITL 이벤트 수집 (order 201)
 * @see MetricRingBuffer 이벤트가 publish되는 링 버퍼
 */
class MetricCollectionHook(
    private val ringBuffer: MetricRingBuffer,
    private val healthMonitor: PipelineHealthMonitor,
    private val storeUserIdentifiers: Boolean = false,
    private val storeSessionIdentifiers: Boolean = false
) : AfterAgentCompleteHook, AfterToolCallHook {

    override val order: Int = 200
    override val enabled: Boolean = true
    override val failOnError: Boolean = false

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        try {
            val event = AgentExecutionEvent(
                tenantId = context.metadata["tenantId"]?.toString() ?: "default",
                runId = context.runId,
                userId = sanitizedUserId(context.userId),
                sessionId = sanitizedSessionId(context),
                channel = context.channel,
                success = response.success,
                errorCode = if (!response.success) response.errorCode else null,
                durationMs = response.totalDurationMs,
                llmDurationMs = context.metadata["llmDurationMs"]?.toString()?.toLongOrNull() ?: 0,
                toolDurationMs = context.metadata["toolDurationMs"]?.toString()?.toLongOrNull() ?: 0,
                guardDurationMs = context.metadata["guardDurationMs"]?.toString()?.toLongOrNull() ?: 0,
                queueWaitMs = context.metadata["queueWaitMs"]?.toString()?.toLongOrNull() ?: 0,
                toolCount = response.toolsUsed.size,
                personaId = context.metadata["personaId"]?.toString(),
                promptTemplateId = context.metadata["promptTemplateId"]?.toString(),
                intentCategory = context.metadata["intentCategory"]?.toString(),
                fallbackUsed = context.metadata["fallbackUsed"] == true
            )
            if (!ringBuffer.publish(event)) {
                healthMonitor.recordDrop(1)
            }
            publishGuardEvent(context, event)
            publishSessionEvent(context, response)
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
                tenantId = context.agentContext.metadata["tenantId"]?.toString() ?: "default",
                runId = context.agentContext.runId,
                toolName = context.toolName,
                toolSource = context.agentContext.metadata["toolSource_${context.toolName}"]?.toString() ?: "local",
                mcpServerName = context.agentContext.metadata["mcpServer_${context.toolName}"]?.toString(),
                callIndex = context.callIndex,
                success = result.success,
                durationMs = result.durationMs,
                errorClass = if (!result.success) AdminClassifiers.classifyToolError(result.errorMessage) else null,
                errorMessage = result.errorMessage?.take(500)
            )
            if (!ringBuffer.publish(event)) {
                healthMonitor.recordDrop(1)
            }
            publishMcpHealthEvent(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            healthMonitor.recordDrop(1)
            logger.warn(e) { "Failed to record tool call metric for ${context.toolName}" }
        }
    }

    private fun publishGuardEvent(context: HookContext, execution: AgentExecutionEvent) {
        val guardDurationMs = context.metadata["guardDurationMs"]?.toString()?.toLongOrNull()
        if (guardDurationMs == null) {
            logger.debug { "guardDurationMs missing in metadata, skipping guard event for runId=${context.runId}" }
            return
        }
        val action = if (execution.guardRejected) "rejected" else "allowed"
        val guardEvent = GuardEvent(
            tenantId = execution.tenantId,
            userId = execution.userId,
            channel = execution.channel,
            stage = execution.guardStage ?: "all",
            category = execution.guardCategory ?: "none",
            action = action
        )
        if (!ringBuffer.publish(guardEvent)) {
            healthMonitor.recordDrop(1)
        }
    }

    private fun publishSessionEvent(context: HookContext, response: AgentResponse) {
        val sessionId = sanitizedSessionId(context) ?: return
        val tenantId = context.metadata["tenantId"]?.toString() ?: "default"
        val sessionEvent = SessionEvent(
            tenantId = tenantId,
            sessionId = sessionId,
            userId = sanitizedUserId(context.userId),
            channel = context.channel,
            turnCount = 1,
            totalDurationMs = response.totalDurationMs
        )
        if (!ringBuffer.publish(sessionEvent)) {
            healthMonitor.recordDrop(1)
        }
    }

    private fun publishMcpHealthEvent(toolCall: ToolCallEvent) {
        if (toolCall.toolSource != "mcp") return
        val serverName = toolCall.mcpServerName ?: return
        val mcpEvent = McpHealthEvent(
            tenantId = toolCall.tenantId,
            serverName = serverName,
            status = if (toolCall.success) "CONNECTED" else "FAILED",
            responseTimeMs = toolCall.durationMs,
            errorClass = toolCall.errorClass,
            errorMessage = toolCall.errorMessage
        )
        if (!ringBuffer.publish(mcpEvent)) {
            healthMonitor.recordDrop(1)
        }
    }

    private fun sanitizedUserId(userId: String?): String? {
        if (!storeUserIdentifiers) return null
        return userId?.takeIf { it.isNotBlank() }
    }

    private fun sanitizedSessionId(context: HookContext): String? {
        if (!storeSessionIdentifiers) return null
        return context.metadata["sessionId"]?.toString()?.takeIf { it.isNotBlank() }
    }
}
