package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.resilience.CircuitBreakerOpenException
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Tag("matrix")
/**
 * AgentErrorPolicy의 매트릭스 테스트.
 *
 * 다양한 오류 유형에 대한 정책 적용을 검증합니다.
 */
class AgentErrorPolicyMatrixTest {

    private val prefixes = listOf("", "provider: ", "error: ", "[llm] ")
    private val suffixes = listOf("", " please retry", " temporary", " !!!")

    private fun variants(seed: String): Set<String> {
        val casings = listOf(
            seed,
            seed.uppercase(),
            seed.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        )
        return casings.flatMap { c ->
            prefixes.flatMap { p ->
                suffixes.map { s -> "$p$c$s" }
            }
        }.toSet()
    }

    @Test
    fun `classify은(는) handle large message matrix consistently해야 한다`() {
        val policy = AgentErrorPolicy()
        val expectations = linkedMapOf(
            "rate limit exceeded" to AgentErrorCode.RATE_LIMITED,
            "request timeout" to AgentErrorCode.TIMEOUT,
            "context length exceeded" to AgentErrorCode.CONTEXT_TOO_LONG,
            "tool execution failed" to AgentErrorCode.TOOL_ERROR
        )

        var checked = 0
        expectations.forEach { (seed, expected) ->
            variants(seed).forEach { message ->
                val code = policy.classify(RuntimeException(message))
                assertEquals(expected, code, "seed='$seed', message='$message'")
                checked++
            }
        }

        // 4 seeds * 3 casings * 4 prefixes * 4 suffixes = 192 checks
        assertEquals(192, checked)
    }

    @Test
    fun `classify은(는) prioritize timeout over tool when both words exist해야 한다`() {
        val policy = AgentErrorPolicy()
        val message = "tool timeout while executing tool callback"
        assertEquals(AgentErrorCode.TIMEOUT, policy.classify(RuntimeException(message)))
    }

    @Test
    fun `classify은(는) return circuit breaker open for dedicated exception type해야 한다`() {
        val policy = AgentErrorPolicy()
        assertEquals(
            AgentErrorCode.CIRCUIT_BREAKER_OPEN,
            policy.classify(CircuitBreakerOpenException("agent-primary"))
        )
    }

    @Test
    fun `default transient classifier은(는) match broad transient matrix해야 한다`() {
        val seeds = listOf(
            "status 429",
            "http 503",
            "too many requests",
            "rate limit exceeded",
            "connection timeout",
            "timed out waiting for upstream",
            "connection refused",
            "connection reset by peer",
            "internal server error",
            "service unavailable",
            "bad gateway"
        )

        var checked = 0
        seeds.forEach { seed ->
            variants(seed).forEach { message ->
                assertTrue(defaultTransientErrorClassifier(RuntimeException(message)), "message='$message'")
                checked++
            }
        }

        // 11 seeds * 48 variants = 528 checks
        assertEquals(528, checked)
    }

    @Test
    fun `default transient classifier은(는) reject stable non transient matrix해야 한다`() {
        val stableSeeds = listOf(
            "validation failed",
            "invalid argument",
            "schema mismatch",
            "permission denied",
            "business rule violated"
        )

        var checked = 0
        stableSeeds.forEach { seed ->
            variants(seed).forEach { message ->
                assertFalse(defaultTransientErrorClassifier(RuntimeException(message)), "message='$message'")
                checked++
            }
        }

        // 5 seeds * 48 variants = 240 checks
        assertEquals(240, checked)
    }
}
