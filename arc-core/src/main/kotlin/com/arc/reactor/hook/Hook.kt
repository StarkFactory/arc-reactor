package com.arc.reactor.hook

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult

/**
 * 에이전트 Hook 시스템
 *
 * 에이전트 실행 생명주기의 주요 시점에 호출되는 확장 포인트이다.
 * 로깅, 인가, 감사, 커스텀 비즈니스 로직 등 횡단 관심사를
 * 코어 에이전트 코드 수정 없이 추가할 수 있다.
 *
 * ## Hook 유형
 * - [BeforeAgentStartHook]: 에이전트 시작 전 (인증, 예산 확인, 로깅)
 * - [BeforeToolCallHook]: 각 도구 호출 전 (승인, 파라미터 검증)
 * - [AfterToolCallHook]: 각 도구 호출 후 (결과 로깅, 알림)
 * - [AfterAgentCompleteHook]: 에이전트 완료 후 (감사, 과금, 분석)
 *
 * ## 실행 흐름
 * ```
 * BeforeAgentStart → [에이전트 루프] → (BeforeToolCall → 도구 → AfterToolCall)* → AfterAgentComplete
 * ```
 *
 * ## 오류 처리 정책: 기본 Fail-Open
 * Hook은 기본적으로 **fail-open**으로 동작한다: 오류를 로깅하고 다음 Hook을 계속 실행한다.
 * [AgentHook.failOnError]를 `true`로 설정하면 해당 Hook은 fail-close로 동작한다.
 *
 * 왜 기본이 fail-open인가: Hook은 비즈니스 로직(로깅, 알림 등)이므로
 * Hook 실패가 핵심 에이전트 실행을 방해하면 안 된다.
 * 이는 항상 fail-close인 [com.arc.reactor.guard.impl.GuardPipeline]과 의도적으로 대조된다.
 *
 * ## 실행 순서
 * Hook은 [AgentHook.order] 값의 오름차순으로 실행된다 (낮을수록 먼저)
 *
 * ## 사용 예시
 * ```kotlin
 * @Component
 * class AuditHook : AfterAgentCompleteHook {
 *     override val order = 100
 *
 *     override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
 *         auditService.log(
 *             userId = context.userId,
 *             prompt = context.userPrompt,
 *             success = response.success,
 *             toolsUsed = response.toolsUsed
 *         )
 *     }
 * }
 * ```
 *
 * @see HookExecutor Hook을 순서대로 실행하는 오케스트레이터
 */
interface AgentHook {
    /**
     * Hook 실행 순서 (낮은 값이 먼저 실행됨)
     *
     * 권장 범위:
     * - 1-99: 중요/초기 Hook (인증, 보안)
     * - 100-199: 표준 Hook (로깅, 감사)
     * - 200+: 후기 Hook (정리, 알림)
     */
    val order: Int get() = 0

    /** 이 Hook의 활성화 여부 */
    val enabled: Boolean get() = true

    /**
     * Hook 실패 시 실행을 중단할지 여부
     *
     * - `false` (기본값): Fail-Open — 오류 로깅 후 다음 Hook 계속
     * - `true`: Fail-Close — 예외를 전파하여 실행 중단
     *
     * 인증, 보안 등 실패 시 에이전트 실행을 막아야 하는
     * 중요한 Hook에만 `true`를 사용한다.
     */
    val failOnError: Boolean get() = false
}

/**
 * 에이전트 시작 전 Hook
 *
 * 에이전트가 처리를 시작하기 전에 호출된다. 용도:
 * - 인가 및 권한 확인
 * - 예산/할당량 검증
 * - 요청 보강 (컨텍스트, 메타데이터 추가)
 * - 감사 로깅 시작
 *
 * ## 반환값
 * - [HookResult.Continue]: 에이전트 실행 진행
 * - [HookResult.Reject]: 오류 메시지와 함께 실행 차단
 *
 * @see HookContext 요청 컨텍스트 정보
 * @see HookExecutor 오케스트레이션된 실행
 */
interface BeforeAgentStartHook : AgentHook {
    /**
     * 에이전트 실행 시작 전에 호출된다.
     *
     * @param context userId, 프롬프트, 메타데이터를 포함한 요청 컨텍스트
     * @return 진행(Continue), 거부(Reject), 또는 승인 대기를 나타내는 HookResult
     */
    suspend fun beforeAgentStart(context: HookContext): HookResult
}

/**
 * 도구 호출 전 Hook
 *
 * 각 도구 실행 전에 호출된다. 용도:
 * - 도구 수준 인가
 * - 파라미터 검증 및 새니타이징
 * - 민감한 작업 승인
 * - 도구 실행 로깅
 *
 * ## 반환값
 * - [HookResult.Continue]: 도구 실행 진행
 * - [HookResult.Reject]: 해당 도구 호출만 차단
 *
 * @see ToolCallContext 도구 호출 정보
 * @see HookExecutor 오케스트레이션된 실행
 */
interface BeforeToolCallHook : AgentHook {
    /**
     * 각 도구 실행 전에 호출된다.
     *
     * @param context 도구명과 파라미터를 포함한 도구 호출 컨텍스트
     * @return 진행(Continue), 거부(Reject), 또는 승인 대기를 나타내는 HookResult
     */
    suspend fun beforeToolCall(context: ToolCallContext): HookResult
}

/**
 * 도구 호출 후 Hook
 *
 * 각 도구 실행이 완료된 후 호출된다. 용도:
 * - 결과 로깅 및 모니터링
 * - 성능 메트릭 수집
 * - 오류 추적 및 알림
 * - 도구 사용 분석
 *
 * 주의: 이 Hook은 결과를 수정하거나 실행을 중단할 수 없다.
 *
 * @see ToolCallResult 도구 실행 결과
 * @see HookExecutor 오케스트레이션된 실행
 */
interface AfterToolCallHook : AgentHook {
    /**
     * 각 도구 실행 완료 후 호출된다.
     *
     * @param context 도구 호출 컨텍스트
     * @param result 성공 여부와 출력을 포함한 도구 실행 결과
     */
    suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult)
}

/**
 * 에이전트 완료 후 Hook
 *
 * 에이전트가 처리를 마친 후(성공/실패 무관) 호출된다. 용도:
 * - 감사 로그 마무리
 * - 과금 및 사용량 추적
 * - 대시보드/분석 업데이트
 * - 알림 발송
 * - 정리 작업
 *
 * 주의: 에이전트가 실패한 경우에도 호출된다.
 *
 * @see AgentResponse 완전한 응답 정보
 * @see HookExecutor 오케스트레이션된 실행
 */
interface AfterAgentCompleteHook : AgentHook {
    /**
     * 에이전트 실행 완료 후 호출된다.
     *
     * @param context 원본 요청 컨텍스트
     * @param response 성공 여부, 콘텐츠, 메타데이터를 포함한 에이전트 응답
     */
    suspend fun afterAgentComplete(context: HookContext, response: AgentResponse)
}
