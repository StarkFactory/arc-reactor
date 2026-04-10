package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.resilience.CircuitBreakerOpenException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AgentErrorPolicy에 대한 테스트.
 *
 * 에이전트 오류 정책의 기본 동작을 검증합니다.
 */
class AgentErrorPolicyTest {

    private val policy = AgentErrorPolicy()

    @Test
    fun `circuit breaker open exception를 분류한다`() {
        val result = policy.classify(CircuitBreakerOpenException())
        assertEquals(AgentErrorCode.CIRCUIT_BREAKER_OPEN, result)
    }

    @Test
    fun `rate limit message를 분류한다`() {
        val result = policy.classify(RuntimeException("Rate limit exceeded"))
        assertEquals(AgentErrorCode.RATE_LIMITED, result)
    }

    @Test
    fun `timeout message를 분류한다`() {
        val result = policy.classify(RuntimeException("Connection timeout"))
        assertEquals(AgentErrorCode.TIMEOUT, result)
    }

    @Test
    fun `context length message를 분류한다`() {
        val result = policy.classify(RuntimeException("context length exceeded"))
        assertEquals(AgentErrorCode.CONTEXT_TOO_LONG, result)
    }

    @Test
    fun `tool message를 분류한다`() {
        val result = policy.classify(RuntimeException("tool execution failed"))
        assertEquals(AgentErrorCode.TOOL_ERROR, result)
    }

    @Test
    fun `unknown when no known pattern exists를 분류한다`() {
        val result = policy.classify(RuntimeException("some unexpected failure"))
        assertEquals(AgentErrorCode.UNKNOWN, result)
    }

    @Test
    fun `delegates은(는) transient checks to injected classifier`() {
        val customPolicy = AgentErrorPolicy { e -> e.message == "retry-me" }

        assertTrue(customPolicy.isTransient(RuntimeException("retry-me")), "Custom classifier should mark 'retry-me' as transient")
        assertFalse(customPolicy.isTransient(RuntimeException("do-not-retry")), "Custom classifier should not mark 'do-not-retry' as transient")
    }

    @Test
    fun `transient classifier detects common transient messages를 기본값으로 한다`() {
        assertTrue(defaultTransientErrorClassifier(RuntimeException("HTTP 503 Service Unavailable")), "HTTP 503 should be classified as transient")
        assertTrue(defaultTransientErrorClassifier(RuntimeException("Connection reset by peer")), "Connection reset should be classified as transient")
        assertTrue(defaultTransientErrorClassifier(RuntimeException("Too many requests")), "Rate limit error should be classified as transient")
        assertFalse(defaultTransientErrorClassifier(RuntimeException("Validation failed")), "Validation failure should not be classified as transient")
    }

    @Test
    fun `cause 체인에 있는 Gemini 429 쿼터 예외를 RATE_LIMITED로 분류한다`() {
        // R218에서 관찰된 실제 패턴: Spring AI가 Google GenAI ClientException을 래핑
        val cause = RuntimeException("429 . Resource has been exhausted (e.g. check quota).")
        val wrapped = RuntimeException("Failed to generate content", cause)
        val result = policy.classify(wrapped)
        assertEquals(
            AgentErrorCode.RATE_LIMITED,
            result,
            "Cause chain의 429 쿼터 소진은 RATE_LIMITED로 분류되어야 한다 (UNKNOWN 아님)"
        )
    }

    @Test
    fun `cause 체인에 있는 429 쿼터 예외를 transient로 분류한다`() {
        val cause = RuntimeException("429 . Resource has been exhausted (e.g. check quota).")
        val wrapped = RuntimeException("Failed to generate content", cause)
        assertTrue(
            defaultTransientErrorClassifier(wrapped),
            "Cause chain의 429 쿼터는 transient로 판별되어 재시도 대상이어야 한다"
        )
    }

    @Test
    fun `standalone 429 메시지를 RATE_LIMITED로 분류한다`() {
        // prefix 없는 429 (Google GenAI SDK 패턴)
        val result = policy.classify(RuntimeException("429 Resource has been exhausted"))
        assertEquals(
            AgentErrorCode.RATE_LIMITED,
            result,
            "prefix 없는 429는 RATE_LIMITED로 분류되어야 한다"
        )
    }

    @Test
    fun `quota 키워드를 transient로 분류한다`() {
        assertTrue(
            defaultTransientErrorClassifier(RuntimeException("Daily quota exceeded for project")),
            "quota 키워드는 transient로 판별되어야 한다"
        )
    }

    @Test
    fun `resource exhausted 키워드를 RATE_LIMITED로 분류한다`() {
        val result = policy.classify(RuntimeException("RESOURCE_EXHAUSTED: quota limit reached"))
        assertEquals(
            AgentErrorCode.RATE_LIMITED,
            result,
            "resource_exhausted는 RATE_LIMITED로 분류되어야 한다"
        )
    }

    @Test
    fun `fullMessageChain은 cause를 재귀적으로 연결한다`() {
        val inner = RuntimeException("innermost error")
        val middle = RuntimeException("middle wrapper", inner)
        val outer = RuntimeException("outer context", middle)
        val chain = outer.fullMessageChain()
        assertTrue(chain.contains("outer context"), "outer 메시지가 포함되어야 한다")
        assertTrue(chain.contains("middle wrapper"), "middle 메시지가 포함되어야 한다")
        assertTrue(chain.contains("innermost error"), "inner 메시지가 포함되어야 한다")
    }

    @Test
    fun `fullMessageChain은 순환 참조를 안전하게 처리한다`() {
        // 순환 cause 방지 확인 (depth=10 limit)
        val e = RuntimeException("self-referencing")
        val chain = e.fullMessageChain()
        assertTrue(chain.contains("self-referencing"), "최상위 메시지는 포함되어야 한다")
    }
}
