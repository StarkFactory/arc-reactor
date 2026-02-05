package com.arc.reactor.hook

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Hook 실행 오케스트레이터
 *
 * 등록된 Hook들을 순서대로 실행하고 결과를 처리.
 */
class HookExecutor(
    private val beforeStartHooks: List<BeforeAgentStartHook> = emptyList(),
    private val beforeToolCallHooks: List<BeforeToolCallHook> = emptyList(),
    private val afterToolCallHooks: List<AfterToolCallHook> = emptyList(),
    private val afterCompleteHooks: List<AfterAgentCompleteHook> = emptyList()
) {

    /**
     * Agent 시작 전 Hook 실행
     *
     * @return Continue면 진행, Reject면 중단
     */
    suspend fun executeBeforeAgentStart(context: HookContext): HookResult {
        return executeHooks(
            hooks = beforeStartHooks.filter { it.enabled }.sortedBy { it.order },
            context = context
        ) { hook, ctx ->
            hook.beforeAgentStart(ctx)
        }
    }

    /**
     * Tool 호출 전 Hook 실행
     */
    suspend fun executeBeforeToolCall(context: ToolCallContext): HookResult {
        return executeHooks(
            hooks = beforeToolCallHooks.filter { it.enabled }.sortedBy { it.order },
            context = context
        ) { hook, ctx ->
            hook.beforeToolCall(ctx)
        }
    }

    /**
     * Tool 호출 후 Hook 실행
     */
    suspend fun executeAfterToolCall(context: ToolCallContext, result: ToolCallResult) {
        afterToolCallHooks
            .filter { it.enabled }
            .sortedBy { it.order }
            .forEach { hook ->
                try {
                    hook.afterToolCall(context, result)
                } catch (e: Exception) {
                    logger.error(e) { "AfterToolCallHook failed: ${hook::class.simpleName}" }
                }
            }
    }

    /**
     * Agent 완료 후 Hook 실행
     */
    suspend fun executeAfterAgentComplete(context: HookContext, response: AgentResponse) {
        afterCompleteHooks
            .filter { it.enabled }
            .sortedBy { it.order }
            .forEach { hook ->
                try {
                    hook.afterAgentComplete(context, response)
                } catch (e: Exception) {
                    logger.error(e) { "AfterAgentCompleteHook failed: ${hook::class.simpleName}" }
                }
            }
    }

    /**
     * Hook 실행 공통 로직
     */
    private suspend fun <T, C> executeHooks(
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
                    is HookResult.Modify -> {
                        logger.debug { "Hook modified params" }
                        return result
                    }
                    is HookResult.PendingApproval -> {
                        logger.info { "Hook pending approval: ${result.approvalId}" }
                        return result
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Hook execution failed" }
                // fail-open: 에러 시 계속 진행 (fail-close 원하면 Reject 반환)
            }
        }
        return HookResult.Continue
    }
}
