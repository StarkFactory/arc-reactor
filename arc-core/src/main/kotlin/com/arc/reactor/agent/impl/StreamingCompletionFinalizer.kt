package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.support.formatBoundaryViolation
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 스트리밍 실행이 완료된 후의 후처리를 담당하는 finalizer.
 *
 * 스트리밍 성공 시: 출력 가드 검사 → 대화 히스토리 저장 → 경계값 위반 마커 발행
 * 스트리밍 완료 후(성공/실패 모두): AfterAgentComplete 훅 실행
 *
 * 출력 가드(fail-close): 가드 실패 시 잠재적으로 안전하지 않은 콘텐츠를 히스토리에 저장하지 않는다.
 *
 * @see StreamingFlowLifecycleCoordinator 이 finalizer를 호출하는 수명 주기 코디네이터
 * @see OutputGuardPipeline 출력 가드 파이프라인 (차단/수정/허용)
 * @see OutputBoundaryEnforcer 비-스트리밍 모드의 출력 경계값 적용 (대응 역할)
 * @see ConversationManager 대화 히스토리 저장
 */
internal class StreamingCompletionFinalizer(
    private val boundaries: BoundaryProperties,
    private val conversationManager: ConversationManager,
    private val hookExecutor: HookExecutor?,
    private val agentMetrics: AgentMetrics,
    private val outputGuardPipeline: OutputGuardPipeline? = null,
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    /**
     * 스트리밍 완료 후 최종 처리를 수행한다.
     *
     * @param command 원본 에이전트 명령
     * @param hookContext 훅 컨텍스트
     * @param streamStarted 스트리밍이 시작되었는지 여부
     * @param streamSuccess 스트리밍이 성공적으로 완료되었는지 여부
     * @param collectedContent 전체 스트리밍에서 수집된 콘텐츠
     * @param lastIterationContent 마지막 ReAct 반복에서의 콘텐츠 (히스토리 저장용)
     * @param streamErrorMessage 실패 시 에러 메시지
     * @param streamErrorCode 실패 시 에러 코드명
     * @param toolsUsed 사용된 도구 목록
     * @param startTime 실행 시작 시각
     * @param emit SSE 이벤트 전송 함수
     */
    suspend fun finalize(
        command: AgentCommand,
        hookContext: HookContext,
        streamStarted: Boolean,
        streamSuccess: Boolean,
        collectedContent: String,
        lastIterationContent: String,
        streamErrorMessage: String?,
        streamErrorCode: String?,
        toolsUsed: List<String>,
        startTime: Long,
        emit: suspend (String) -> Unit
    ) {
        if (streamSuccess) {
            // ── 단계 1: 출력 가드 검사 — 가드 통과 시 어시스턴트 응답 포함 저장 ──
            val guardPassed = applyStreamingOutputGuard(command, collectedContent, toolsUsed, startTime, emit)
            val effectiveContent = if (guardPassed) lastIterationContent else ""
            conversationManager.saveStreamingHistory(command, effectiveContent)
            // ── 단계 2: 출력 길이 경계값 위반 시 SSE 마커 발행 ──
            emitBoundaryMarkers(collectedContent, emit)
        } else if (streamStarted) {
            // ── 스트리밍 실패 시에도 사용자 메시지는 기록한다 ──
            conversationManager.saveStreamingHistory(command, "")
        }

        // ── 단계 3: AfterAgentComplete 훅 실행 (fail-open) ──
        try {
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(
                    success = streamSuccess,
                    response = if (streamSuccess) collectedContent else null,
                    errorMessage = if (!streamSuccess) (streamErrorMessage ?: "Streaming failed") else null,
                    toolsUsed = toolsUsed,
                    totalDurationMs = nowMs() - startTime,
                    errorCode = if (!streamSuccess) streamErrorCode else null
                )
            )
        } catch (hookEx: Exception) {
            hookEx.throwIfCancellation()
            logger.error(hookEx) { "AfterAgentComplete hook failed in streaming finally" }
        }
    }

    /**
     * 스트리밍 출력에 대해 출력 가드 파이프라인을 실행한다.
     *
     * @return 가드 통과(Allowed 또는 Modified) 시 true, 거부(Rejected) 또는 에러 시 false.
     *         true인 경우에만 히스토리를 저장해야 한다 (fail-close 정책).
     */
    private suspend fun applyStreamingOutputGuard(
        command: AgentCommand,
        collectedContent: String,
        toolsUsed: List<String>,
        startTime: Long,
        emit: suspend (String) -> Unit
    ): Boolean {
        if (outputGuardPipeline == null || collectedContent.isEmpty()) return true

        return try {
            val guardContext = OutputGuardContext(
                command = command,
                toolsUsed = toolsUsed,
                durationMs = nowMs() - startTime
            )
            when (val result = outputGuardPipeline.check(collectedContent, guardContext)) {
                is OutputGuardResult.Allowed -> {
                    agentMetrics.recordOutputGuardAction("pipeline", "allowed", "", command.metadata)
                    true
                }
                is OutputGuardResult.Modified -> {
                    agentMetrics.recordOutputGuardAction(
                        result.stage ?: "unknown", "modified", result.reason, command.metadata
                    )
                    logger.warn { "Streaming output guard modified content: ${result.reason}" }
                    emit(StreamEventMarker.error(
                        "Output guard modified response: ${result.reason}"
                    ))
                    true
                }
                is OutputGuardResult.Rejected -> {
                    agentMetrics.recordOutputGuardAction(
                        result.stage ?: "unknown", "rejected", result.reason, command.metadata
                    )
                    logger.warn { "Streaming output guard rejected: ${result.reason}" }
                    emit(StreamEventMarker.error(
                        "Output guard rejected response: ${result.reason}"
                    ))
                    false
                }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Streaming output guard failed, rejecting (fail-close)" }
            emit(StreamEventMarker.error("Output guard check failed"))
            false // fail-close: do not save potentially unsafe content to conversation history
        }
    }

    /**
     * 출력 길이가 경계값(최대/최소)을 위반하는 경우 SSE 에러 마커를 발행한다.
     *
     * 스트리밍에서는 이미 전송된 콘텐츠를 회수할 수 없으므로,
     * 위반 사실을 클라이언트에 알리는 마커만 발행한다 (truncation 불가).
     */
    private suspend fun emitBoundaryMarkers(
        collectedContent: String,
        emit: suspend (String) -> Unit
    ) {
        val contentLength = collectedContent.length

        // ── 출력 최대 길이 초과 검사 ──
        if (boundaries.outputMaxChars > 0 && contentLength > boundaries.outputMaxChars) {
            val policy = "warn"
            agentMetrics.recordBoundaryViolation(
                OutputBoundaryEnforcer.VIOLATION_OUTPUT_TOO_LONG, policy, boundaries.outputMaxChars, contentLength
            )
            logger.warn {
                formatBoundaryViolation(
                    OutputBoundaryEnforcer.VIOLATION_OUTPUT_TOO_LONG, policy, boundaries.outputMaxChars, contentLength
                )
            }
            try {
                emit(StreamEventMarker.error(
                    formatBoundaryViolation(
                        OutputBoundaryEnforcer.VIOLATION_OUTPUT_TOO_LONG, policy, boundaries.outputMaxChars, contentLength
                    )
                ))
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.debug { "Could not emit boundary error (collector cancelled)" }
            }
        }

        // ── 출력 최소 길이 미달 검사 ──
        if (boundaries.outputMinChars > 0 && contentLength < boundaries.outputMinChars) {
            val policy = when (boundaries.outputMinViolationMode) {
                OutputMinViolationMode.RETRY_ONCE -> "warn" // falls back to warn in streaming
                else -> boundaries.outputMinViolationMode.name.lowercase()
            }
            agentMetrics.recordBoundaryViolation(
                OutputBoundaryEnforcer.VIOLATION_OUTPUT_TOO_SHORT, policy, boundaries.outputMinChars, contentLength
            )
            logger.warn {
                formatBoundaryViolation(
                    OutputBoundaryEnforcer.VIOLATION_OUTPUT_TOO_SHORT,
                    policy,
                    boundaries.outputMinChars,
                    contentLength
                )
            }
            try {
                emit(StreamEventMarker.error(
                    formatBoundaryViolation(
                        OutputBoundaryEnforcer.VIOLATION_OUTPUT_TOO_SHORT, policy, boundaries.outputMinChars, contentLength
                    )
                ))
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.debug { "Could not emit boundary error (collector cancelled)" }
            }
        }
    }
}
