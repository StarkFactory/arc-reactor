package com.arc.reactor.response

import com.arc.reactor.agent.model.AgentCommand

/**
 * Filter that transforms agent response content before returning to the caller.
 *
 * Filters are executed in [order] sequence by [ResponseFilterChain].
 * Each filter receives the current content and can modify or pass it through.
 *
 * ## Implementation Rules
 * - Always rethrow [kotlin.coroutines.cancellation.CancellationException]
 * - Return the input content unchanged if no transformation is needed
 * - Filters should be idempotent (safe to apply multiple times)
 *
 * ## Example
 * ```kotlin
 * class ProfanityFilter : ResponseFilter {
 *     override val order = 20
 *     override suspend fun filter(content: String, context: ResponseFilterContext): String {
 *         return content.replace(profanityRegex, "***")
 *     }
 * }
 * ```
 */
interface ResponseFilter {

    /**
     * Execution order. Lower values execute first.
     * Built-in filters use 1-99. Custom filters should use 100+.
     */
    val order: Int get() = 100

    /**
     * Transform the response content.
     *
     * @param content Current response content (may have been modified by previous filters)
     * @param context Metadata about the request and execution
     * @return Transformed content
     */
    suspend fun filter(content: String, context: ResponseFilterContext): String
}

/**
 * Context passed to [ResponseFilter] with metadata about the current request.
 */
data class ResponseFilterContext(
    /** Original agent command */
    val command: AgentCommand,
    /** Tools that were used during execution */
    val toolsUsed: List<String>,
    /** Execution duration in milliseconds */
    val durationMs: Long
)
