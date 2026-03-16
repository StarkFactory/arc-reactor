package com.arc.reactor.hook

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Hook 실행 오케스트레이터
 *
 * 등록된 Hook들을 order 순서대로 실행하고 결과를 처리한다.
 *
 * ## Before Hook: 차단 시맨틱 (Blocking Semantics)
 * [executeBeforeAgentStart], [executeBeforeToolCall]은 차단 시맨틱을 사용한다:
 * `failOnError=true`인 Hook이 실패하면 즉시 [HookResult.Reject]를 반환하여
 * 나머지 Hook과 에이전트 실행을 중단한다.
 * 왜: Before Hook의 실패는 "이 작업을 진행하면 안 된다"는 신호이기 때문이다.
 *
 * ## After Hook: 관찰 시맨틱 (Observational Semantics)
 * [executeAfterToolCall], [executeAfterAgentComplete]는 관찰 시맨틱을 사용한다:
 * 각 Hook이 독립적으로 실행되며, 실패를 로깅하되 후속 Hook에 영향을 주지 않는다.
 * (`failOnError=true`가 명시적으로 설정된 경우에만 예외를 재던진다)
 * 왜: After Hook은 이미 완료된 작업에 대한 관찰(로깅, 알림)이므로
 * 하나의 실패가 다른 관찰을 방해하면 안 된다.
 *
 * @param beforeStartHooks 에이전트 시작 전 Hook 목록
 * @param beforeToolCallHooks 도구 호출 전 Hook 목록
 * @param afterToolCallHooks 도구 호출 후 Hook 목록
 * @param afterCompleteHooks 에이전트 완료 후 Hook 목록
 *
 * @see BeforeAgentStartHook 실행 전 Hook
 * @see BeforeToolCallHook 도구 호출 전 Hook
 * @see AfterToolCallHook 도구 호출 후 Hook
 * @see AfterAgentCompleteHook 실행 완료 후 Hook
 * @see AgentHook 공통 Hook 속성 (order, enabled, failOnError)
 */
class HookExecutor(
    beforeStartHooks: List<BeforeAgentStartHook> = emptyList(),
    beforeToolCallHooks: List<BeforeToolCallHook> = emptyList(),
    afterToolCallHooks: List<AfterToolCallHook> = emptyList(),
    afterCompleteHooks: List<AfterAgentCompleteHook> = emptyList()
) {

    // 활성화된 Hook만 order 기준 정렬하여 보관
    private val sortedBeforeStartHooks = beforeStartHooks.filter { it.enabled }.sortedBy { it.order }
    private val sortedBeforeToolCallHooks = beforeToolCallHooks.filter { it.enabled }.sortedBy { it.order }
    private val sortedAfterToolCallHooks = afterToolCallHooks.filter { it.enabled }.sortedBy { it.order }
    private val sortedAfterCompleteHooks = afterCompleteHooks.filter { it.enabled }.sortedBy { it.order }

    /**
     * Before-Start Hook을 차단 시맨틱으로 실행한다.
     * `failOnError=true`인 Hook이 실패하면 즉시 중단한다.
     *
     * @return 진행(Continue) 또는 중단(Reject)
     */
    suspend fun executeBeforeAgentStart(context: HookContext): HookResult {
        return executeHooks(
            hooks = sortedBeforeStartHooks,
            context = context
        ) { hook, ctx ->
            hook.beforeAgentStart(ctx)
        }
    }

    /**
     * Before-ToolCall Hook을 차단 시맨틱으로 실행한다.
     * `failOnError=true`인 Hook이 실패하면 즉시 중단한다.
     *
     * @return 진행(Continue) 또는 중단(Reject)
     */
    suspend fun executeBeforeToolCall(context: ToolCallContext): HookResult {
        return executeHooks(
            hooks = sortedBeforeToolCallHooks,
            context = context
        ) { hook, ctx ->
            hook.beforeToolCall(ctx)
        }
    }

    /**
     * After-ToolCall Hook을 관찰 시맨틱으로 실행한다.
     * 각 Hook이 독립 실행되며, 실패는 로깅만 한다.
     * `failOnError=true`인 경우에만 예외를 재던진다.
     */
    suspend fun executeAfterToolCall(context: ToolCallContext, result: ToolCallResult) {
        for (hook in sortedAfterToolCallHooks) {
            try {
                hook.afterToolCall(context, result)
            } catch (e: Exception) {
                // CancellationException은 반드시 먼저 처리하여 재던진다
                e.throwIfCancellation()
                logger.error(e) { "AfterToolCallHook failed: ${hook::class.simpleName}" }
                // failOnError=true인 경우에만 예외 전파
                if (hook.failOnError) throw e
            }
        }
    }

    /**
     * After-AgentComplete Hook을 관찰 시맨틱으로 실행한다.
     * 각 Hook이 독립 실행되며, 실패는 로깅만 한다.
     * `failOnError=true`인 경우에만 예외를 재던진다.
     */
    suspend fun executeAfterAgentComplete(context: HookContext, response: AgentResponse) {
        for (hook in sortedAfterCompleteHooks) {
            try {
                hook.afterAgentComplete(context, response)
            } catch (e: Exception) {
                // CancellationException은 반드시 먼저 처리하여 재던진다
                e.throwIfCancellation()
                logger.error(e) { "AfterAgentCompleteHook failed: ${hook::class.simpleName}" }
                // failOnError=true인 경우에만 예외 전파
                if (hook.failOnError) throw e
            }
        }
    }

    /**
     * Before Hook 공통 실행 로직 (차단 시맨틱).
     *
     * `failOnError=true`인 Hook이 실패하면 [HookResult.Reject]를 즉시 반환하여
     * 나머지 Hook을 건너뛴다. 그렇지 않으면 로깅 후 계속 진행한다 (fail-open).
     *
     * @param hooks 실행할 Hook 목록 (이미 order 정렬 상태)
     * @param context Hook 컨텍스트
     * @param execute Hook 실행 함수
     * @return 모든 Hook 통과 시 Continue, 중단 시 Reject
     */
    private suspend fun <T : AgentHook, C> executeHooks(
        hooks: List<T>,
        context: C,
        execute: suspend (T, C) -> HookResult
    ): HookResult {
        for (hook in hooks) {
            try {
                when (val result = execute(hook, context)) {
                    is HookResult.Continue -> continue
                    is HookResult.Reject -> {
                        logger.warn { "Hook rejected: ${result.reason}" }
                        return result
                    }
                }
            } catch (e: Exception) {
                // CancellationException은 반드시 먼저 처리하여 재던진다
                e.throwIfCancellation()
                logger.error(e) { "Hook execution failed: ${hook::class.simpleName}" }
                if (hook.failOnError) {
                    // Fail-Close: 중요한 Hook 실패 시 실행 중단
                    return HookResult.Reject("Hook execution failed: ${e.message}")
                }
                // Fail-Open: 오류 로깅 후 다음 Hook 계속
            }
        }
        return HookResult.Continue
    }
}
