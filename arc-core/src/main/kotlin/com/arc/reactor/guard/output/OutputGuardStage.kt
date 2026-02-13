package com.arc.reactor.guard.output

/**
 * Post-execution guard stage that validates LLM responses.
 *
 * Unlike input [com.arc.reactor.guard.GuardStage] which only allows or rejects,
 * output guard stages can also **modify** content (e.g., masking PII).
 *
 * Stages are executed in [order] sequence by [OutputGuardPipeline].
 * The pipeline is **fail-close**: exceptions cause rejection.
 *
 * ## Implementation Rules
 * - Always rethrow [kotlin.coroutines.cancellation.CancellationException]
 * - Return [OutputGuardResult.Allowed] if no issues detected
 * - Return [OutputGuardResult.Modified] to mask/redact content
 * - Return [OutputGuardResult.Rejected] to block the response entirely
 * - Extract Regex patterns to companion object (avoid hot-path compilation)
 *
 * ## Example
 * ```kotlin
 * class ToxicContentGuard : OutputGuardStage {
 *     override val stageName = "ToxicContent"
 *     override val order = 30
 *     override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
 *         if (containsToxicContent(content)) {
 *             return OutputGuardResult.Rejected(
 *                 reason = "Response contains harmful content",
 *                 category = OutputRejectionCategory.HARMFUL_CONTENT
 *             )
 *         }
 *         return OutputGuardResult.Allowed.DEFAULT
 *     }
 * }
 * ```
 */
interface OutputGuardStage {

    /** Human-readable stage name for logging and metrics. */
    val stageName: String

    /**
     * Execution order. Lower values execute first.
     * Built-in stages use 1-99. Custom stages should use 100+.
     */
    val order: Int

    /** Whether this stage is active. Disabled stages are skipped. */
    val enabled: Boolean get() = true

    /**
     * Check the LLM response content.
     *
     * @param content LLM response content (may be modified by earlier stages)
     * @param context Metadata about the request and execution
     * @return Allowed, Modified (with new content), or Rejected
     */
    suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult
}
