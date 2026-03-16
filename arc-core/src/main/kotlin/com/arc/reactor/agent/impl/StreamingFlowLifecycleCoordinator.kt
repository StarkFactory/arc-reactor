package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.hook.model.HookContext

/**
 * SSE 스트리밍 실행의 수명 주기(lifecycle)를 관리하는 코디네이터.
 *
 * 스트리밍 ReAct 루프가 완료(성공/실패)된 후, 후처리 단계인
 * 완료 처리([StreamingCompletionFinalizer])와 메트릭 기록을 조율한다.
 * finally 블록에서 반드시 MDC 컨텍스트를 정리하여 리소스 누수를 방지한다.
 *
 * 실행 흐름: StreamingReActLoopExecutor → **StreamingFlowLifecycleCoordinator** → StreamingCompletionFinalizer
 *
 * @see StreamingCompletionFinalizer 스트리밍 완료 시 출력 가드/히스토리 저장/경계값 마커 처리
 * @see StreamingExecutionCoordinator 스트리밍 전체 실행을 조율하는 상위 코디네이터
 * @see AgentRunContextManager MDC 컨텍스트 열기/닫기
 */
internal class StreamingFlowLifecycleCoordinator(
    private val streamingCompletionFinalizer: StreamingCompletionFinalizer,
    private val agentMetrics: AgentMetrics,
    private val closeRunContext: () -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    /**
     * 스트리밍 실행 완료 후 최종 처리를 수행한다.
     *
     * @param command 원본 에이전트 명령
     * @param hookContext 훅 컨텍스트 (AfterComplete 훅 실행에 사용)
     * @param toolsUsed 실행 중 사용된 도구 이름 목록
     * @param state 스트리밍 실행 상태 (수집된 콘텐츠, 성공/실패 여부 등)
     * @param startTime 실행 시작 시각(에포크 밀리초)
     * @param emit SSE 이벤트 전송 함수
     */
    suspend fun finalize(
        command: AgentCommand,
        hookContext: HookContext,
        toolsUsed: List<String>,
        state: StreamingExecutionState,
        startTime: Long,
        emit: suspend (String) -> Unit
    ) {
        try {
            // ── 단계 1: 완료 처리 (출력 가드, 히스토리 저장, 경계값 마커) ──
            streamingCompletionFinalizer.finalize(
                command = command,
                hookContext = hookContext,
                streamStarted = state.streamStarted,
                streamSuccess = state.streamSuccess,
                collectedContent = state.collectedContent.toString(),
                lastIterationContent = state.lastIterationContent.toString(),
                streamErrorMessage = state.streamErrorMessage,
                streamErrorCode = state.streamErrorCode?.name,
                toolsUsed = toolsUsed,
                startTime = startTime,
                emit = emit
            )

            // ── 단계 2: 스트리밍 실행 메트릭 기록 ──
            recordStreamingMetrics(state, toolsUsed, startTime)
        } finally {
            // ── 단계 3: MDC 컨텍스트 정리 (리소스 누수 방지) ──
            closeRunContext()
        }
    }

    /** 스트리밍 실행 결과를 성공/실패에 따라 메트릭으로 기록한다. */
    private fun recordStreamingMetrics(
        state: StreamingExecutionState,
        toolsUsed: List<String>,
        startTime: Long
    ) {
        val durationMs = nowMs() - startTime
        val result = if (state.streamSuccess) {
            AgentResult.success(
                content = state.collectedContent.toString(),
                toolsUsed = toolsUsed,
                durationMs = durationMs
            )
        } else {
            AgentResult.failure(
                errorMessage = state.streamErrorMessage ?: "Streaming failed",
                errorCode = state.streamErrorCode ?: AgentErrorCode.UNKNOWN,
                durationMs = durationMs
            )
        }
        agentMetrics.recordStreamingExecution(result)
    }
}
