package com.arc.reactor.guard.output

import com.arc.reactor.agent.model.AgentCommand

/**
 * Context passed to [OutputGuardStage] with metadata about the current request.
 */
data class OutputGuardContext(
    /** Original agent command */
    val command: AgentCommand,
    /** Tools that were used during execution */
    val toolsUsed: List<String>,
    /** Execution duration in milliseconds */
    val durationMs: Long
)

/**
 * Result of an output guard stage check.
 *
 * Three possible outcomes:
 * - [Allowed]: Content is safe, pass through unchanged
 * - [Modified]: Content was sanitized (e.g., PII masked), continue with new content
 * - [Rejected]: Content is unsafe, block the response entirely
 */
sealed class OutputGuardResult {

    /**
     * Content passed the check — no modification needed.
     */
    data class Allowed(
        val hints: List<String> = emptyList()
    ) : OutputGuardResult() {
        companion object {
            val DEFAULT = Allowed()
        }
    }

    /**
     * Content was modified (e.g., PII masked) — continue with sanitized content.
     */
    data class Modified(
        val content: String,
        val reason: String,
        val stage: String? = null
    ) : OutputGuardResult()

    /**
     * Content is unsafe — block the response entirely.
     */
    data class Rejected(
        val reason: String,
        val category: OutputRejectionCategory,
        val stage: String? = null
    ) : OutputGuardResult()
}

/**
 * Categories for output guard rejections.
 */
enum class OutputRejectionCategory {
    /** PII detected in response */
    PII_DETECTED,

    /** Harmful or toxic content */
    HARMFUL_CONTENT,

    /** Policy violation */
    POLICY_VIOLATION,

    /** Guard stage error (fail-close) */
    SYSTEM_ERROR
}
