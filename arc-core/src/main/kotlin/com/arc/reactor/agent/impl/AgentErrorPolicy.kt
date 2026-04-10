package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.resilience.CircuitBreakerOpenException

/**
 * HTTP 상태 코드 패턴 — 에러 메시지에서 429, 500, 502, 503, 504를 탐지한다.
 * companion object에 추출하여 hot path에서 Regex 재생성을 방지한다.
 */
private val httpStatusPattern = Regex("(status|http|error|code)[^a-z0-9]*(429|500|502|503|504)")

/** 쿼터 소진 패턴 — Google GenAI 등 일부 SDK는 429를 prefix 없이 노출한다. */
private val standaloneStatusPattern = Regex("(?:^|\\s)(429|500|502|503|504)(?:\\s|$|\\.)")

/**
 * 예외 체인 전체의 메시지를 소문자로 연결하여 반환한다.
 * Spring AI 등은 원본 예외(예: `ClientException: 429 ...`)를 RuntimeException으로
 * 래핑하므로 최상위 메시지만 검사하면 분류가 누락된다.
 * 순환 참조 방지를 위해 최대 10단계까지만 추적한다.
 */
internal fun Throwable.fullMessageChain(): String {
    val builder = StringBuilder()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 10) {
        current.message?.let { builder.append(it).append(' ') }
        if (current.cause === current) break
        current = current.cause
        depth++
    }
    return builder.toString().lowercase()
}

/**
 * 기본 일시적 에러 분류기.
 * 예외가 재시도할 가치가 있는 임시 에러인지 판별한다.
 *
 * 에러 메시지에 다음 패턴이 포함되면 일시적 에러로 판단:
 * - HTTP 상태 코드 429/5xx (prefix 있거나 standalone)
 * - rate limit, too many requests, quota, resource exhausted
 * - timeout, connection refused/reset
 * - internal server error, service unavailable, bad gateway
 *
 * **cause 체인 전체를 검사**하여 래핑된 예외도 정확히 분류한다.
 *
 * @see RetryExecutor 이 분류기를 사용하여 재시도 여부 결정
 */
fun defaultTransientErrorClassifier(e: Exception): Boolean {
    val message = e.fullMessageChain()
    if (message.isEmpty()) return false
    return httpStatusPattern.containsMatchIn(message) ||
        standaloneStatusPattern.containsMatchIn(message) ||
        message.contains("rate limit") ||
        message.contains("too many requests") ||
        message.contains("quota") ||
        message.contains("resource has been exhausted") ||
        message.contains("resource_exhausted") ||
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
     * 분류 우선순위: CircuitBreakerOpen > rate limit/quota/429 > timeout > context length > tool error > unknown
     *
     * **cause 체인 전체를 검사**하여 Spring AI가 래핑한 `Failed to generate content` 뒤의
     * 실제 원인(예: Google GenAI `ClientException: 429 Resource has been exhausted`)도
     * 정확히 RATE_LIMITED로 분류한다.
     */
    fun classify(e: Exception): AgentErrorCode {
        if (e is CircuitBreakerOpenException) return AgentErrorCode.CIRCUIT_BREAKER_OPEN
        val chain = e.fullMessageChain()
        return when {
            chain.contains("rate limit") ||
                chain.contains("too many requests") ||
                chain.contains("quota") ||
                chain.contains("resource has been exhausted") ||
                chain.contains("resource_exhausted") ||
                standaloneStatusPattern.containsMatchIn(chain) && chain.contains("429") -> AgentErrorCode.RATE_LIMITED
            chain.contains("timeout") || chain.contains("timed out") -> AgentErrorCode.TIMEOUT
            chain.contains("context length") -> AgentErrorCode.CONTEXT_TOO_LONG
            chain.contains("tool") -> AgentErrorCode.TOOL_ERROR
            else -> AgentErrorCode.UNKNOWN
        }
    }
}
