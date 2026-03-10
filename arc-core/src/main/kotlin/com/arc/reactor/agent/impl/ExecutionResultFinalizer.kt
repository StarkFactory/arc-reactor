package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.redactQuerySignal
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
import com.arc.reactor.tool.WorkspaceMutationIntentDetector
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
            applyOutputBoundaryRule(guarded, command, hookContext, toolsUsed, startTime, attemptLongerResponse),
            hookContext
        )
        if (!bounded.success && bounded.errorCode == AgentErrorCode.OUTPUT_TOO_SHORT) {
            observeResponse(bounded, command, hookContext, toolsUsed)
            runAfterCompletionHook(hookContext, bounded, toolsUsed, startTime)
            return bounded
        }

        val filtered = applyResponseFilters(bounded, command, hookContext, toolsUsed, startTime)
        val completed = enrichResponseMetadata(
            ensureVisibleBlockedResponse(filtered, command, hookContext),
            hookContext
        )
        observeResponse(completed, command, hookContext, toolsUsed)
        conversationManager.saveHistory(command, completed)
        runAfterCompletionHook(hookContext, completed, toolsUsed, startTime)
        return recordFinalExecution(completed, startTime)
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
        toolsUsed: List<String>,
        startTime: Long,
        attemptLongerResponse: suspend (String, Int, AgentCommand) -> String?
    ): AgentResult {
        if (!result.success || result.content == null) return result

        val effectiveRetry = if (shouldAttemptLongerResponse(result, hookContext, toolsUsed)) {
            attemptLongerResponse
        } else {
            { _: String, _: Int, _: AgentCommand -> null }
        }
        return outputBoundaryEnforcer.apply(result, command, trustEventMetadata(command, hookContext), effectiveRetry)
            ?: outputTooShortFailure(hookContext, startTime)
    }

    private fun shouldAttemptLongerResponse(
        result: AgentResult,
        hookContext: HookContext,
        toolsUsed: List<String>
    ): Boolean {
        if (toolsUsed.isNotEmpty()) return false
        if (hookContext.verifiedSources.isNotEmpty()) return false
        if (readToolSignals(hookContext).isNotEmpty()) return false
        if (resolveGrounded(result, hookContext)) return false
        return true
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
        val channel = hookContext.channel?.takeIf { it.isNotBlank() }
            ?: command.metadata["channel"]?.toString()?.takeIf { it.isNotBlank() }
        if (channel != null) {
            metadata["channel"] = channel
        }
        val querySignal = redactQuerySignal(hookContext.userPrompt)
        if (querySignal != null) {
            metadata["queryCluster"] = querySignal.clusterId
            metadata["queryLabel"] = querySignal.label
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
        if (hookContext.metadata["blockReason"]?.toString()?.isNotBlank() == true) return false
        if (UNVERIFIED_PATTERNS.any { filteredContent.contains(it, ignoreCase = true) }) {
            hookContext.metadata["blockReason"] = "unverified_sources"
            return true
        }
        return false
    }

    private fun ensureVisibleBlockedResponse(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext
    ): AgentResult {
        if (!result.success) return result
        val blockReason = resolveVisibleBlockReason(command, hookContext) ?: return result
        resolveDeliveryAcknowledgement(command.userPrompt, blockReason, hookContext)?.let { acknowledgement ->
            return result.copy(content = acknowledgement)
        }
        val existingContent = result.content?.trim()
        if (!existingContent.isNullOrBlank()) {
            if (UNVERIFIED_PATTERNS.any { existingContent.contains(it, ignoreCase = true) }) {
                return result.copy(content = buildBlockedResponse(command.userPrompt, blockReason))
            }
            return result
        }
        return result.copy(content = buildBlockedResponse(command.userPrompt, blockReason))
    }

    private fun resolveDeliveryAcknowledgement(
        userPrompt: String,
        blockReason: String,
        hookContext: HookContext
    ): String? {
        if (blockReason != "unverified_sources") return null
        val signal = latestDeliverySignal(readToolSignals(hookContext)) ?: return null
        return buildDeliveryAcknowledgement(userPrompt, signal.deliveryMode.orEmpty())
    }

    private fun resolveVisibleBlockReason(command: AgentCommand, hookContext: HookContext): String? {
        hookContext.metadata["blockReason"]?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(command.userPrompt)) {
            hookContext.metadata["blockReason"] = "read_only_mutation"
            return "read_only_mutation"
        }
        return null
    }

    private fun buildBlockedResponse(userPrompt: String, blockReason: String): String {
        val hangul = userPrompt.any { ch -> ch in '\uAC00'..'\uD7A3' }
        return when (blockReason) {
            "policy_denied" -> if (hangul) {
                "현재 접근 정책에 포함되지 않은 Jira, Confluence, Bitbucket, Swagger 범위라 조회할 수 없습니다."
            } else {
                "This request targets Jira, Confluence, Bitbucket, or Swagger data outside the current access policy."
            }

            "read_only_mutation" -> if (hangul) {
                "현재 workspace는 읽기 전용이라 변경 작업을 수행할 수 없습니다."
            } else {
                "The current workspace is read-only, so I can't perform this mutation."
            }

            "identity_unresolved" -> if (hangul) {
                "요청자 계정을 Jira 사용자로 확인할 수 없어 개인화 조회를 확정할 수 없습니다. requesterEmail과 Atlassian 사용자 매핑을 확인해 주세요."
            } else {
                "I couldn't resolve the requesting user to a Jira identity, so I can't confirm this personalized result. Check the requesterEmail to Atlassian user mapping."
            }

            "upstream_auth_failed" -> if (hangul) {
                "연결된 업무 도구 인증이 실패해 이 조회를 확정할 수 없습니다. 시스템 계정 토큰 설정을 확인해 주세요."
            } else {
                "I couldn't confirm this result because the connected workspace tool authentication failed. Check the system account token."
            }

            "upstream_permission_denied" -> if (hangul) {
                "연결된 업무 도구 계정에 필요한 권한이 없어 이 조회를 수행할 수 없습니다. 시스템 계정 권한을 확인해 주세요."
            } else {
                "I couldn't complete this lookup because the connected workspace account is missing the required permission."
            }

            "upstream_rate_limited" -> if (hangul) {
                "업무 도구 API rate limit에 걸려 지금은 이 조회를 확정할 수 없습니다. 잠시 후 다시 시도해 주세요."
            } else {
                "I couldn't complete this lookup because the workspace API is currently rate limited. Please try again later."
            }

            "unverified_sources" -> if (hangul) {
                "검증 가능한 출처를 찾지 못해 답변을 확정할 수 없습니다. 승인된 Jira, Confluence, Bitbucket, Swagger/OpenAPI 자료를 다시 조회해 주세요."
            } else {
                "I couldn't verify this answer from approved sources. Please re-run the query against approved Jira, Confluence, Bitbucket, or Swagger/OpenAPI data."
            }

            else -> if (hangul) {
                "안전한 검증 경로를 확인하지 못해 이 요청을 완료할 수 없습니다."
            } else {
                "I couldn't complete this request through a verified safe path."
            }
        }
    }

    private fun buildDeliveryAcknowledgement(userPrompt: String, deliveryMode: String): String {
        val hangul = userPrompt.any { ch -> ch in '\uAC00'..'\uD7A3' }
        return when {
            hangul && deliveryMode == "thread_reply" ->
                "Slack 스레드 답글은 전송했습니다. 다만 이 응답 자체는 승인된 출처로 검증된 답변이 아니라 전달 결과만 안내드립니다."
            hangul ->
                "Slack 메시지는 전송했습니다. 다만 이 응답 자체는 승인된 출처로 검증된 답변이 아니라 전달 결과만 안내드립니다."
            deliveryMode == "thread_reply" ->
                "The Slack thread reply was sent. This response is only confirming delivery, not a grounded answer from approved sources."
            else ->
                "The Slack message was sent. This response is only confirming delivery, not a grounded answer from approved sources."
        }
    }

    private fun enrichResponseMetadata(result: AgentResult, hookContext: HookContext): AgentResult {
        val toolSignals = readToolSignals(hookContext)
        val verifiedSources = hookContext.verifiedSources.toList()
        val latestSignal = toolSignals.lastOrNull()
        val deliverySignal = latestDeliverySignal(toolSignals)
        val freshness = latestSignal?.freshness ?: hookContext.metadata["freshness"] as? Map<*, *>
        val outputGuard = buildOutputGuardMetadata(hookContext)
        val metadata = linkedMapOf<String, Any?>()
        metadata["grounded"] = latestSignal?.grounded ?: verifiedSources.isNotEmpty()
        metadata["answerMode"] = latestSignal?.answerMode ?: hookContext.metadata["answerMode"]?.toString()
        metadata["verifiedSourceCount"] = verifiedSources.size
        metadata["verifiedSources"] = verifiedSources.map(::toSourceMap)
        freshness?.let { metadata["freshness"] = sanitizeMap(it) }
        latestSignal?.retrievedAt?.let { metadata["retrievedAt"] = it }
        deliverySignal?.let {
            metadata["deliveryAcknowledged"] = true
            metadata["delivery"] = linkedMapOf(
                "platform" to it.deliveryPlatform,
                "mode" to it.deliveryMode,
                "toolName" to it.toolName
            )
        }
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
        signal.blockReason?.let { data["blockReason"] = it }
        signal.deliveryPlatform?.let { data["deliveryPlatform"] = it }
        signal.deliveryMode?.let { data["deliveryMode"] = it }
        return data
    }

    private fun latestDeliverySignal(toolSignals: List<ToolResponseSignal>): ToolResponseSignal? {
        return toolSignals.lastOrNull { it.deliveryPlatform == "slack" && !it.deliveryMode.isNullOrBlank() }
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
