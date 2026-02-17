package com.arc.reactor.agent

import com.arc.reactor.agent.config.*
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import io.mockk.every
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for concurrency limiting (P1-1) and request timeout (P1-2) features.
 */
class ConcurrencyTimeoutTest {

    private lateinit var fixture: AgentTestFixture

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
        fixture.mockCallResponse()
    }

    @Nested
    inner class ConcurrencyLimiting {

        @Test
        fun `should enforce maxConcurrentRequests limit`() = runBlocking {
            val currentConcurrent = AtomicInteger(0)
            val maxConcurrentObserved = AtomicInteger(0)

            val properties = AgentProperties(
                concurrency = ConcurrencyProperties(
                    maxConcurrentRequests = 2,
                    requestTimeoutMs = 5000
                )
            )
            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            every { fixture.requestSpec.call() } answers {
                val concurrent = currentConcurrent.incrementAndGet()
                maxConcurrentObserved.updateAndGet { max -> maxOf(max, concurrent) }
                Thread.sleep(80)
                currentConcurrent.decrementAndGet()
                fixture.callResponseSpec
            }

            val jobs = List(5) { index ->
                async {
                    executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Request $index"))
                }
            }

            jobs.awaitAll()

            assertTrue(maxConcurrentObserved.get() <= 2,
                "Max concurrent (${maxConcurrentObserved.get()}) should not exceed 2")
        }
    }

    @Nested
    inner class RequestTimeout {

        @Test
        fun `should timeout when request exceeds requestTimeoutMs`() = runBlocking {
            val properties = AgentProperties(
                concurrency = ConcurrencyProperties(
                    maxConcurrentRequests = 20,
                    requestTimeoutMs = 30
                )
            )
            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            every { fixture.requestSpec.call() } answers {
                Thread.sleep(120)
                fixture.callResponseSpec
            }

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertFalse(result.success, "Request should fail due to timeout")
            val errorMessage = requireNotNull(result.errorMessage)
            assertTrue(
                errorMessage.contains("timed out", ignoreCase = true) ||
                errorMessage.contains("timeout", ignoreCase = true),
                "Error message should mention timeout, got: $errorMessage"
            )
        }

        @Test
        fun `should allow requests within timeout`() = runBlocking {
            val properties = AgentProperties(
                concurrency = ConcurrencyProperties(
                    maxConcurrentRequests = 20,
                    requestTimeoutMs = 500
                )
            )
            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            every { fixture.requestSpec.call() } answers {
                Thread.sleep(20)
                fixture.callResponseSpec
            }

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertTrue(result.success, "Request should succeed within timeout")
            assertNull(result.errorMessage, "Successful request should have no error message")
        }
    }
}
