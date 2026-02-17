package com.arc.reactor.agent.model

/**
 * Agent error classifications with default English messages.
 *
 * Provides standardized error codes for common failure scenarios.
 * Use [ErrorMessageResolver] to customize messages (e.g., localization).
 *
 * ## Example: Korean Error Messages (i18n)
 * ```kotlin
 * val koreanResolver = ErrorMessageResolver { code, originalMessage ->
 *     when (code) {
 *         AgentErrorCode.RATE_LIMITED -> "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."
 *         AgentErrorCode.TIMEOUT -> "요청 시간이 초과되었습니다."
 *         AgentErrorCode.CONTEXT_TOO_LONG -> "입력이 너무 깁니다. 내용을 줄여주세요."
 *         AgentErrorCode.TOOL_ERROR -> "도구 실행 중 오류가 발생했습니다: $originalMessage"
 *         AgentErrorCode.UNKNOWN -> "알 수 없는 오류가 발생했습니다."
 *     }
 * }
 * ```
 */
enum class AgentErrorCode(val defaultMessage: String) {
    RATE_LIMITED("Rate limit exceeded. Please try again later."),
    TIMEOUT("Request timed out."),
    CONTEXT_TOO_LONG("Input is too long. Please reduce the content."),
    TOOL_ERROR("An error occurred during tool execution."),
    GUARD_REJECTED("Request rejected by guard."),
    HOOK_REJECTED("Request rejected by hook."),
    INVALID_RESPONSE("LLM returned an invalid structured response."),
    OUTPUT_GUARD_REJECTED("Response blocked by output guard."),
    OUTPUT_TOO_SHORT("Response is too short to meet quality requirements."),
    CIRCUIT_BREAKER_OPEN("Service temporarily unavailable due to repeated failures. Please try again later."),
    UNKNOWN("An unknown error occurred.")
}

/**
 * Resolves error messages for agent error codes.
 *
 * Implement this interface to provide custom (e.g., localized) error messages.
 * Register as a Spring Bean to override the default English messages.
 *
 * @see DefaultErrorMessageResolver for the default implementation
 */
fun interface ErrorMessageResolver {
    fun resolve(code: AgentErrorCode, originalMessage: String?): String
}

/**
 * Default implementation using English messages from [AgentErrorCode].
 */
class DefaultErrorMessageResolver : ErrorMessageResolver {
    override fun resolve(code: AgentErrorCode, originalMessage: String?): String {
        return when (code) {
            AgentErrorCode.TOOL_ERROR ->
                if (originalMessage != null) "${code.defaultMessage}: $originalMessage"
                else code.defaultMessage
            else -> code.defaultMessage
        }
    }
}
