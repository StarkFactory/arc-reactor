package com.arc.reactor.admin.collection

import com.arc.reactor.admin.model.HitlEvent
import com.arc.reactor.hook.AfterToolCallHook
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import mu.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Captures Human-in-the-Loop (HITL) approval/rejection events from tool calls.
 *
 * Reads HITL metadata set by ToolCallOrchestrator:
 * - hitlWaitMs_{toolName}: how long the tool waited for human approval
 * - hitlApproved_{toolName}: whether the human approved the tool call
 * - hitlRejectionReason_{toolName}: reason for rejection (if rejected)
 *
 * Order 201: runs after MetricCollectionHook (200).
 */
class HitlEventHook(
    private val ringBuffer: MetricRingBuffer,
    private val healthMonitor: PipelineHealthMonitor
) : AfterToolCallHook {

    override val order: Int = 201
    override val enabled: Boolean = true
    override val failOnError: Boolean = false

    override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
        try {
            val meta = context.agentContext.metadata
            val toolName = context.toolName
            val waitMs = meta["hitlWaitMs_$toolName"]?.toString()?.toLongOrNull() ?: return
            val approved = meta["hitlApproved_$toolName"]?.toString()?.toBoolean() ?: true
            val reason = meta["hitlRejectionReason_$toolName"]?.toString()

            val event = HitlEvent(
                tenantId = meta["tenantId"]?.toString() ?: "default",
                runId = context.agentContext.runId,
                toolName = toolName,
                approved = approved,
                waitMs = waitMs,
                rejectionReason = reason
            )
            if (!ringBuffer.publish(event)) {
                healthMonitor.recordDrop(1)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            healthMonitor.recordDrop(1)
            logger.warn(e) { "Failed to record HITL event for ${context.toolName}" }
        }
    }
}
