package com.arc.reactor.guard.model

/**
 * Guard check request
 */
data class GuardCommand(
    val userId: String,
    val text: String,
    val channel: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Guard check result
 */
sealed class GuardResult {
    /**
     * Request allowed
     */
    data class Allowed(
        val hints: List<String> = emptyList()
    ) : GuardResult() {
        companion object {
            val DEFAULT = Allowed()
        }
    }

    /**
     * Request rejected
     */
    data class Rejected(
        val reason: String,
        val category: RejectionCategory,
        val stage: String? = null
    ) : GuardResult()
}

/**
 * Rejection category
 */
enum class RejectionCategory {
    /** Rate limit exceeded */
    RATE_LIMITED,

    /** Invalid input */
    INVALID_INPUT,

    /** Prompt injection detected */
    PROMPT_INJECTION,

    /** Off-topic request */
    OFF_TOPIC,

    /** Unauthorized */
    UNAUTHORIZED,

    /** System error */
    SYSTEM_ERROR
}
