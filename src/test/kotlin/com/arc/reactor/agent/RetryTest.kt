package com.arc.reactor.agent

import com.arc.reactor.agent.config.*
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class RetryTest {

    private lateinit var fixture: AgentTestFixture
    private lateinit var properties: AgentProperties

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
        properties = AgentProperties(
            retry = RetryProperties(
                maxAttempts = 3,
                initialDelayMs = 10,  // short delays for test speed
                multiplier = 2.0,
                maxDelayMs = 100
            )
        )
    }

    @Nested
    inner class TransientErrors {

        @Test
        fun `should succeed after transient failure with retry`() = runBlocking {
            val callCount = AtomicInteger(0)

            every { fixture.requestSpec.call() } answers {
                val count = callCount.incrementAndGet()
                if (count < 3) {
                    throw RuntimeException("Rate limit exceeded: too many requests")
                }
                fixture.callResponseSpec
            }
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Success after retry")

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            result.assertSuccess("Should succeed after retries")
            assertEquals("Success after retry", result.content)
            assertEquals(3, callCount.get(), "Should have called LLM 3 times (2 failures + 1 success)")
        }

        @Test
        fun `should retry on connection error`() = runBlocking {
            val callCount = AtomicInteger(0)

            every { fixture.requestSpec.call() } answers {
                val count = callCount.incrementAndGet()
                if (count == 1) {
                    throw RuntimeException("Connection refused")
                }
                fixture.callResponseSpec
            }
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Recovered")

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            result.assertSuccess()
            assertEquals(2, callCount.get())
        }

        @Test
        fun `should retry on timeout error`() = runBlocking {
            val callCount = AtomicInteger(0)

            every { fixture.requestSpec.call() } answers {
                val count = callCount.incrementAndGet()
                if (count == 1) {
                    throw RuntimeException("Request timed out")
                }
                fixture.callResponseSpec
            }
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("Recovered")

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            result.assertSuccess()
            assertEquals(2, callCount.get())
        }
    }

    @Nested
    inner class NonTransientErrors {

        @Test
        fun `should not retry non-transient errors`() = runBlocking {
            val callCount = AtomicInteger(0)

            every { fixture.requestSpec.call() } answers {
                callCount.incrementAndGet()
                throw RuntimeException("Invalid API key")
            }

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertFalse(result.success) { "Non-transient error should not succeed, got content: ${result.content}" }
            assertEquals(1, callCount.get(), "Should NOT retry non-transient errors")
        }

        @Test
        fun `should not false-positive on error messages containing numbers`() = runBlocking {
            val callCount = AtomicInteger(0)

            // "Processed 500 records" contains "500" but is NOT a server error
            every { fixture.requestSpec.call() } answers {
                callCount.incrementAndGet()
                throw RuntimeException("Processed 500 records successfully")
            }

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            // Should NOT retry because "500" in this context is not a transient error
            // The regex uses word boundaries so "500" in "Processed 500 records" won't match
            assertEquals(1, callCount.get(), "Should not retry false-positive 500")
        }
    }

    @Nested
    inner class BackoffBehavior {

        @Test
        fun `should apply exponential backoff with increasing delays`() = runBlocking {
            val timestamps = mutableListOf<Long>()

            every { fixture.requestSpec.call() } answers {
                timestamps.add(System.nanoTime())
                if (timestamps.size < 3) {
                    throw RuntimeException("Rate limit exceeded")
                }
                fixture.callResponseSpec
            }
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("OK")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties.copy(
                    retry = RetryProperties(
                        maxAttempts = 3,
                        initialDelayMs = 100,
                        multiplier = 2.0,
                        maxDelayMs = 500
                    )
                )
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            assertEquals(3, timestamps.size, "Should have attempted 3 calls")
            val firstDelayMs = (timestamps[1] - timestamps[0]) / 1_000_000
            val secondDelayMs = (timestamps[2] - timestamps[1]) / 1_000_000
            // Verify delays are non-trivial (> 30ms proves backoff is active, not instant retry)
            assertTrue(firstDelayMs > 30) { "First delay (${firstDelayMs}ms) should be non-trivial" }
            assertTrue(secondDelayMs > 30) { "Second delay (${secondDelayMs}ms) should be non-trivial" }
            // Verify exponential increase: second delay should be larger than first
            assertTrue(secondDelayMs > firstDelayMs) {
                "Exponential backoff: second delay (${secondDelayMs}ms) should exceed first (${firstDelayMs}ms)"
            }
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `should make at least one attempt when maxAttempts is zero`() = runBlocking {
            val callCount = AtomicInteger(0)

            every { fixture.requestSpec.call() } answers {
                callCount.incrementAndGet()
                fixture.callResponseSpec
            }
            every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("OK")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties.copy(
                    retry = RetryProperties(maxAttempts = 0, initialDelayMs = 10, multiplier = 2.0, maxDelayMs = 100)
                )
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertTrue(result.success, "Should succeed with at least 1 attempt even when maxAttempts=0")
            assertEquals(1, callCount.get(), "Should have made exactly 1 attempt")
        }

        @Test
        fun `should propagate CancellationException without retry`() {
            every { fixture.requestSpec.call() } answers {
                throw java.util.concurrent.CancellationException("Cancelled")
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties.copy(
                    concurrency = ConcurrencyProperties(requestTimeoutMs = 30000)
                )
            )

            // CancellationException should propagate through execute() (structured concurrency)
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                runBlocking {
                    executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))
                }
            }
        }

        @Test
        fun `should fail when all retry attempts exhausted`() = runBlocking {
            val callCount = AtomicInteger(0)

            every { fixture.requestSpec.call() } answers {
                callCount.incrementAndGet()
                throw RuntimeException("Service unavailable")
            }

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertFalse(result.success, "Should fail after exhausting retries")
            assertNotNull(result.errorMessage) { "Should have error message after exhausting retries" }
            assertEquals(3, callCount.get(), "Should have tried 3 times")
        }
    }
}
