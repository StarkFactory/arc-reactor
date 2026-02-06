package com.arc.reactor.hook

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Hook Execution Orchestrator
 *
 * Executes registered hooks in order and processes results.
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
     * Execute hooks before agent starts.
     *
     * @return Continue to proceed, Reject to abort
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
     * Execute hooks before tool call.
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
     * Execute hooks after tool call.
     */
    suspend fun executeAfterToolCall(context: ToolCallContext, result: ToolCallResult) {
        for (hook in sortedAfterToolCallHooks) {
            try {
                hook.afterToolCall(context, result)
            } catch (e: Exception) {
                logger.error(e) { "AfterToolCallHook failed: ${hook::class.simpleName}" }
                if (hook.failOnError) throw e
            }
        }
    }

    /**
     * Execute hooks after agent completes.
     */
    suspend fun executeAfterAgentComplete(context: HookContext, response: AgentResponse) {
        for (hook in sortedAfterCompleteHooks) {
            try {
                hook.afterAgentComplete(context, response)
            } catch (e: Exception) {
                logger.error(e) { "AfterAgentCompleteHook failed: ${hook::class.simpleName}" }
                if (hook.failOnError) throw e
            }
        }
    }

    /**
     * Common hook execution logic.
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
