package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.guard.tool.ToolOutputSanitizer
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

internal class ToolCallOrchestrator(
    private val toolCallTimeoutMs: Long,
    private val hookExecutor: HookExecutor?,
    private val toolApprovalPolicy: ToolApprovalPolicy?,
    private val pendingApprovalStore: PendingApprovalStore?,
    private val agentMetrics: AgentMetrics,
    private val parseToolArguments: (String?) -> Map<String, Any?> = ::parseToolArguments,
    private val toolOutputSanitizer: ToolOutputSanitizer? = null
) {

    suspend fun executeInParallel(
        toolCalls: List<AssistantMessage.ToolCall>,
        tools: List<Any>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        totalToolCallsCounter: AtomicInteger,
        maxToolCalls: Int,
        allowedTools: Set<String>?
    ): List<ToolResponseMessage.ToolResponse> = coroutineScope {
        toolCalls.map { toolCall ->
            async {
                executeSingleToolCall(
                    toolCall = toolCall,
                    tools = tools,
                    hookContext = hookContext,
                    toolsUsed = toolsUsed,
                    totalToolCallsCounter = totalToolCallsCounter,
                    maxToolCalls = maxToolCalls,
                    allowedTools = allowedTools
                )
            }
        }.awaitAll()
    }

    private suspend fun executeSingleToolCall(
        toolCall: AssistantMessage.ToolCall,
        tools: List<Any>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        totalToolCallsCounter: AtomicInteger,
        maxToolCalls: Int,
        allowedTools: Set<String>?
    ): ToolResponseMessage.ToolResponse {
        val currentCount = totalToolCallsCounter.getAndIncrement()
        if (currentCount >= maxToolCalls) {
            logger.warn { "maxToolCalls ($maxToolCalls) reached, stopping tool execution" }
            return ToolResponseMessage.ToolResponse(
                toolCall.id(), toolCall.name(),
                "Error: Maximum tool call limit ($maxToolCalls) reached"
            )
        }

        val toolName = toolCall.name()
        if (allowedTools != null && toolName !in allowedTools) {
            val msg = "Error: Tool '$toolName' is not allowed for this request"
            logger.info { "Tool call blocked by allowlist: tool=$toolName allowedTools=${allowedTools.size}" }
            agentMetrics.recordToolCall(toolName, 0, false)
            return ToolResponseMessage.ToolResponse(toolCall.id(), toolName, msg)
        }

        val toolCallContext = ToolCallContext(
            agentContext = hookContext,
            toolName = toolName,
            toolParams = parseToolArguments(toolCall.arguments()),
            callIndex = currentCount
        )

        checkBeforeToolCallHook(toolCallContext)?.let { rejection ->
            logger.info { "Tool call $toolName rejected by hook: ${rejection.reason}" }
            return ToolResponseMessage.ToolResponse(
                toolCall.id(), toolName, "Tool call rejected: ${rejection.reason}"
            )
        }

        // Human-in-the-Loop: check if tool call requires approval
        checkToolApproval(toolName, toolCallContext, hookContext)?.let { rejection ->
            publishBlockedToolCallResult(toolCallContext, toolName, rejection)
            return ToolResponseMessage.ToolResponse(toolCall.id(), toolName, rejection)
        }

        val toolStartTime = System.currentTimeMillis()
        val (rawOutput, toolSuccess) = invokeToolAdapter(toolName, toolCall, tools, toolsUsed)
        var toolOutput = rawOutput
        val toolDurationMs = System.currentTimeMillis() - toolStartTime

        // Sanitize tool output for indirect injection defense
        if (toolOutputSanitizer != null && toolSuccess) {
            val sanitized = toolOutputSanitizer.sanitize(toolName, toolOutput)
            toolOutput = sanitized.content
        }

        hookExecutor?.executeAfterToolCall(
            context = toolCallContext,
            result = ToolCallResult(
                success = toolSuccess,
                output = toolOutput,
                errorMessage = if (!toolSuccess) toolOutput else null,
                durationMs = toolDurationMs
            )
        )

        agentMetrics.recordToolCall(toolName, toolDurationMs, toolSuccess)
        return ToolResponseMessage.ToolResponse(toolCall.id(), toolName, toolOutput)
    }

    private suspend fun checkBeforeToolCallHook(context: ToolCallContext): HookResult.Reject? {
        if (hookExecutor == null) return null
        return hookExecutor.executeBeforeToolCall(context) as? HookResult.Reject
    }

    /**
     * Human-in-the-Loop: Check if tool call requires approval and wait for it.
     *
     * @return Rejection message if rejected or timed out, null if approved or no policy
     */
    private suspend fun checkToolApproval(
        toolName: String,
        toolCallContext: ToolCallContext,
        hookContext: HookContext
    ): String? {
        if (toolApprovalPolicy == null) return null
        if (!toolApprovalPolicy.requiresApproval(toolName, toolCallContext.toolParams)) return null

        val approvalStore = pendingApprovalStore
        if (approvalStore == null) {
            val reason = "Approval store unavailable for required tool '$toolName'"
            logger.error { reason }
            return "Tool call blocked: $reason"
        }

        logger.info { "Tool '$toolName' requires human approval, suspending execution..." }
        val hitlStartNanos = System.nanoTime()

        return try {
            val response = approvalStore.requestApproval(
                runId = hookContext.runId,
                userId = hookContext.userId,
                toolName = toolName,
                arguments = toolCallContext.toolParams
            )
            val keySuffix = hitlMetadataSuffix(toolCallContext)
            val hitlWaitMs = (System.nanoTime() - hitlStartNanos) / 1_000_000
            hookContext.metadata["hitlWaitMs_$keySuffix"] = hitlWaitMs
            hookContext.metadata["hitlApproved_$keySuffix"] = response.approved
            if (response.approved) {
                logger.info { "Tool '$toolName' approved by human (waited ${hitlWaitMs}ms)" }
                null // Continue execution
            } else {
                val reason = response.reason ?: "Rejected by human"
                logger.info { "Tool '$toolName' rejected by human: $reason (waited ${hitlWaitMs}ms)" }
                hookContext.metadata["hitlRejectionReason_$keySuffix"] = reason
                "Tool call rejected by human: $reason"
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            val reason = "Approval check failed for tool '$toolName': ${e.message ?: "unknown error"}"
            logger.error(e) { reason }
            "Tool call blocked: $reason"
        }
    }

    private suspend fun publishBlockedToolCallResult(
        context: ToolCallContext,
        toolName: String,
        message: String
    ) {
        hookExecutor?.executeAfterToolCall(
            context = context,
            result = ToolCallResult(
                success = false,
                output = message,
                errorMessage = message,
                durationMs = 0
            )
        )
        agentMetrics.recordToolCall(toolName, 0, false)
    }

    private fun hitlMetadataSuffix(context: ToolCallContext): String {
        return "${context.toolName}_${context.callIndex}"
    }

    private suspend fun invokeToolAdapter(
        toolName: String,
        toolCall: AssistantMessage.ToolCall,
        tools: List<Any>,
        toolsUsed: MutableList<String>
    ): Pair<String, Boolean> {
        val adapter = findToolAdapter(toolName, tools)
        return if (adapter != null) {
            toolsUsed.add(toolName)
            try {
                val timeoutMs = adapter.arcCallback.timeoutMs ?: toolCallTimeoutMs
                val output = withTimeout(timeoutMs) {
                    adapter.call(toolCall.arguments())
                }
                Pair(output, true)
            } catch (e: TimeoutCancellationException) {
                val timeoutMs = adapter.arcCallback.timeoutMs ?: toolCallTimeoutMs
                logger.error { "Tool $toolName timed out after ${timeoutMs}ms" }
                Pair("Error: Tool '$toolName' timed out after ${timeoutMs}ms", false)
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "Tool $toolName execution failed" }
                Pair("Error: ${e.message}", false)
            }
        } else {
            logger.warn { "Tool '$toolName' not found (possibly hallucinated by LLM)" }
            Pair("Error: Tool '$toolName' not found", false)
        }
    }

    /**
     * Find a tool adapter by name from the registered tools.
     */
    private fun findToolAdapter(toolName: String, tools: List<Any>): ArcToolCallbackAdapter? {
        return tools.filterIsInstance<ArcToolCallbackAdapter>().firstOrNull { it.arcCallback.name == toolName }
    }
}
