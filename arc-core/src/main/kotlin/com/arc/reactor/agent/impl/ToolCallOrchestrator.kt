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
import com.arc.reactor.response.ToolResponseSignal
import com.arc.reactor.response.ToolResponseSignalExtractor
import com.arc.reactor.response.VerifiedSource
import com.arc.reactor.response.VerifiedSourceExtractor
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.aop.framework.Advised
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.tool.method.MethodToolCallbackProvider
import java.util.concurrent.ConcurrentHashMap
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
    private val springToolCallbackCache =
        ConcurrentHashMap<ToolCallbackCacheKey, Map<String, org.springframework.ai.tool.ToolCallback>>()

    suspend fun executeDirectToolCall(
        toolName: String,
        toolParams: Map<String, Any?>,
        tools: List<Any>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        allowedTools: Set<String>? = null
    ): ToolCallResult {
        if (allowedTools != null && toolName !in allowedTools) {
            val message = "Error: Tool '$toolName' is not allowed for this request"
            logger.info { "Direct tool call blocked by allowlist: tool=$toolName allowedTools=${allowedTools.size}" }
            agentMetrics.recordToolCall(toolName, 0, false)
            return ToolCallResult(success = false, output = message, errorMessage = message, durationMs = 0)
        }

        val effectiveToolParams = enrichToolParamsForRequesterAwareTools(
            toolName = toolName,
            toolParams = toolParams,
            metadata = hookContext.metadata
        )
        val callIndex = toolsUsed.size
        val toolCallContext = ToolCallContext(
            agentContext = hookContext,
            toolName = toolName,
            toolParams = effectiveToolParams,
            callIndex = callIndex
        )

        checkBeforeToolCallHook(toolCallContext)?.let { rejection ->
            val message = "Tool call rejected: ${rejection.reason}"
            logger.info { "Direct tool call $toolName rejected by hook: ${rejection.reason}" }
            return ToolCallResult(success = false, output = message, errorMessage = message, durationMs = 0)
        }

        checkToolApproval(toolName, toolCallContext, hookContext)?.let { rejection ->
            publishBlockedToolCallResult(toolCallContext, toolName, rejection)
            return ToolCallResult(success = false, output = rejection, errorMessage = rejection, durationMs = 0)
        }

        val toolStartTime = System.currentTimeMillis()
        val springCallbacksByName = resolveSpringToolCallbacksByName(tools)
        val toolInput = serializeToolInput(effectiveToolParams, null)
        val invocation = invokeToolAdapter(
            toolName = toolName,
            toolInput = toolInput,
            tools = tools,
            springCallbacksByName = springCallbacksByName
        )
        if (invocation.trackAsUsed) {
            toolsUsed.add(toolName)
        }
        captureToolSignals(hookContext, toolName, invocation.output, invocation.success)
        var toolOutput = invocation.output
        val toolDurationMs = System.currentTimeMillis() - toolStartTime

        if (toolOutputSanitizer != null) {
            val sanitized = toolOutputSanitizer.sanitize(toolName, toolOutput)
            toolOutput = sanitized.content
        }

        val result = ToolCallResult(
            success = invocation.success,
            output = toolOutput,
            errorMessage = if (!invocation.success) toolOutput else null,
            durationMs = toolDurationMs
        )
        hookExecutor?.executeAfterToolCall(toolCallContext, result)
        agentMetrics.recordToolCall(toolName, toolDurationMs, invocation.success)
        return result
    }

    suspend fun executeInParallel(
        toolCalls: List<AssistantMessage.ToolCall>,
        tools: List<Any>,
        hookContext: HookContext,
        toolsUsed: MutableList<String>,
        totalToolCallsCounter: AtomicInteger,
        maxToolCalls: Int,
        allowedTools: Set<String>?,
        normalizeToolResponseToJson: Boolean = false
    ): List<ToolResponseMessage.ToolResponse> = coroutineScope {
        val springCallbacksByName = resolveSpringToolCallbacksByName(tools)
        val executions = toolCalls.map { toolCall ->
            async {
                executeSingleToolCall(
                    toolCall = toolCall,
                    tools = tools,
                    springCallbacksByName = springCallbacksByName,
                    hookContext = hookContext,
                    totalToolCallsCounter = totalToolCallsCounter,
                    maxToolCalls = maxToolCalls,
                    allowedTools = allowedTools,
                    normalizeToolResponseToJson = normalizeToolResponseToJson
                )
            }
        }.awaitAll()
        executions.forEach { execution ->
            execution.usedToolName?.let(toolsUsed::add)
            mergeToolCapture(hookContext, execution.capture)
        }
        executions.map(ParallelToolExecution::response)
    }

    private suspend fun executeSingleToolCall(
        toolCall: AssistantMessage.ToolCall,
        tools: List<Any>,
        springCallbacksByName: Map<String, org.springframework.ai.tool.ToolCallback>,
        hookContext: HookContext,
        totalToolCallsCounter: AtomicInteger,
        maxToolCalls: Int,
        allowedTools: Set<String>?,
        normalizeToolResponseToJson: Boolean
    ): ParallelToolExecution {
        val toolName = toolCall.name()
        if (allowedTools != null && toolName !in allowedTools) {
            val msg = "Error: Tool '$toolName' is not allowed for this request"
            logger.info { "Tool call blocked by allowlist: tool=$toolName allowedTools=${allowedTools.size}" }
            agentMetrics.recordToolCall(toolName, 0, false)
            return ParallelToolExecution(
                response = buildToolResponse(
                    toolCall = toolCall,
                    toolName = toolName,
                    output = msg,
                    normalizeToolResponseToJson = normalizeToolResponseToJson
                )
            )
        }

        val parsedToolParams = parseToolArguments(toolCall.arguments())
        val effectiveToolParams = enrichToolParamsForRequesterAwareTools(
            toolName = toolName,
            toolParams = parsedToolParams,
            metadata = hookContext.metadata
        )
        val toolInput = serializeToolInput(effectiveToolParams, toolCall.arguments())

        val toolCallContext = ToolCallContext(
            agentContext = hookContext,
            toolName = toolName,
            toolParams = effectiveToolParams,
            callIndex = totalToolCallsCounter.get()
        )

        checkBeforeToolCallHook(toolCallContext)?.let { rejection ->
            logger.info { "Tool call $toolName rejected by hook: ${rejection.reason}" }
            return ParallelToolExecution(
                response = buildToolResponse(
                    toolCall = toolCall,
                    toolName = toolName,
                    output = "Tool call rejected: ${rejection.reason}",
                    normalizeToolResponseToJson = normalizeToolResponseToJson
                )
            )
        }

        // Human-in-the-Loop: check if tool call requires approval
        checkToolApproval(toolName, toolCallContext, hookContext)?.let { rejection ->
            publishBlockedToolCallResult(toolCallContext, toolName, rejection)
            return ParallelToolExecution(
                response = buildToolResponse(
                    toolCall = toolCall,
                    toolName = toolName,
                    output = rejection,
                    normalizeToolResponseToJson = normalizeToolResponseToJson
                )
            )
        }

        val toolExists = findToolAdapter(toolName, tools) != null || springCallbacksByName.containsKey(toolName)
        if (!toolExists) {
            logger.warn { "Tool '$toolName' not found (possibly hallucinated by LLM)" }
            return ParallelToolExecution(
                response = buildToolResponse(
                    toolCall = toolCall,
                    toolName = toolName,
                    output = "Error: Tool '$toolName' not found",
                    normalizeToolResponseToJson = normalizeToolResponseToJson
                )
            )
        }

        if (reserveToolExecutionSlot(totalToolCallsCounter, maxToolCalls) == null) {
            logger.warn { "maxToolCalls ($maxToolCalls) reached, stopping tool execution" }
            return ParallelToolExecution(
                response = buildToolResponse(
                    toolCall = toolCall,
                    toolName = toolCall.name(),
                    output = "Error: Maximum tool call limit ($maxToolCalls) reached",
                    normalizeToolResponseToJson = normalizeToolResponseToJson
                )
            )
        }

        val toolStartTime = System.currentTimeMillis()
        val invocation = invokeToolAdapter(
            toolName = toolName,
            toolInput = toolInput,
            tools = tools,
            springCallbacksByName = springCallbacksByName
        )
        val capture = extractToolCapture(toolName, invocation.output, invocation.success)
        var toolOutput = invocation.output
        val toolDurationMs = System.currentTimeMillis() - toolStartTime

        // Sanitize tool output for indirect injection defense
        if (toolOutputSanitizer != null) {
            val sanitized = toolOutputSanitizer.sanitize(toolName, toolOutput)
            toolOutput = sanitized.content
        }

        hookExecutor?.executeAfterToolCall(
            context = toolCallContext,
            result = ToolCallResult(
                success = invocation.success,
                output = toolOutput,
                errorMessage = if (!invocation.success) toolOutput else null,
                durationMs = toolDurationMs
            )
        )

        agentMetrics.recordToolCall(toolName, toolDurationMs, invocation.success)
        return ParallelToolExecution(
            response = buildToolResponse(
                toolCall = toolCall,
                toolName = toolName,
                output = toolOutput,
                normalizeToolResponseToJson = normalizeToolResponseToJson
            ),
            usedToolName = toolName.takeIf { invocation.trackAsUsed },
            capture = capture
        )
    }

    private fun reserveToolExecutionSlot(counter: AtomicInteger, maxToolCalls: Int): Int? {
        while (true) {
            val current = counter.get()
            if (current >= maxToolCalls) {
                return null
            }
            if (counter.compareAndSet(current, current + 1)) {
                return current
            }
        }
    }

    private fun extractToolCapture(
        toolName: String,
        toolOutput: String,
        toolSuccess: Boolean
    ): ToolCapture {
        if (!toolSuccess) return ToolCapture()
        return ToolCapture(
            verifiedSources = VerifiedSourceExtractor.extract(toolName, toolOutput),
            signal = ToolResponseSignalExtractor.extract(toolName, toolOutput)
        )
    }

    private fun mergeToolCapture(
        hookContext: HookContext,
        capture: ToolCapture
    ) {
        capture.verifiedSources
            .filterNot { source -> hookContext.verifiedSources.any { it.url == source.url } }
            .forEach(hookContext.verifiedSources::add)
        capture.signal?.let { signal ->
            val signals = getOrCreateToolSignals(hookContext)
            signals += signal
            signal.answerMode?.let { hookContext.metadata["answerMode"] = it }
            signal.grounded?.let { hookContext.metadata["grounded"] = it }
            signal.freshness?.let { hookContext.metadata["freshness"] = it }
            signal.retrievedAt?.let { hookContext.metadata["retrievedAt"] = it }
            signal.blockReason?.let { hookContext.metadata["blockReason"] = it }
        }
    }

    private fun captureToolSignals(
        hookContext: HookContext,
        toolName: String,
        toolOutput: String,
        toolSuccess: Boolean
    ) {
        mergeToolCapture(hookContext, extractToolCapture(toolName, toolOutput, toolSuccess))
    }

    @Suppress("UNCHECKED_CAST")
    private fun getOrCreateToolSignals(hookContext: HookContext): MutableList<ToolResponseSignal> {
        return hookContext.metadata.getOrPut(TOOL_SIGNALS_METADATA_KEY) {
            mutableListOf<ToolResponseSignal>()
        } as MutableList<ToolResponseSignal>
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
        toolInput: String,
        tools: List<Any>,
        springCallbacksByName: Map<String, org.springframework.ai.tool.ToolCallback>
    ): ToolInvocationOutcome {
        val adapter = findToolAdapter(toolName, tools)
        if (adapter != null) {
            return try {
                val timeoutMs = adapter.arcCallback.timeoutMs ?: toolCallTimeoutMs
                val output = withTimeout(timeoutMs) {
                    adapter.call(toolInput)
                }
                ToolInvocationOutcome(output = output, success = true, trackAsUsed = true)
            } catch (e: TimeoutCancellationException) {
                val timeoutMs = adapter.arcCallback.timeoutMs ?: toolCallTimeoutMs
                logger.error { "Tool $toolName timed out after ${timeoutMs}ms" }
                ToolInvocationOutcome(
                    output = "Error: Tool '$toolName' timed out after ${timeoutMs}ms",
                    success = false,
                    trackAsUsed = true
                )
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "Tool $toolName execution failed" }
                ToolInvocationOutcome(
                    output = "Error: ${e.message}",
                    success = false,
                    trackAsUsed = true
                )
            }
        }

        val springCallback = springCallbacksByName[toolName]
        if (springCallback != null) {
            return try {
                val output = withTimeout(toolCallTimeoutMs) {
                    runInterruptible(Dispatchers.IO) {
                        springCallback.call(toolInput)
                    }
                }
                ToolInvocationOutcome(
                    output = normalizeSpringToolOutput(output),
                    success = true,
                    trackAsUsed = true
                )
            } catch (e: TimeoutCancellationException) {
                logger.error { "Tool $toolName timed out after ${toolCallTimeoutMs}ms" }
                ToolInvocationOutcome(
                    output = "Error: Tool '$toolName' timed out after ${toolCallTimeoutMs}ms",
                    success = false,
                    trackAsUsed = true
                )
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "Tool $toolName execution failed" }
                ToolInvocationOutcome(
                    output = "Error: ${e.message}",
                    success = false,
                    trackAsUsed = true
                )
            }
        }

        logger.warn { "Tool '$toolName' not found (possibly hallucinated by LLM)" }
        return ToolInvocationOutcome(
            output = "Error: Tool '$toolName' not found",
            success = false,
            trackAsUsed = false
        )
    }

    /**
     * Find a tool adapter by name from the registered tools.
     */
    private fun findToolAdapter(toolName: String, tools: List<Any>): ArcToolCallbackAdapter? {
        return tools.filterIsInstance<ArcToolCallbackAdapter>().firstOrNull { it.arcCallback.name == toolName }
    }

    private fun resolveSpringToolCallbacksByName(
        tools: List<Any>
    ): Map<String, org.springframework.ai.tool.ToolCallback> {
        val localTools = tools.filterIsInstance<LocalTool>()
            .map { unwrapAopProxy(it) }
            .distinctBy { System.identityHashCode(it) }
        val explicitCallbacks = tools
            .filterIsInstance<org.springframework.ai.tool.ToolCallback>()
            .filterNot { it is ArcToolCallbackAdapter }
        val cacheKey = ToolCallbackCacheKey(
            localToolIds = localTools.map(System::identityHashCode),
            explicitCallbackIds = explicitCallbacks.map(System::identityHashCode)
        )
        return springToolCallbackCache.computeIfAbsent(cacheKey) {
            buildSpringToolCallbacksByName(localTools, explicitCallbacks)
        }
    }

    internal fun springToolCallbackCacheEntryCount(): Int = springToolCallbackCache.size

    private fun buildSpringToolCallbacksByName(
        localTools: List<Any>,
        explicitCallbacks: List<org.springframework.ai.tool.ToolCallback>
    ): Map<String, org.springframework.ai.tool.ToolCallback> {
        val reflectedCallbacks = if (localTools.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                MethodToolCallbackProvider.builder()
                    .toolObjects(*localTools.toTypedArray())
                    .build()
                    .toolCallbacks
                    .toList()
            }.getOrElse { ex ->
                logger.warn(ex) { "Failed to resolve @Tool callbacks from LocalTool beans; skipping local tool callback map." }
                emptyList()
            }
        }

        val byName = LinkedHashMap<String, org.springframework.ai.tool.ToolCallback>()
        (explicitCallbacks + reflectedCallbacks).forEach { callback ->
            val name = callback.toolDefinition.name()
            if (name.isNotBlank()) {
                byName.putIfAbsent(name, callback)
            }
        }
        return byName
    }

    private fun unwrapAopProxy(bean: Any): Any {
        if (bean !is Advised) return bean
        return runCatching { bean.targetSource.target }
            .getOrNull()
            ?: bean
    }

    private fun buildToolResponse(
        toolCall: AssistantMessage.ToolCall,
        toolName: String,
        output: String,
        normalizeToolResponseToJson: Boolean
    ): ToolResponseMessage.ToolResponse {
        val responseData = if (normalizeToolResponseToJson) {
            ToolResponsePayloadNormalizer.normalizeForStrictJsonProvider(output)
        } else {
            output
        }
        return ToolResponseMessage.ToolResponse(toolCall.id(), toolName, responseData)
    }

    private fun normalizeSpringToolOutput(output: String): String {
        return runCatching { springToolOutputMapper.readValue(output, String::class.java) }
            .getOrElse { output }
    }

    private fun enrichToolParamsForRequesterAwareTools(
        toolName: String,
        toolParams: Map<String, Any?>,
        metadata: Map<String, Any>
    ): Map<String, Any?> {
        if (toolName !in requesterAwareToolNames) return toolParams

        val hasAssignee = toolParams["assigneeAccountId"]?.toString()?.isNotBlank() == true
        if (hasAssignee) return toolParams

        val hasRequesterEmail = toolParams["requesterEmail"]?.toString()?.isNotBlank() == true
        if (hasRequesterEmail) return toolParams

        val assigneeAccountId = requesterAccountIdMetadataKeys.asSequence()
            .mapNotNull { key -> metadata[key]?.toString()?.trim()?.takeIf { it.isNotBlank() } }
            .firstOrNull()

        if (!assigneeAccountId.isNullOrBlank()) {
            return toolParams + ("assigneeAccountId" to assigneeAccountId)
        }

        val requesterEmail = requesterEmailMetadataKeys.asSequence()
            .mapNotNull { key -> metadata[key]?.toString()?.trim()?.takeIf { it.isNotBlank() } }
            .firstOrNull()
            ?: return toolParams

        return toolParams + ("requesterEmail" to requesterEmail)
    }

    private fun serializeToolInput(toolParams: Map<String, Any?>, rawInput: String?): String {
        if (toolParams.isEmpty()) {
            return rawInput.orEmpty().ifBlank { "{}" }
        }
        return runCatching {
            springToolOutputMapper.writeValueAsString(toolParams)
        }.getOrElse {
            rawInput.orEmpty().ifBlank { "{}" }
        }
    }

    companion object {
        const val TOOL_SIGNALS_METADATA_KEY = "toolSignals"
        private val springToolOutputMapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        private val requesterAwareToolNames = setOf(
            "jira_my_open_issues",
            "jira_due_soon_issues",
            "jira_blocker_digest",
            "jira_daily_briefing",
            "jira_search_my_issues_by_text",
            "bitbucket_review_queue",
            "bitbucket_review_sla_alerts",
            "bitbucket_my_authored_prs",
            "work_personal_focus_plan",
            "work_personal_learning_digest",
            "work_personal_interrupt_guard",
            "work_personal_end_of_day_wrapup",
            "work_prepare_standup_update",
            "work_personal_document_search"
        )
        private val requesterAccountIdMetadataKeys = listOf("requesterAccountId", "accountId")
        private val requesterEmailMetadataKeys = listOf("requesterEmail", "userEmail", "slackUserEmail")
    }

    private data class ToolCapture(
        val verifiedSources: List<VerifiedSource> = emptyList(),
        val signal: ToolResponseSignal? = null
    )

    private data class ParallelToolExecution(
        val response: ToolResponseMessage.ToolResponse,
        val usedToolName: String? = null,
        val capture: ToolCapture = ToolCapture()
    )

    private data class ToolInvocationOutcome(
        val output: String,
        val success: Boolean,
        val trackAsUsed: Boolean
    )

    private data class ToolCallbackCacheKey(
        val localToolIds: List<Int>,
        val explicitCallbackIds: List<Int>
    )
}
