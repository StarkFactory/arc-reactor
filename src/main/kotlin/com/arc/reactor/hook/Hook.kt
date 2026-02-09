package com.arc.reactor.hook

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult

/**
 * Agent Hook System
 *
 * Extension points called at key moments in the agent execution lifecycle.
 * Hooks enable cross-cutting concerns like logging, authorization, audit,
 * and custom business logic without modifying the core agent.
 *
 * ## Hook Types
 * - [BeforeAgentStartHook]: Before agent starts (auth, budget check, logging)
 * - [BeforeToolCallHook]: Before each tool call (approval, parameter validation)
 * - [AfterToolCallHook]: After each tool call (result logging, notifications)
 * - [AfterAgentCompleteHook]: After agent completes (audit, billing, analytics)
 *
 * ## Execution Flow
 * ```
 * BeforeAgentStart → [Agent Loop] → (BeforeToolCall → Tool → AfterToolCall)* → AfterAgentComplete
 * ```
 *
 * ## Error Handling Policy
 * Hooks default to **fail-open**: errors are logged and the next hook continues.
 * Set [AgentHook.failOnError] to `true` for critical hooks that must fail-close.
 * This contrasts with [com.arc.reactor.guard.impl.GuardPipeline], which is always
 * **fail-close** (any guard stage error rejects the request).
 *
 * ## Execution Order
 * Hooks execute in ascending order by [order] value (lower = earlier)
 *
 * ## Example Usage
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
 */
interface AgentHook {
    /**
     * Hook execution order (lower values execute first)
     *
     * Recommended ranges:
     * - 1-99: Critical/early hooks (auth, security)
     * - 100-199: Standard hooks (logging, audit)
     * - 200+: Late hooks (cleanup, notifications)
     */
    val order: Int get() = 0

    /** Whether this hook is enabled */
    val enabled: Boolean get() = true

    /**
     * Whether hook failures should abort execution.
     *
     * - `false` (default): Fail-open - log error and continue to next hook
     * - `true`: Fail-close - propagate exception, aborting execution
     *
     * Use `true` for critical hooks (auth, security) where failure
     * should prevent agent execution.
     */
    val failOnError: Boolean get() = false
}

/**
 * Before Agent Start Hook
 *
 * Called before the agent begins processing. Use for:
 * - Authorization and permission checks
 * - Budget/quota validation
 * - Request enrichment (add context, metadata)
 * - Audit logging start
 *
 * ## Return Values
 * - [HookResult.Continue]: Allow agent to proceed
 * - [HookResult.Reject]: Block execution with error message
 *
 * @see HookContext for available request information
 */
interface BeforeAgentStartHook : AgentHook {
    /**
     * Called before agent execution starts.
     *
     * @param context Request context including userId, prompt, and metadata
     * @return HookResult indicating whether to continue, reject, or await approval
     */
    suspend fun beforeAgentStart(context: HookContext): HookResult
}

/**
 * Before Tool Call Hook
 *
 * Called before each tool execution. Use for:
 * - Tool-level authorization
 * - Parameter validation and sanitization
 * - Sensitive operation approval
 * - Tool execution logging
 *
 * ## Return Values
 * - [HookResult.Continue]: Allow tool execution
 * - [HookResult.Reject]: Block this specific tool call
 *
 * @see ToolCallContext for tool call information
 */
interface BeforeToolCallHook : AgentHook {
    /**
     * Called before each tool execution.
     *
     * @param context Tool call context including tool name and parameters
     * @return HookResult indicating whether to continue, reject, or await approval
     */
    suspend fun beforeToolCall(context: ToolCallContext): HookResult
}

/**
 * After Tool Call Hook
 *
 * Called after each tool execution completes. Use for:
 * - Result logging and monitoring
 * - Performance metrics collection
 * - Error tracking and alerting
 * - Tool usage analytics
 *
 * Note: This hook cannot modify the result or stop execution.
 *
 * @see ToolCallResult for tool execution results
 */
interface AfterToolCallHook : AgentHook {
    /**
     * Called after each tool execution completes.
     *
     * @param context Tool call context
     * @param result Tool execution result including success status and output
     */
    suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult)
}

/**
 * After Agent Complete Hook
 *
 * Called after the agent finishes processing (success or failure). Use for:
 * - Audit log finalization
 * - Billing and usage tracking
 * - Dashboard/analytics updates
 * - Notification sending
 * - Cleanup operations
 *
 * Note: This hook is called even when the agent fails.
 *
 * @see AgentResponse for complete response information
 */
interface AfterAgentCompleteHook : AgentHook {
    /**
     * Called after agent execution completes.
     *
     * @param context Original request context
     * @param response Agent response including success status, content, and metadata
     */
    suspend fun afterAgentComplete(context: HookContext, response: AgentResponse)
}
