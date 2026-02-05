package com.arc.reactor.hook

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult

/**
 * Agent Hook 시스템
 *
 * Agent 실행 라이프사이클의 주요 지점에서 호출되는 확장 포인트.
 *
 * ## Hook 종류
 * - [BeforeAgentStartHook]: Agent 시작 전 (권한 확인, 예산 체크)
 * - [BeforeToolCallHook]: Tool 호출 전 (승인 요청, 파라미터 검증)
 * - [AfterToolCallHook]: Tool 호출 후 (결과 로깅, 알림)
 * - [AfterAgentCompleteHook]: Agent 완료 후 (감사 로그, 비용 정산)
 *
 * ## 실행 순서
 * Hook은 [order] 값에 따라 오름차순 실행 (낮을수록 먼저)
 */
interface AgentHook {
    /** Hook 실행 순서 (낮을수록 먼저) */
    val order: Int get() = 0

    /** Hook 활성화 여부 */
    val enabled: Boolean get() = true
}

/**
 * Agent 시작 전 Hook
 *
 * 권한 확인, 예산 체크, 감사 로깅 시작 등에 사용.
 */
interface BeforeAgentStartHook : AgentHook {
    suspend fun beforeAgentStart(context: HookContext): HookResult
}

/**
 * Tool 호출 전 Hook
 *
 * 승인 요청, 파라미터 검증/변환, 민감 작업 차단 등에 사용.
 */
interface BeforeToolCallHook : AgentHook {
    suspend fun beforeToolCall(context: ToolCallContext): HookResult
}

/**
 * Tool 호출 후 Hook
 *
 * 결과 로깅, 알림 전송, 메트릭 수집 등에 사용.
 */
interface AfterToolCallHook : AgentHook {
    suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult)
}

/**
 * Agent 완료 후 Hook
 *
 * 감사 로그 완료, 비용 정산, 대시보드 업데이트 등에 사용.
 */
interface AfterAgentCompleteHook : AgentHook {
    suspend fun afterAgentComplete(context: HookContext, response: AgentResponse)
}
