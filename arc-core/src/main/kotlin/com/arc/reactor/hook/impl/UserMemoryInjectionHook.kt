package com.arc.reactor.hook.impl

import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.memory.UserMemoryManager
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Injects per-user long-term memory into the agent system prompt.
 *
 * When a non-blank userId is present in the [HookContext], this hook:
 * 1. Loads the user's memory via [UserMemoryManager.getContextPrompt]
 * 2. Stores the resulting context string in [HookContext.metadata] under key `userMemoryContext`
 *
 * The agent executor reads this key from metadata and appends it to the system prompt
 * before the first LLM call. This hook is fail-open (errors are logged, never propagated).
 *
 * ## Activation
 * Enabled by setting `arc.reactor.memory.user.enabled=true`.
 *
 * ## Example output in system prompt
 * ```
 * [User Context]
 * User context: team=backend, role=senior engineer | recent topics: Spring AI, MCP integration
 * ```
 */
class UserMemoryInjectionHook(
    private val memoryManager: UserMemoryManager
) : BeforeAgentStartHook {

    /** Run early (order 5) so memory context is available to all subsequent hooks. */
    override val order: Int = 5

    /** Fail-open: memory injection failure should never block request processing. */
    override val failOnError: Boolean = false

    override suspend fun beforeAgentStart(context: HookContext): HookResult {
        val userId = context.userId.takeIf { it.isNotBlank() && it != "anonymous" }
            ?: return HookResult.Continue

        try {
            val contextPrompt = memoryManager.getContextPrompt(userId)
            if (contextPrompt.isNotBlank()) {
                context.metadata[USER_MEMORY_CONTEXT_KEY] = contextPrompt
                logger.debug { "Injected user memory context for userId=$userId" }
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "Failed to load user memory for userId=$userId, continuing without context" }
        }

        return HookResult.Continue
    }

    companion object {
        /** Metadata key used to pass user memory context to the system prompt builder. */
        const val USER_MEMORY_CONTEXT_KEY = "userMemoryContext"
    }
}
