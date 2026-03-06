package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.response.ResponseFilterChain
import com.arc.reactor.response.ResponseFilterContext
import com.arc.reactor.response.ToolResponseSignal
import com.arc.reactor.response.VerifiedSource
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

internal class ExecutionResultFinalizer(
    private val outputGuardPipeline: OutputGuardPipeline?,
    private val responseFilterChain: ResponseFilterChain?,
    private val boundaries: BoundaryProperties,
    private val conversationManager: ConversationManager,
    private val hookExecutor: HookExecutor?,
    private val errorMessageResolver: ErrorMessageResolver,
    private val agentMetrics: AgentMetrics,
    private val outputBoundaryEnforcer: OutputBoundaryEnforcer =
        OutputBoundaryEnforcer(boundaries = boundaries, agentMetrics = agentMetrics),
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    suspend fun finalize(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>,
        startTime: Long,
        attemptLongerResponse: suspend (String, Int, AgentCommand) -> String?
    ): AgentResult {
        val guarded = enrichResponseMetadata(
            applyOutputGuardPipeline(result, command, hookContext, toolsUsed, startTime),
            hookContext
        )
        if (!guarded.success && guarded.errorCode == AgentErrorCode.OUTPUT_GUARD_REJECTED) {
            observeResponse(guarded, command, hookContext, toolsUsed)
            runAfterCompletionHook(hookContext, guarded, toolsUsed, startTime)
            return guarded
        }

        val bounded = enrichResponseMetadata(
            applyOutputBoundaryRule(guarded, command, hookContext, startTime, attemptLongerResponse),
            hookContext
        )
        if (!bounded.success && bounded.errorCode == AgentErrorCode.OUTPUT_TOO_SHORT) {
            observeResponse(bounded, command, hookContext, toolsUsed)
            runAfterCompletionHook(hookContext, bounded, toolsUsed, startTime)
            return bounded
        }

        val filtered = enrichResponseMetadata(
            applyResponseFilters(bounded, command, hookContext, toolsUsed, startTime),
            hookContext
        )
        observeResponse(filtered, command, hookContext, toolsUsed)
        conversationManager.saveHistory(command, filtered)
        runAfterCompletionHook(hookContext, filtered, toolsUsed, startTime)
        return recordFinalExecution(filtered, startTime)
    }

    private suspend fun applyOutputGuardPipeline(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>,
        startTime: Long
    ): AgentResult {
        if (!result.success || result.content == null || outputGuardPipeline == null) return result

        return try {
            val trustMetadata = trustEventMetadata(command, hookContext)
            val guardContext = OutputGuardContext(
                command = command,
                toolsUsed = toolsUsed,
                durationMs = nowMs() - startTime
            )
            when (val guardResult = outputGuardPipeline.check(result.content, guardContext)) {
                is OutputGuardResult.Allowed -> {
                    recordOutputGuardMetadata(hookContext, "allowed", null, "")
                    agentMetrics.recordOutputGuardAction("pipeline", "allowed", "", trustMetadata)
                    result
                }

                is OutputGuardResult.Modified -> {
                    recordOutputGuardMetadata(hookContext, "modified", guardResult.stage, guardResult.reason)
                    agentMetrics.recordOutputGuardAction(
                        guardResult.stage ?: "unknown",
                        "modified",
                        guardResult.reason,
                        trustMetadata
                    )
                    result.copy(content = guardResult.content)
                }

                is OutputGuardResult.Rejected -> {
                    recordOutputGuardMetadata(hookContext, "rejected", guardResult.stage, guardResult.reason)
                    agentMetrics.recordOutputGuardAction(
                        guardResult.stage ?: "unknown",
                        "rejected",
                        guardResult.reason,
                        trustMetadata
                    )
                    outputGuardFailure(reason = guardResult.reason, startTime = startTime)
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Output guard pipeline failed, rejecting (fail-close)" }
            recordOutputGuardMetadata(hookContext, "rejected", "pipeline", "Output guard check failed")
            outputGuardFailure(reason = "Output guard check failed", startTime = startTime)
        }
    }

    private suspend fun applyOutputBoundaryRule(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        startTime: Long,
        attemptLongerResponse: suspend (String, Int, AgentCommand) -> String?
    ): AgentResult {
        if (!result.success || result.content == null) return result

        return outputBoundaryEnforcer.apply(result, command, trustEventMetadata(command, hookContext), attemptLongerResponse)
            ?: outputTooShortFailure(hookContext, startTime)
    }

    private suspend fun applyResponseFilters(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>,
        startTime: Long
    ): AgentResult {
        if (!result.success || result.content == null || responseFilterChain == null) return result

        return try {
            val context = ResponseFilterContext(
                command = command,
                toolsUsed = toolsUsed,
                verifiedSources = hookContext.verifiedSources.toList(),
                durationMs = nowMs() - startTime
            )
            val filteredContent = responseFilterChain.apply(result.content, context)
            val blockedUnverified = captureVerificationBlockReason(
                hookContext,
                filteredContent,
                hookContext.verifiedSources.toList()
            )
            if (blockedUnverified) {
                agentMetrics.recordUnverifiedResponse(
                    trustEventMetadata(command, hookContext) + mapOf("blockReason" to "unverified_sources")
                )
            }
            result.copy(content = filteredContent)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Response filter chain failed, using original content" }
            result
        }
    }

    private fun trustEventMetadata(command: AgentCommand, hookContext: HookContext): Map<String, Any> {
        val metadata = linkedMapOf<String, Any>()
        metadata.putAll(command.metadata)
        metadata["runId"] = hookContext.runId
        metadata["userId"] = hookContext.userId
        val channel = hookContext.channel?.takeIf { it.isNotBlank() }
            ?: command.metadata["channel"]?.toString()?.takeIf { it.isNotBlank() }
        if (channel != null) {
            metadata["channel"] = channel
        }
        val queryPreview = hookContext.userPrompt
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { if (it.length <= 160) it else it.take(159).trimEnd() + "…" }
        if (queryPreview != null) {
            metadata["queryPreview"] = queryPreview
        }
        return metadata
    }

    private fun observeResponse(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>
    ) {
        agentMetrics.recordResponseObservation(
            responseObservationMetadata(result, command, hookContext, toolsUsed)
        )
    }

    private fun responseObservationMetadata(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>
    ): Map<String, Any> {
        val metadata = trustEventMetadata(command, hookContext).toMutableMap()
        metadata["grounded"] = resolveGrounded(result, hookContext)
        metadata["answerMode"] = resolveAnswerMode(result, hookContext)
        metadata["deliveryMode"] = if (hookContext.metadata["schedulerJobId"] != null) "scheduled" else "interactive"
        metadata["toolFamily"] = deriveToolFamily(toolsUsed)
        resolveBlockReason(result, hookContext)?.let { metadata["blockReason"] = it }
        return metadata
    }

    private suspend fun runAfterCompletionHook(
        hookContext: HookContext,
        result: AgentResult,
        toolsUsed: List<String>,
        startTime: Long
    ) {
        try {
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(
                    success = result.success,
                    response = result.content,
                    errorMessage = result.errorMessage,
                    toolsUsed = toolsUsed,
                    totalDurationMs = nowMs() - startTime,
                    errorCode = result.errorCode?.name
                )
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "AfterAgentComplete hook failed" }
        }
    }

    private fun recordFinalExecution(result: AgentResult, startTime: Long): AgentResult {
        val finalResult = result.copy(durationMs = nowMs() - startTime)
        agentMetrics.recordExecution(finalResult)
        return finalResult
    }

    private fun outputGuardFailure(reason: String, startTime: Long): AgentResult {
        return AgentResult.failure(
            errorMessage = reason,
            errorCode = AgentErrorCode.OUTPUT_GUARD_REJECTED,
            durationMs = nowMs() - startTime
        ).also { agentMetrics.recordExecution(it) }
    }

    private fun outputTooShortFailure(hookContext: HookContext, startTime: Long): AgentResult {
        hookContext.metadata["blockReason"] = "output_too_short"
        return AgentResult.failure(
            errorMessage = errorMessageResolver.resolve(AgentErrorCode.OUTPUT_TOO_SHORT, null),
            errorCode = AgentErrorCode.OUTPUT_TOO_SHORT,
            durationMs = nowMs() - startTime
        ).also { agentMetrics.recordExecution(it) }
    }

    private fun recordOutputGuardMetadata(
        hookContext: HookContext,
        action: String,
        stage: String?,
        reason: String
    ) {
        hookContext.metadata["outputGuardAction"] = action
        hookContext.metadata["outputGuardStage"] = stage ?: "pipeline"
        if (reason.isNotBlank()) {
            hookContext.metadata["outputGuardReason"] = reason
            hookContext.metadata["blockReason"] = reason
        }
    }

    private fun captureVerificationBlockReason(
        hookContext: HookContext,
        filteredContent: String,
        sources: List<VerifiedSource>
    ): Boolean {
        if (sources.isNotEmpty()) return false
        if (UNVERIFIED_PATTERNS.any { filteredContent.contains(it, ignoreCase = true) }) {
            hookContext.metadata["blockReason"] = "unverified_sources"
            return true
        }
        return false
    }

    private fun enrichResponseMetadata(result: AgentResult, hookContext: HookContext): AgentResult {
        val toolSignals = readToolSignals(hookContext)
        val verifiedSources = hookContext.verifiedSources.toList()
        val latestSignal = toolSignals.lastOrNull()
        val freshness = latestSignal?.freshness ?: hookContext.metadata["freshness"] as? Map<*, *>
        val outputGuard = buildOutputGuardMetadata(hookContext)
        val metadata = linkedMapOf<String, Any?>()
        metadata["grounded"] = latestSignal?.grounded ?: verifiedSources.isNotEmpty()
        metadata["answerMode"] = latestSignal?.answerMode ?: hookContext.metadata["answerMode"]?.toString()
        metadata["verifiedSourceCount"] = verifiedSources.size
        metadata["verifiedSources"] = verifiedSources.map(::toSourceMap)
        freshness?.let { metadata["freshness"] = sanitizeMap(it) }
        latestSignal?.retrievedAt?.let { metadata["retrievedAt"] = it }
        outputGuard?.let { metadata["outputGuard"] = it }
        resolveBlockReason(result, hookContext)?.let { metadata["blockReason"] = it }
        if (toolSignals.isNotEmpty()) {
            metadata["toolSignals"] = toolSignals.map(::toToolSignalMap)
        }

        val sanitized = linkedMapOf<String, Any>()
        for ((key, value) in metadata) {
            val shouldKeep = when (value) {
                null -> false
                is String -> value.isNotBlank()
                is Collection<*> -> value.isNotEmpty()
                is Map<*, *> -> value.isNotEmpty()
                else -> true
            }
            if (shouldKeep && value != null) {
                sanitized[key] = value
            }
        }
        return result.copy(metadata = result.metadata + sanitized)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readToolSignals(hookContext: HookContext): List<ToolResponseSignal> {
        return hookContext.metadata[ToolCallOrchestrator.TOOL_SIGNALS_METADATA_KEY] as? List<ToolResponseSignal>
            ?: emptyList()
    }

    private fun buildOutputGuardMetadata(hookContext: HookContext): Map<String, Any?>? {
        val action = hookContext.metadata["outputGuardAction"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val data = linkedMapOf<String, Any?>("action" to action)
        hookContext.metadata["outputGuardStage"]?.toString()?.takeIf { it.isNotBlank() }?.let { data["stage"] = it }
        hookContext.metadata["outputGuardReason"]?.toString()?.takeIf { it.isNotBlank() }?.let { data["reason"] = it }
        return data
    }

    private fun resolveBlockReason(result: AgentResult, hookContext: HookContext): String? {
        if (!result.success) {
            return hookContext.metadata["blockReason"]?.toString()?.takeIf { it.isNotBlank() }
                ?: result.errorMessage?.takeIf { it.isNotBlank() }
        }
        return hookContext.metadata["blockReason"]?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun resolveGrounded(result: AgentResult, hookContext: HookContext): Boolean {
        return (result.metadata["grounded"] as? Boolean)
            ?: (hookContext.metadata["grounded"] as? Boolean)
            ?: hookContext.verifiedSources.isNotEmpty()
    }

    private fun resolveAnswerMode(result: AgentResult, hookContext: HookContext): String {
        return result.metadata["answerMode"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: hookContext.metadata["answerMode"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: "unknown"
    }

    private fun deriveToolFamily(toolsUsed: List<String>): String {
        if (toolsUsed.isEmpty()) return "none"
        val families = toolsUsed.map(::toolFamily).toSet()
        return if (families.size == 1) families.first() else "mixed"
    }

    private fun toolFamily(toolName: String): String {
        return when {
            toolName.startsWith("confluence_") -> "confluence"
            toolName.startsWith("jira_") -> "jira"
            toolName.startsWith("bitbucket_") -> "bitbucket"
            toolName.startsWith("work_") -> "work"
            toolName.startsWith("mcp_") -> "mcp"
            else -> "other"
        }
    }

    private fun toSourceMap(source: VerifiedSource): Map<String, Any?> {
        return linkedMapOf(
            "title" to source.title,
            "url" to source.url,
            "toolName" to source.toolName
        )
    }

    private fun toToolSignalMap(signal: ToolResponseSignal): Map<String, Any?> {
        val data = linkedMapOf<String, Any?>("toolName" to signal.toolName)
        signal.answerMode?.let { data["answerMode"] = it }
        signal.grounded?.let { data["grounded"] = it }
        signal.freshness?.let { data["freshness"] = sanitizeMap(it) }
        signal.retrievedAt?.let { data["retrievedAt"] = it }
        return data
    }

    private fun sanitizeMap(input: Map<*, *>): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        for ((key, value) in input) {
            val normalizedKey = key?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: continue
            result[normalizedKey] = when (value) {
                is Map<*, *> -> sanitizeMap(value)
                is List<*> -> value.map { item ->
                    when (item) {
                        is Map<*, *> -> sanitizeMap(item)
                        else -> item
                    }
                }
                else -> value
            }
        }
        return result
    }

    companion object {
        private val UNVERIFIED_PATTERNS = listOf(
            "couldn't verify",
            "cannot verify",
            "검증 가능한 출처를 찾지 못",
            "확인 가능한 출처를 찾지 못"
        )
    }
}
