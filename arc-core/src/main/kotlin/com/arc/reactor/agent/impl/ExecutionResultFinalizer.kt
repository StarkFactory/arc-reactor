package com.arc.reactor.agent.impl

import com.arc.reactor.agent.budget.CostCalculator
import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.CitationProperties
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

/**
 * 실행 결과 최종화기 — Output Guard, 응답 필터, Citation 부착, 대화 저장을 처리합니다.
 *
 * [AgentExecutionCoordinator]에서 ReAct 루프 완료 후 이 클래스로 위임됩니다.
 * 최종화 파이프라인:
 *
 * 1. Output Guard 파이프라인 실행 (fail-close: 실패 시 거부)
 * 2. 출력 길이 경계 검사 (너무 짧으면 더 긴 응답 재시도)
 * 3. 내용 변경 시 Output Guard 재실행 (새로운 LLM 출력 보호)
 * 4. 응답 필터 체인 적용 (미검증 출처 차단 등)
 * 5. Citation(출처 인용) 부착
 * 6. 차단된 응답의 사용자 가시 메시지 생성
 * 7. 응답 메타데이터 풍부화 (grounded, answerMode, verifiedSources 등)
 * 8. 대화 히스토리 저장
 * 9. AfterAgentComplete Hook 실행
 * 10. 최종 메트릭 기록 및 실행 시간 설정
 *
 * @see AgentExecutionCoordinator 이 최종화기를 호출하는 조율기
 * @see OutputGuardPipeline 출력 가드 파이프라인 (fail-close)
 * @see ResponseFilterChain 응답 필터 체인
 * @see OutputBoundaryEnforcer 출력 길이 경계 검사
 */
internal class ExecutionResultFinalizer(
    private val outputGuardPipeline: OutputGuardPipeline?,
    private val responseFilterChain: ResponseFilterChain?,
    private val boundaries: BoundaryProperties,
    private val conversationManager: ConversationManager,
    private val hookExecutor: HookExecutor?,
    private val errorMessageResolver: ErrorMessageResolver,
    private val agentMetrics: AgentMetrics,
    private val citationProperties: CitationProperties = CitationProperties(),
    private val outputBoundaryEnforcer: OutputBoundaryEnforcer =
        OutputBoundaryEnforcer(boundaries = boundaries, agentMetrics = agentMetrics),
    private val costCalculator: CostCalculator = CostCalculator(),
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    /**
     * 에이전트 실행 결과를 최종화하여 사용자에게 반환할 결과를 생성합니다.
     *
     * @param result ReAct 루프에서 반환된 원시 결과
     * @param command 에이전트 실행 명령
     * @param hookContext Hook/메트릭용 실행 컨텍스트
     * @param toolsUsed 실행에 사용된 도구 이름 목록
     * @param startTime 실행 시작 타임스탬프 (밀리초)
     * @param attemptLongerResponse 응답이 너무 짧을 때 더 긴 응답을 시도하는 함수
     * @return 최종화된 에이전트 결과
     * @see OutputGuardPipeline
     * @see OutputBoundaryEnforcer
     */
    suspend fun finalize(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>,
        startTime: Long,
        attemptLongerResponse: suspend (String, Int, AgentCommand) -> String?
    ): AgentResult {
        // ── 사전 계산: 메트릭/관측용 신뢰 이벤트 메타데이터 (요청당 1회) ──
        val eventMetadata = trustEventMetadata(command, hookContext)

        // ── 단계 1: Output Guard 파이프라인 실행 (fail-close) ──
        val guarded = enrichResponseMetadata(
            applyOutputGuardPipeline(result, command, hookContext, toolsUsed, startTime, eventMetadata),
            hookContext
        )
        if (isGuardRejected(guarded)) {
            return finalizeEarlyReturn(guarded, command, hookContext, toolsUsed, startTime, eventMetadata)
        }

        // ── 단계 2: 출력 길이 경계 검사 (너무 짧으면 더 긴 응답 재시도) ──
        val boundaryApplied = applyOutputBoundaryRule(
            guarded, command, hookContext, toolsUsed,
            startTime, attemptLongerResponse, eventMetadata
        )
        val bounded = enrichResponseMetadata(
            boundaryApplied, hookContext
        )
        if (isBoundaryRejected(bounded)) {
            return finalizeEarlyReturn(bounded, command, hookContext, toolsUsed, startTime, eventMetadata)
        }

        // ── 단계 3: 내용 변경 시 Output Guard 재실행 ──
        val reguarded = reapplyOutputGuardIfContentChanged(
            bounded, guarded, command, hookContext, toolsUsed, startTime, eventMetadata
        )
        if (isGuardRejected(reguarded)) {
            return finalizeEarlyReturn(reguarded, command, hookContext, toolsUsed, startTime, eventMetadata)
        }

        // ── 단계 4~6: 응답 필터 -> Citation 부착 -> 차단 응답 가시화 ──
        val completed = enrichAndFinalizeContent(
            reguarded, command, hookContext, toolsUsed, startTime, eventMetadata
        )

        // ── 단계 6.5: 빈 응답 안전망 — LLM이 빈 content를 반환한 경우 에러 처리 ──
        if (isEmptySuccessResponse(completed)) {
            logger.warn { "LLM이 빈 콘텐츠 반환, 에러로 변환 (runId=${hookContext.runId})" }
            val emptyFailure = emptyContentFailure(hookContext, startTime)
            return finalizeEarlyReturn(emptyFailure, command, hookContext, toolsUsed, startTime, eventMetadata)
        }

        // ── 단계 7~10: 관측 -> 대화 저장 -> AfterComplete Hook -> 메트릭 기록 ──
        observeResponse(completed, command, hookContext, toolsUsed, eventMetadata)
        saveHistorySafely(command, completed, hookContext)
        runAfterCompletionHook(hookContext, completed, toolsUsed, startTime)
        return recordFinalExecution(completed, startTime)
    }

    /**
     * 대화 이력 저장을 안전하게 수행한다.
     * 저장 실패 시에도 응답 반환에 영향을 주지 않도록 예외를 억제한다.
     */
    private suspend fun saveHistorySafely(
        command: AgentCommand,
        result: AgentResult,
        hookContext: HookContext
    ) {
        try {
            conversationManager.saveHistory(command, result)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "대화 이력 저장 실패 (runId=${hookContext.runId})" }
        }
    }

    private fun isGuardRejected(result: AgentResult): Boolean =
        !result.success && result.errorCode == AgentErrorCode.OUTPUT_GUARD_REJECTED

    private fun isBoundaryRejected(result: AgentResult): Boolean =
        !result.success && result.errorCode == AgentErrorCode.OUTPUT_TOO_SHORT

    /**
     * LLM이 성공으로 처리했으나 실제 content가 비어있는지 판별한다.
     * Guard 거부(success=false)나 blockReason이 이미 설정된 경우는 제외한다.
     */
    private fun isEmptySuccessResponse(result: AgentResult): Boolean =
        result.success && result.content.isNullOrBlank() && result.errorCode == null

    /**
     * 경계 검사로 내용이 변경되었으면 Output Guard를 재실행합니다.
     *
     * attemptLongerResponse가 새로운 미검증 LLM 출력을 생성했을 수 있으므로
     * 변경된 내용에 대해 Output Guard를 다시 적용합니다.
     * @return 내용 미변경 시 bounded 그대로, 변경 시 재검사된 결과 (거부 포함)
     */
    private suspend fun reapplyOutputGuardIfContentChanged(
        bounded: AgentResult,
        guarded: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>,
        startTime: Long,
        eventMetadata: Map<String, Any>
    ): AgentResult {
        if (bounded.content == guarded.content) return bounded
        return enrichResponseMetadata(
            applyOutputGuardPipeline(bounded, command, hookContext, toolsUsed, startTime, eventMetadata),
            hookContext
        )
    }

    /** 응답 필터 적용 -> Citation 부착 -> 차단 응답 가시화를 수행합니다. */
    private suspend fun enrichAndFinalizeContent(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>,
        startTime: Long,
        eventMetadata: Map<String, Any>
    ): AgentResult {
        val filtered = applyResponseFilters(result, command, hookContext, toolsUsed, startTime, eventMetadata)
        val cited = appendCitations(filtered, hookContext)
        return enrichResponseMetadata(
            ensureVisibleBlockedResponse(cited, command, hookContext), hookContext
        )
    }

    /**
     * 가드/경계 거부 시 관측·대화 저장·Hook 실행을 수행하고 결과를 반환합니다.
     * 사용자 메시지는 실패 결과에서도 저장됩니다 (세션 연속성 보장).
     */
    private suspend fun finalizeEarlyReturn(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>,
        startTime: Long,
        eventMetadata: Map<String, Any>
    ): AgentResult {
        observeResponse(result, command, hookContext, toolsUsed, eventMetadata)
        saveHistorySafely(command, result, hookContext)
        runAfterCompletionHook(hookContext, result, toolsUsed, startTime)
        return result
    }

    /**
     * Output Guard 파이프라인을 실행합니다 (fail-close 정책).
     *
     * 결과: Allowed(통과) / Modified(수정) / Rejected(거부)
     * 예외 발생 시에도 거부 처리하여 안전성을 보장합니다.
     */
    private suspend fun applyOutputGuardPipeline(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>,
        startTime: Long,
        eventMetadata: Map<String, Any>
    ): AgentResult {
        if (!result.success || result.content == null || outputGuardPipeline == null) return result

        return try {
            val guardContext = OutputGuardContext(
                command = command,
                toolsUsed = toolsUsed,
                durationMs = nowMs() - startTime
            )
            when (val guardResult = outputGuardPipeline.check(result.content, guardContext)) {
                is OutputGuardResult.Allowed -> {
                    recordGuardOutcome(hookContext, GUARD_ACTION_ALLOWED, null, "", eventMetadata)
                    result
                }

                is OutputGuardResult.Modified -> {
                    recordGuardOutcome(
                        hookContext, GUARD_ACTION_MODIFIED,
                        guardResult.stage, guardResult.reason, eventMetadata
                    )
                    result.copy(content = guardResult.content)
                }

                is OutputGuardResult.Rejected -> {
                    recordGuardOutcome(
                        hookContext, GUARD_ACTION_REJECTED,
                        guardResult.stage, guardResult.reason, eventMetadata
                    )
                    outputGuardFailure(reason = guardResult.reason, startTime = startTime)
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "출력 Guard 파이프라인 실패, 거부 (fail-close)" }
            recordGuardMetadataOnly(hookContext, GUARD_ACTION_REJECTED, "pipeline", "Output guard check failed")
            outputGuardFailure(reason = "Output guard check failed", startTime = startTime)
        }
    }

    /**
     * 출력 길이 경계 규칙을 적용합니다.
     *
     * Tool 사용이 없고 검증된 출처도 없는 경우에만 더 긴 응답 재시도를 허용합니다.
     * @see OutputBoundaryEnforcer
     */
    private suspend fun applyOutputBoundaryRule(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>,
        startTime: Long,
        attemptLongerResponse: suspend (String, Int, AgentCommand) -> String?,
        eventMetadata: Map<String, Any>
    ): AgentResult {
        if (!result.success || result.content == null) return result

        val effectiveRetry = if (shouldAttemptLongerResponse(result, hookContext, toolsUsed)) {
            attemptLongerResponse
        } else {
            { _: String, _: Int, _: AgentCommand -> null }
        }
        return outputBoundaryEnforcer.enforceOutputBoundaries(result, command, eventMetadata, effectiveRetry)
            ?: outputTooShortFailure(hookContext, startTime)
    }

    /**
     * 더 긴 응답 재시도 가능 여부를 판단합니다.
     *
     * Tool 사용, 검증된 출처, Tool 신호, grounded 상태 중 하나라도 있으면
     * 이미 근거 기반 응답이므로 재시도하지 않습니다.
     */
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

    /** 응답 필터 체인을 적용합니다. 실패 시 원본 내용을 유지합니다 (fail-open). */
    private suspend fun applyResponseFilters(
        result: AgentResult,
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>,
        startTime: Long,
        eventMetadata: Map<String, Any>
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
                    eventMetadata +
                        mapOf(META_BLOCK_REASON to BlockReasonConstants.UNVERIFIED_SOURCES)
                )
            }
            result.copy(content = filteredContent)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "응답 필터 체인 실패, 원본 콘텐츠 사용" }
            result
        }
    }

    /** Citation(출처 인용) 섹션을 응답 끝에 부착합니다. citation.enabled 설정 기반. */
    private fun appendCitations(result: AgentResult, hookContext: HookContext): AgentResult {
        if (!citationProperties.enabled) return result
        if (!result.success || result.content == null) return result
        val sources = hookContext.verifiedSources.distinctBy { it.url }
        if (sources.isEmpty()) return result
        return result.copy(content = result.content + formatCitationSection(sources))
    }

    private fun formatCitationSection(sources: List<VerifiedSource>): String {
        val sb = StringBuilder(CITATION_HEADER)
        for ((index, source) in sources.withIndex()) {
            sb.append("\n- [${index + 1}] ${source.title} (${source.url})")
        }
        return sb.toString()
    }

    /** 메트릭/관측용 신뢰 이벤트 메타데이터를 구성합니다. */
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
        toolsUsed: List<String>,
        eventMetadata: Map<String, Any>
    ) {
        agentMetrics.recordResponseObservation(
            responseObservationMetadata(result, hookContext, toolsUsed, eventMetadata)
        )
    }

    private fun responseObservationMetadata(
        result: AgentResult,
        hookContext: HookContext,
        toolsUsed: List<String>,
        eventMetadata: Map<String, Any>
    ): Map<String, Any> {
        val metadata = LinkedHashMap(eventMetadata)
        metadata["grounded"] = resolveGrounded(result, hookContext)
        metadata["answerMode"] = resolveAnswerMode(result, hookContext)
        metadata["deliveryMode"] = if (hookContext.metadata["schedulerJobId"] != null) "scheduled" else "interactive"
        metadata["toolFamily"] = deriveToolFamily(toolsUsed)
        resolveBlockReason(result, hookContext)?.let { metadata[META_BLOCK_REASON] = it }
        return metadata
    }

    /** AfterAgentComplete Hook을 실행합니다. CancellationException만 재throw합니다 (fail-open). */
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
            logger.error(e) { "AfterAgentComplete 훅 실행 실패" }
        }
    }

    /** 최종 실행 결과에 총 소요 시간을 설정하고 메트릭에 기록합니다. */
    private fun recordFinalExecution(result: AgentResult, startTime: Long): AgentResult {
        val finalResult = result.copy(durationMs = nowMs() - startTime)
        agentMetrics.recordExecution(finalResult)
        return finalResult
    }

    private fun outputGuardFailure(reason: String, startTime: Long): AgentResult {
        logger.warn { "출력 Guard 거부: $reason" }
        return AgentResult.failure(
            errorMessage = "응답이 내부 정책에 의해 차단되었습니다.",
            errorCode = AgentErrorCode.OUTPUT_GUARD_REJECTED,
            durationMs = nowMs() - startTime
        ).also { agentMetrics.recordExecution(it) }
    }

    private fun outputTooShortFailure(hookContext: HookContext, startTime: Long): AgentResult {
        hookContext.metadata[META_BLOCK_REASON] = BlockReasonConstants.OUTPUT_TOO_SHORT
        return AgentResult.failure(
            errorMessage = errorMessageResolver.resolve(AgentErrorCode.OUTPUT_TOO_SHORT, null),
            errorCode = AgentErrorCode.OUTPUT_TOO_SHORT,
            durationMs = nowMs() - startTime
        ).also { agentMetrics.recordExecution(it) }
    }

    /** LLM이 빈 content를 반환한 경우 사용자에게 재시도를 안내하는 실패 결과를 생성한다. */
    private fun emptyContentFailure(hookContext: HookContext, startTime: Long): AgentResult {
        hookContext.metadata[META_BLOCK_REASON] = BlockReasonConstants.OUTPUT_TOO_SHORT
        return AgentResult(
            success = false,
            content = EMPTY_CONTENT_FALLBACK_MESSAGE,
            errorCode = AgentErrorCode.OUTPUT_TOO_SHORT,
            errorMessage = "LLM returned empty content",
            durationMs = nowMs() - startTime
        ).also { agentMetrics.recordExecution(it) }
    }

    /** 예외 발생 시 메타데이터만 기록 (메트릭 제외). 원본 동작 보존. */
    private fun recordGuardMetadataOnly(
        hookContext: HookContext,
        action: String,
        stage: String?,
        reason: String
    ) {
        hookContext.metadata[META_GUARD_ACTION] = action
        hookContext.metadata[META_GUARD_STAGE] = stage ?: "pipeline"
        if (reason.isNotBlank()) {
            hookContext.metadata[META_GUARD_REASON] = reason
            hookContext.metadata[META_BLOCK_REASON] = reason
        }
    }

    private fun recordGuardOutcome(
        hookContext: HookContext,
        action: String,
        stage: String?,
        reason: String,
        eventMetadata: Map<String, Any>
    ) {
        hookContext.metadata[META_GUARD_ACTION] = action
        hookContext.metadata[META_GUARD_STAGE] = stage ?: "pipeline"
        if (reason.isNotBlank()) {
            hookContext.metadata[META_GUARD_REASON] = reason
            hookContext.metadata[META_BLOCK_REASON] = reason
        }
        agentMetrics.recordOutputGuardAction(
            stage ?: "unknown", action, reason, eventMetadata
        )
    }

    private fun captureVerificationBlockReason(
        hookContext: HookContext,
        filteredContent: String,
        sources: List<VerifiedSource>
    ): Boolean {
        if (sources.isNotEmpty()) return false
        if (hookContext.metadata[META_BLOCK_REASON]?.toString()?.isNotBlank() == true) return false
        if (UNVERIFIED_PATTERNS.any { filteredContent.contains(it, ignoreCase = true) }) {
            hookContext.metadata[META_BLOCK_REASON] = BlockReasonConstants.UNVERIFIED_SOURCES
            return true
        }
        return false
    }

    /**
     * 차단된 응답에 사용자에게 보여줄 메시지를 설정합니다.
     *
     * blockReason에 따라 한글/영어 메시지를 자동 선택합니다.
     * Slack 전송 성공 시 전달 확인 메시지로 대체합니다.
     */
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
        if (blockReason != BlockReasonConstants.UNVERIFIED_SOURCES) return null
        val signal = latestDeliverySignal(readToolSignals(hookContext)) ?: return null
        return buildDeliveryAcknowledgement(userPrompt, signal.deliveryMode.orEmpty())
    }

    private fun resolveVisibleBlockReason(command: AgentCommand, hookContext: HookContext): String? {
        hookContext.metadata[META_BLOCK_REASON]?.toString()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        if (WorkspaceMutationIntentDetector.isWorkspaceMutationPrompt(command.userPrompt)) {
            hookContext.metadata[META_BLOCK_REASON] = BlockReasonConstants.READ_ONLY_MUTATION
            return BlockReasonConstants.READ_ONLY_MUTATION
        }
        return null
    }

    /** blockReason별 차단 응답 메시지를 생성합니다. 프롬프트 언어에 따라 한글/영어 선택 */
    private fun buildBlockedResponse(userPrompt: String, blockReason: String): String {
        val hangul = containsHangul(userPrompt)
        val msg = BLOCKED_MESSAGES[blockReason] ?: DEFAULT_BLOCKED_MESSAGE
        return if (hangul) msg.ko else msg.en
    }

    private fun buildDeliveryAcknowledgement(userPrompt: String, deliveryMode: String): String {
        val hangul = containsHangul(userPrompt)
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

    /**
     * 응답 메타데이터를 풍부화합니다.
     *
     * grounded 여부, answerMode, verifiedSources, freshness,
     * Tool 신호, Output Guard 결과, 단계별 소요 시간 등을 추가합니다.
     */
    private fun enrichResponseMetadata(result: AgentResult, hookContext: HookContext): AgentResult {
        val toolSignals = readToolSignals(hookContext)
        val verifiedSources = hookContext.verifiedSources.toList()
        val metadata = linkedMapOf<String, Any?>()
        mergeVerifiedSourcesMetadata(metadata, toolSignals, verifiedSources, hookContext)
        mergeToolSignalsMetadata(metadata, toolSignals, hookContext)
        buildOutputGuardMetadata(hookContext)?.let { metadata["outputGuard"] = it }
        resolveBlockReason(result, hookContext)?.let { metadata[META_BLOCK_REASON] = it }
        appendCostEstimate(metadata, hookContext)
        return result.copy(metadata = result.metadata + stripEmptyValues(metadata))
    }

    /** 검증된 출처(VerifiedSource) 관련 메타데이터를 병합합니다. */
    private fun mergeVerifiedSourcesMetadata(
        metadata: LinkedHashMap<String, Any?>,
        toolSignals: List<ToolResponseSignal>,
        verifiedSources: List<VerifiedSource>,
        hookContext: HookContext
    ) {
        val latestSignal = toolSignals.lastOrNull()
        metadata["grounded"] = latestSignal?.grounded ?: verifiedSources.isNotEmpty()
        metadata["answerMode"] = latestSignal?.answerMode
            ?: hookContext.metadata["answerMode"]?.toString()
        metadata["verifiedSourceCount"] = verifiedSources.size
        metadata["verifiedSources"] = verifiedSources.map(::toSourceMap)
        val freshness = latestSignal?.freshness ?: hookContext.metadata["freshness"] as? Map<*, *>
        freshness?.let { metadata["freshness"] = sanitizeMap(it) }
        latestSignal?.retrievedAt?.let { metadata["retrievedAt"] = it }
    }

    /** 토큰 사용량 기반 비용 추정을 메타데이터에 추가한다. */
    private fun appendCostEstimate(metadata: LinkedHashMap<String, Any?>, hookContext: HookContext) {
        val tokens = hookContext.metadata["tokensUsed"] as? Int ?: return
        val model = hookContext.metadata["modelUsed"]?.toString()
            ?: hookContext.metadata["model"]?.toString() ?: return
        // 입력/출력 토큰을 별도 추적하지 않으므로 산업 표준 75/25 비율로 분할한다.
        // (이전 코드: tokens + tokens/3 ≈ 133% 중복계산 버그 수정)
        val inputTokens = tokens * 3 / 4
        val outputTokens = tokens - inputTokens
        val cost = costCalculator.calculateCost(model, inputTokens, outputTokens)
        metadata["costEstimateUsd"] = "%.6f".format(cost.estimatedCostUsd)
        metadata["tokensUsed"] = tokens
        metadata["modelUsed"] = model
    }

    /** Tool 신호(ToolResponseSignal), 전달 확인, 단계별 소요 시간 메타데이터를 병합합니다. */
    private fun mergeToolSignalsMetadata(
        metadata: LinkedHashMap<String, Any?>,
        toolSignals: List<ToolResponseSignal>,
        hookContext: HookContext
    ) {
        val deliverySignal = latestDeliverySignal(toolSignals)
        deliverySignal?.let {
            metadata["deliveryAcknowledged"] = true
            metadata["delivery"] = linkedMapOf(
                "platform" to it.deliveryPlatform,
                "mode" to it.deliveryMode,
                "toolName" to it.toolName
            )
        }
        val stageTimings = readStageTimings(hookContext)
        if (stageTimings.isNotEmpty()) {
            metadata["stageTimings"] = LinkedHashMap(stageTimings)
        }
        if (toolSignals.isNotEmpty()) {
            metadata["toolSignals"] = toolSignals.map(::toToolSignalMap)
        }
    }

    /** null, 빈 문자열, 빈 컬렉션, 빈 맵을 제거합니다. */
    private fun stripEmptyValues(metadata: Map<String, Any?>): Map<String, Any> {
        val sanitized = linkedMapOf<String, Any>()
        for ((key, value) in metadata) {
            if (isNonEmptyValue(value) && value != null) sanitized[key] = value
        }
        return sanitized
    }

    private fun isNonEmptyValue(value: Any?): Boolean = when (value) {
        null -> false
        is String -> value.isNotBlank()
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    @Suppress("UNCHECKED_CAST")
    private fun readToolSignals(hookContext: HookContext): List<ToolResponseSignal> {
        return hookContext.metadata[ToolCallOrchestrator.TOOL_SIGNALS_METADATA_KEY] as? List<ToolResponseSignal>
            ?: emptyList()
    }

    private fun buildOutputGuardMetadata(hookContext: HookContext): Map<String, Any?>? {
        val action = hookContext.metadata[META_GUARD_ACTION]?.toString()
            ?.takeIf { it.isNotBlank() } ?: return null
        val data = linkedMapOf<String, Any?>("action" to action)
        hookContext.metadata[META_GUARD_STAGE]?.toString()
            ?.takeIf { it.isNotBlank() }?.let { data["stage"] = it }
        hookContext.metadata[META_GUARD_REASON]?.toString()
            ?.takeIf { it.isNotBlank() }?.let { data["reason"] = it }
        return data
    }

    private fun resolveBlockReason(result: AgentResult, hookContext: HookContext): String? {
        if (!result.success) {
            return hookContext.metadata[META_BLOCK_REASON]?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: result.errorMessage?.takeIf { it.isNotBlank() }
        }
        return hookContext.metadata[META_BLOCK_REASON]?.toString()
            ?.takeIf { it.isNotBlank() }
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

    /** 사용된 도구들에서 Tool 패밀리(confluence, jira, mcp 등)를 도출합니다. */
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

    /** 텍스트에 한글이 포함되어 있는지 확인한다. */
    private fun containsHangul(text: String): Boolean {
        return text.any { it in '\uAC00'..'\uD7A3' }
    }

    companion object {
        // Output Guard 메타데이터 키
        private const val META_GUARD_ACTION = "outputGuardAction"
        private const val META_GUARD_STAGE = "outputGuardStage"
        private const val META_GUARD_REASON = "outputGuardReason"
        private const val META_BLOCK_REASON = "blockReason"

        private const val GUARD_ACTION_ALLOWED = "allowed"
        private const val GUARD_ACTION_MODIFIED = "modified"
        private const val GUARD_ACTION_REJECTED = "rejected"

        private const val CITATION_HEADER = "\n\n---\nSources:"

        /** LLM이 빈 응답을 반환했을 때 사용자에게 보여줄 대체 메시지 */
        private const val EMPTY_CONTENT_FALLBACK_MESSAGE =
            "죄송합니다. 응답을 생성하지 못했습니다. 다시 시도해 주세요."

        private val UNVERIFIED_PATTERNS = listOf(
            "couldn't verify",
            "cannot verify",
            "검증 가능한 출처를 찾지 못",
            "확인 가능한 출처를 찾지 못"
        )

        /** 차단 메시지 한글/영어 쌍 */
        private data class BlockedMessage(val ko: String, val en: String)

        /** blockReason별 차단 메시지 맵 */
        private val BLOCKED_MESSAGES = mapOf(
            BlockReasonConstants.POLICY_DENIED to BlockedMessage(
                ko = "현재 접근 정책에 포함되지 않은 Jira, Confluence, Bitbucket, Swagger 범위라 조회할 수 없습니다.",
                en = "This request targets Jira, Confluence, Bitbucket, or Swagger data outside the current access policy."
            ),
            BlockReasonConstants.READ_ONLY_MUTATION to BlockedMessage(
                ko = "현재 workspace는 읽기 전용이라 변경 작업을 수행할 수 없습니다.",
                en = "The current workspace is read-only, so I can't perform this mutation."
            ),
            BlockReasonConstants.IDENTITY_UNRESOLVED to BlockedMessage(
                ko = "요청자 계정을 Jira 사용자로 확인할 수 없어 개인화 조회를 확정할 수 없습니다. " +
                    "requesterEmail과 Atlassian 사용자 매핑을 확인해 주세요.",
                en = "I couldn't resolve the requesting user to a Jira identity, " +
                    "so I can't confirm this personalized result. " +
                    "Check the requesterEmail to Atlassian user mapping."
            ),
            BlockReasonConstants.UPSTREAM_AUTH_FAILED to BlockedMessage(
                ko = "연결된 업무 도구 인증이 실패해 이 조회를 확정할 수 없습니다. " +
                    "시스템 계정 토큰 설정을 확인해 주세요.",
                en = "I couldn't confirm this result because the connected workspace tool " +
                    "authentication failed. Check the system account token."
            ),
            BlockReasonConstants.UPSTREAM_PERMISSION_DENIED to BlockedMessage(
                ko = "연결된 업무 도구 계정에 필요한 권한이 없어 이 조회를 수행할 수 없습니다. " +
                    "시스템 계정 권한을 확인해 주세요.",
                en = "I couldn't complete this lookup because the connected workspace account " +
                    "is missing the required permission."
            ),
            BlockReasonConstants.UPSTREAM_RATE_LIMITED to BlockedMessage(
                ko = "업무 도구 API rate limit에 걸려 지금은 이 조회를 확정할 수 없습니다. " +
                    "잠시 후 다시 시도해 주세요.",
                en = "I couldn't complete this lookup because the workspace API is currently " +
                    "rate limited. Please try again later."
            ),
            BlockReasonConstants.UNVERIFIED_SOURCES to BlockedMessage(
                ko = "검증 가능한 출처를 찾지 못해 답변을 확정할 수 없습니다. " +
                    "승인된 Jira, Confluence, Bitbucket, Swagger/OpenAPI 자료를 다시 조회해 주세요.",
                en = "I couldn't verify this answer from approved sources. " +
                    "Please re-run the query against approved Jira, Confluence, Bitbucket, " +
                    "or Swagger/OpenAPI data."
            )
        )

        /** blockReason이 맵에 없을 때 사용하는 기본 차단 메시지 */
        private val DEFAULT_BLOCKED_MESSAGE = BlockedMessage(
            ko = "안전한 검증 경로를 확인하지 못해 이 요청을 완료할 수 없습니다.",
            en = "I couldn't complete this request through a verified safe path."
        )
    }
}
