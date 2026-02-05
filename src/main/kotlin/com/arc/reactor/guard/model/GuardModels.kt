package com.arc.reactor.guard.model

/**
 * Guard 검사 요청
 */
data class GuardCommand(
    val userId: String,
    val text: String,
    val channel: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * Guard 검사 결과
 */
sealed class GuardResult {
    /**
     * 요청 허용
     */
    data class Allowed(
        val hints: List<String> = emptyList()
    ) : GuardResult() {
        companion object {
            val DEFAULT = Allowed()
        }
    }

    /**
     * 요청 거부
     */
    data class Rejected(
        val reason: String,
        val category: RejectionCategory,
        val stage: String? = null
    ) : GuardResult()
}

/**
 * 거부 카테고리
 */
enum class RejectionCategory {
    /** Rate Limit 초과 */
    RATE_LIMITED,

    /** 잘못된 입력 */
    INVALID_INPUT,

    /** Prompt Injection 탐지 */
    PROMPT_INJECTION,

    /** 업무 외 요청 */
    OFF_TOPIC,

    /** 권한 없음 */
    UNAUTHORIZED,

    /** 시스템 오류 */
    SYSTEM_ERROR
}
