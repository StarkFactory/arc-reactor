package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.resilience.CircuitBreakerOpenException

/**
 * HTTP 상태 코드 패턴 — 에러 메시지에서 429, 500, 502, 503, 504를 탐지한다.
 * companion object에 추출하여 hot path에서 Regex 재생성을 방지한다.
 */
private val httpStatusPattern = Regex("(status|http|error|code)[^a-z0-9]*(429|500|502|503|504)")

/**
 * 기본 일시적 에러 분류기.
 * 예외가 재시도할 가치가 있는 임시 에러인지 판별한다.
 *
 * 에러 메시지에 다음 패턴이 포함되면 일시적 에러로 판단:
 * - HTTP 상태 코드 429/5xx
 * - rate limit, timeout, connection refused/reset
 * - internal server error, service unavailable, bad gateway
 *
 * @see RetryExecutor 이 분류기를 사용하여 재시도 여부 결정
 */
fun defaultTransientErrorClassifier(e: Exception): Boolean {
    val message = e.message?.lowercase() ?: return false
    return httpStatusPattern.containsMatchIn(message) ||
        message.contains("rate limit") ||
        message.contains("too many requests") ||
        message.contains("timeout") ||
        message.contains("timed out") ||
        message.contains("connection refused") ||
        message.contains("connection reset") ||
        message.contains("internal server error") ||
        message.contains("service unavailable") ||
        message.contains("bad gateway")
}

/**
 * 에이전트 에러 정책 — 에러의 일시성 판별과 에러 코드 분류를 담당한다.
 *
 * - [isTransient]: 재시도할 가치가 있는 일시적 에러인지 판별
 * - [classify]: 예외를 [AgentErrorCode]로 분류
 *
 * @param transientErrorClassifier 일시적 에러 판별 함수 (커스터마이징 가능)
 * @see SpringAiAgentExecutor 예외 발생 시 에러 코드 분류에 사용
 * @see RetryExecutor 재시도 가능 여부 판별에 사용
 */
internal class AgentErrorPolicy(
    private val transientErrorClassifier: (Exception) -> Boolean = ::defaultTransientErrorClassifier
) {

    /** 예외가 일시적(재시도 가능)인지 판별한다. */
    fun isTransient(e: Exception): Boolean = transientErrorClassifier(e)

    /**
     * 예외를 적절한 [AgentErrorCode]로 분류한다.
     *
     * 분류 우선순위: CircuitBreakerOpen > rate limit > timeout > context length > tool error > unknown
     */
    fun classify(e: Exception): AgentErrorCode {
        return when {
            e is CircuitBreakerOpenException -> AgentErrorCode.CIRCUIT_BREAKER_OPEN
            e.message?.contains("rate limit", ignoreCase = true) == true -> AgentErrorCode.RATE_LIMITED
            e.message?.contains("timeout", ignoreCase = true) == true -> AgentErrorCode.TIMEOUT
            e.message?.contains("context length", ignoreCase = true) == true -> AgentErrorCode.CONTEXT_TOO_LONG
            e.message?.contains("tool", ignoreCase = true) == true -> AgentErrorCode.TOOL_ERROR
            else -> AgentErrorCode.UNKNOWN
        }
    }
}
