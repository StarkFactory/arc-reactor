package com.arc.reactor.resilience

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.resilience.impl.DefaultCircuitBreaker
import io.mockk.every
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 에 대한 통합 테스트. CircuitBreaker with SpringAiAgentExecutor.
 *
 * 다음을 검증합니다: the circuit breaker wraps LLM calls and produces
 * correct error codes when the circuit is open.
 */
class CircuitBreakerIntegrationTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class ExecutorWithCircuitBreaker {

        @Test
        fun `circuit is CLOSED일 때 execute normally해야 한다`() = runTest {
            fixture.mockCallResponse("Hello!")

            val cb = DefaultCircuitBreaker(failureThreshold = 3, name = "test-llm")
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                circuitBreaker = cb
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertTrue(result.success) { "Should succeed when circuit is CLOSED, error: ${result.errorMessage}" }
            assertEquals("Hello!", result.content) { "Should return LLM response" }
            assertEquals(CircuitBreakerState.CLOSED, cb.state()) { "Should remain CLOSED on success" }
        }

        @Test
        fun `circuit is open일 때 return CIRCUIT_BREAKER_OPEN error해야 한다`() = runTest {
            every { fixture.callResponseSpec.chatResponse() } throws RuntimeException("LLM down")

            val cb = DefaultCircuitBreaker(failureThreshold = 2, name = "test-llm")
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                circuitBreaker = cb
            )

            val command = AgentCommand(systemPrompt = "Test", userPrompt = "Hello")

            // the breaker (2 failures)를 트립시킵니다
            repeat(2) {
                val result = executor.execute(command)
                assertFalse(result.success) { "Should fail on LLM error" }
            }

            assertEquals(CircuitBreakerState.OPEN, cb.state()) {
                "Circuit should be OPEN after 2 failures"
            }

            // Next call은(는) be rejected by CB해야 합니다
            val result = executor.execute(command)
            assertFalse(result.success) { "Should fail when circuit is OPEN" }
            assertEquals(AgentErrorCode.CIRCUIT_BREAKER_OPEN, result.errorCode) {
                "Error code should be CIRCUIT_BREAKER_OPEN, got: ${result.errorCode}"
            }
        }

        @Test
        fun `work without circuit breaker (null)해야 한다`() = runTest {
            fixture.mockCallResponse("No CB")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                circuitBreaker = null
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertTrue(result.success) { "Should succeed without CB" }
            assertEquals("No CB", result.content) { "Content should be unmodified" }
        }

        @Test
        fun `circuit closes 후 recover해야 한다`() = runTest {
            val callCount = java.util.concurrent.atomic.AtomicInteger(0)
            val clock = java.util.concurrent.atomic.AtomicLong(1000L)

            val cb = DefaultCircuitBreaker(
                failureThreshold = 2,
                resetTimeoutMs = 5000,
                name = "recovery-test",
                clock = { clock.get() }
            )

            // First: set up to fail
            every { fixture.callResponseSpec.chatResponse() } throws RuntimeException("fail")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                circuitBreaker = cb
            )
            val command = AgentCommand(systemPrompt = "Test", userPrompt = "Hello")

            // the breaker를 트립시킵니다
            repeat(2) { executor.execute(command) }
            assertEquals(CircuitBreakerState.OPEN, cb.state()) { "Should be OPEN" }

            // Advance time past resetTimeout → HALF_OPEN
            clock.addAndGet(5000)

            // Now set up to succeed
            fixture.mockCallResponse("recovered!")

            val result = executor.execute(command)
            assertTrue(result.success) { "Should succeed in HALF_OPEN trial, error: ${result.errorMessage}" }
            assertEquals("recovered!", result.content) { "Should return recovered content" }
            assertEquals(CircuitBreakerState.CLOSED, cb.state()) {
                "Should transition HALF_OPEN → CLOSED on success"
            }
        }
    }
}
