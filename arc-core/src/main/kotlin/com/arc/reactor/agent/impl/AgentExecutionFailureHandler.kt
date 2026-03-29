package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 에이전트 실행 중 발생한 예외를 일관된 [AgentResult] 실패 응답으로 변환하는 핸들러.
 *
 * 실패 처리 흐름:
 * 1. [ErrorMessageResolver]를 사용하여 에러 코드에 맞는 메시지 생성
 * 2. AfterAgentComplete 훅 실행 (fail-open: 훅 실패 시에도 에러 결과 반환)
 * 3. 실패 메트릭 기록
 *
 * @see SpringAiAgentExecutor ReAct 루프에서 예외 발생 시 이 핸들러를 호출
 * @see AgentErrorCode 에이전트 에러 코드 열거형
 * @see ErrorMessageResolver 에러 코드 → 사용자 메시지 변환
 * @see HookExecutor AfterAgentComplete 훅 실행기
 */
internal class AgentExecutionFailureHandler(
    private val errorMessageResolver: ErrorMessageResolver,
    private val hookExecutor: HookExecutor?,
    private val agentMetrics: AgentMetrics,
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    /**
     * 예외를 처리하여 실패 [AgentResult]를 생성한다.
     *
     * @param errorCode 에이전트 에러 코드
     * @param exception 발생한 예외
     * @param hookContext 훅 컨텍스트 (AfterComplete 훅에 전달)
     * @param startTime 실행 시작 시각(에포크 밀리초)
     * @return 에러 정보가 담긴 실패 결과
     */
    suspend fun handle(
        errorCode: AgentErrorCode,
        exception: Exception,
        hookContext: HookContext,
        startTime: Long
    ): AgentResult {
        // ── 단계 1: 에러 코드와 메시지로 실패 결과 생성 ──
        val result = buildFailureResult(errorCode, exception, startTime)

        // ── 단계 2: AfterAgentComplete 훅 실행 (fail-open) ──
        runAfterCompleteHook(result, hookContext)

        // ── 단계 3: 실패 메트릭 기록 ──
        agentMetrics.recordExecution(result)
        return result
    }

    /** 에러 코드와 예외 메시지를 기반으로 실패 [AgentResult]를 구성한다. */
    private fun buildFailureResult(
        errorCode: AgentErrorCode,
        exception: Exception,
        startTime: Long
    ): AgentResult {
        return AgentResult.failure(
            errorMessage = errorMessageResolver.resolve(errorCode, exception.message),
            errorCode = errorCode,
            durationMs = nowMs() - startTime
        )
    }

    /**
     * 실패 결과에 대해 AfterAgentComplete 훅을 실행한다.
     *
     * 훅 실행 실패는 로그만 기록하고 무시한다 (fail-open).
     * 단, CancellationException은 재던져 구조적 동시성을 보존한다.
     */
    private suspend fun runAfterCompleteHook(
        failResult: AgentResult,
        hookContext: HookContext
    ) {
        try {
            hookExecutor?.executeAfterAgentComplete(
                context = hookContext,
                response = AgentResponse(
                    success = false,
                    errorMessage = failResult.errorMessage,
                    toolsUsed = hookContext.toolsUsed.toList(),
                    totalDurationMs = failResult.durationMs,
                    errorCode = failResult.errorCode?.name
                )
            )
        } catch (hookEx: Exception) {
            hookEx.throwIfCancellation()
            logger.error(hookEx) { "에러 처리 중 AfterAgentComplete 훅 실행 실패" }
        }
    }
}
