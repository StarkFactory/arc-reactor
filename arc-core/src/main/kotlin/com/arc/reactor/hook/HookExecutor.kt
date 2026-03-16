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
 * Hook Execution Orchestrator.
 *
 * Executes registered hooks in order and processes results.
 *
 * **Before-hooks** (beforeAgentStart, beforeToolCall) use **blocking semantics**:
 * execution aborts on the first `failOnError=true` failure, returning [HookResult.Reject].
 * This is intentional — a failing before-hook signals that the operation should not proceed.
 *
 * **After-hooks** (afterToolCall, afterAgentComplete) use **observational semantics**:
 * each hook runs independently, logging failures without affecting subsequent hooks
 * (unless `failOnError=true` is explicitly set, which re-throws the exception).
 */
class HookExecutor(
    beforeStartHooks: List<BeforeAgentStartHook> = emptyList(),
    beforeToolCallHooks: List<BeforeToolCallHook> = emptyList(),
    afterToolCallHooks: List<AfterToolCallHook> = emptyList(),
    afterCompleteHooks: List<AfterAgentCompleteHook> = emptyList()
) {

    private val sortedBeforeStartHooks = beforeStartHooks.filter { it.enabled }.sortedBy { it.order }
    private val sortedBeforeToolCallHooks = beforeToolCallHooks.filter { it.enabled }.sortedBy { it.order }
    private val sortedAfterToolCallHooks = afterToolCallHooks.filter { it.enabled }.sortedBy { it.order }
    private val sortedAfterCompleteHooks = afterCompleteHooks.filter { it.enabled }.sortedBy { it.order }

    /**
     * Executes before-start hooks with blocking semantics.
     * Aborts on the first `failOnError=true` failure.
     *
     * @return [HookResult.Continue] to proceed, [HookResult.Reject] to abort
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
     * Executes before-tool-call hooks with blocking semantics.
     * Aborts on the first `failOnError=true` failure.
     *
     * @return [HookResult.Continue] to proceed, [HookResult.Reject] to abort
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
     * Executes after-tool-call hooks with observational semantics.
     * Each hook runs independently; failures are logged and do not block subsequent hooks
     * unless `failOnError=true` is set.
     */
    suspend fun executeAfterToolCall(context: ToolCallContext, result: ToolCallResult) {
        for (hook in sortedAfterToolCallHooks) {
            try {
                hook.afterToolCall(context, result)
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "AfterToolCallHook failed: ${hook::class.simpleName}" }
                if (hook.failOnError) throw e
            }
        }
    }

    /**
     * Executes after-agent-complete hooks with observational semantics.
     * Each hook runs independently; failures are logged and do not block subsequent hooks
     * unless `failOnError=true` is set.
     */
    suspend fun executeAfterAgentComplete(context: HookContext, response: AgentResponse) {
        for (hook in sortedAfterCompleteHooks) {
            try {
                hook.afterAgentComplete(context, response)
            } catch (e: Exception) {
                e.throwIfCancellation()
                logger.error(e) { "AfterAgentCompleteHook failed: ${hook::class.simpleName}" }
                if (hook.failOnError) throw e
            }
        }
    }

    /**
     * Common before-hook execution logic implementing blocking semantics.
     * On `failOnError=true` failure, returns [HookResult.Reject] immediately,
     * skipping remaining hooks. Otherwise logs and continues (fail-open).
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
                e.throwIfCancellation()
                logger.error(e) { "Hook execution failed: ${hook::class.simpleName}" }
                if (hook.failOnError) {
                    return HookResult.Reject("Hook execution failed: ${e.message}")
                }
                // fail-open: continue on error
            }
        }
        return HookResult.Continue
    }
}
