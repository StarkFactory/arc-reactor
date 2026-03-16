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
                initialDelayMs = 10,  // 테스트 속도를 위한 짧은 지연
                multiplier = 2.0,
                maxDelayMs = 100
            )
        )
    }

    @Nested
    inner class TransientErrors {

        @Test
        fun `재시도로 일시적 실패 후 성공해야 한다`() = runBlocking {
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
        fun `연결 오류 시 재시도해야 한다`() = runBlocking {
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
        fun `타임아웃 오류 시 재시도해야 한다`() = runBlocking {
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
        fun `비일시적 오류에 대해 재시도하지 않아야 한다`() = runBlocking {
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
        fun `숫자가 포함된 오류 메시지에 대해 거짓 양성이 발생하지 않아야 한다`() = runBlocking {
            val callCount = AtomicInteger(0)

            // "Processed 500 records" contains "500" but is NOT a server error
            every { fixture.requestSpec.call() } answers {
                callCount.incrementAndGet()
                throw RuntimeException("Processed 500 records successfully")
            }

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            // 이 맥락에서 "500"은 일시적 오류가 아니므로 재시도하면 안 됩니다
            // 정규식이 단어 경계를 사용하므로 "Processed 500 records"의 "500"은 매치되지 않습니다
            assertEquals(1, callCount.get(), "Should not retry false-positive 500")
        }
    }

    @Nested
    inner class BackoffBehavior {

        @Test
        fun `증가하는 지연과 함께 지수 백오프를 적용해야 한다`() = runBlocking {
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
            // 지연이 의미 있는지 확인합니다 (> 30ms이면 백오프가 활성화된 것이며, 즉시 재시도가 아님)
            assertTrue(firstDelayMs > 30) { "First delay (${firstDelayMs}ms) should be non-trivial" }
            assertTrue(secondDelayMs > 30) { "Second delay (${secondDelayMs}ms) should be non-trivial" }
            // 지수 증가를 확인합니다: 두 번째 지연이 첫 번째보다 커야 합니다
            assertTrue(secondDelayMs > firstDelayMs) {
                "Exponential backoff: second delay (${secondDelayMs}ms) should exceed first (${firstDelayMs}ms)"
            }
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `maxAttempts가 0일 때 최소 한 번은 시도해야 한다`() = runBlocking {
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
        fun `재시도 없이 CancellationException을 전파해야 한다`() {
            every { fixture.requestSpec.call() } answers {
                throw java.util.concurrent.CancellationException("Cancelled")
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties.copy(
                    concurrency = ConcurrencyProperties(requestTimeoutMs = 30000)
                )
            )

            // CancellationException은 execute()를 통해 전파되어야 합니다 (구조적 동시성)
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                runBlocking {
                    executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))
                }
            }
        }

        @Test
        fun `모든 재시도가 소진되면 실패해야 한다`() = runBlocking {
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
