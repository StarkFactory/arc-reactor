package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.resilience.CircuitBreakerOpenException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentErrorPolicyTest {

    private val policy = AgentErrorPolicy()

    @Test
    fun `classifies circuit breaker open exception`() {
        val result = policy.classify(CircuitBreakerOpenException())
        assertEquals(AgentErrorCode.CIRCUIT_BREAKER_OPEN, result)
    }

    @Test
    fun `classifies rate limit message`() {
        val result = policy.classify(RuntimeException("Rate limit exceeded"))
        assertEquals(AgentErrorCode.RATE_LIMITED, result)
    }

    @Test
    fun `classifies timeout message`() {
        val result = policy.classify(RuntimeException("Connection timeout"))
        assertEquals(AgentErrorCode.TIMEOUT, result)
    }

    @Test
    fun `classifies context length message`() {
        val result = policy.classify(RuntimeException("context length exceeded"))
        assertEquals(AgentErrorCode.CONTEXT_TOO_LONG, result)
    }

    @Test
    fun `classifies tool message`() {
        val result = policy.classify(RuntimeException("tool execution failed"))
        assertEquals(AgentErrorCode.TOOL_ERROR, result)
    }

    @Test
    fun `classifies unknown when no known pattern exists`() {
        val result = policy.classify(RuntimeException("some unexpected failure"))
        assertEquals(AgentErrorCode.UNKNOWN, result)
    }

    @Test
    fun `delegates transient checks to injected classifier`() {
        val customPolicy = AgentErrorPolicy { e -> e.message == "retry-me" }

        assertTrue(customPolicy.isTransient(RuntimeException("retry-me")), "Custom classifier should mark 'retry-me' as transient")
        assertFalse(customPolicy.isTransient(RuntimeException("do-not-retry")), "Custom classifier should not mark 'do-not-retry' as transient")
    }

    @Test
    fun `default transient classifier detects common transient messages`() {
        assertTrue(defaultTransientErrorClassifier(RuntimeException("HTTP 503 Service Unavailable")), "HTTP 503 should be classified as transient")
        assertTrue(defaultTransientErrorClassifier(RuntimeException("Connection reset by peer")), "Connection reset should be classified as transient")
        assertTrue(defaultTransientErrorClassifier(RuntimeException("Too many requests")), "Rate limit error should be classified as transient")
        assertFalse(defaultTransientErrorClassifier(RuntimeException("Validation failed")), "Validation failure should not be classified as transient")
    }
}
