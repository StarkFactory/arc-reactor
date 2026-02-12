package com.arc.reactor.cache

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.cache.impl.CaffeineResponseCache
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.prompt.ChatOptions

/**
 * Integration test for response caching with SpringAiAgentExecutor.
 */
class ResponseCacheIntegrationTest {

    @Nested
    inner class CacheHitAndMiss {

        @Test
        fun `second call with same input should return cached response`() = runTest {
            val fixture = AgentTestFixture()
            fixture.mockCallResponse("Cached response")
            every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec

            val cache = CaffeineResponseCache()
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = AgentTestFixture.defaultProperties(),
                responseCache = cache,
                cacheableTemperature = 0.5
            )

            val command = AgentCommand(
                systemPrompt = "You are helpful",
                userPrompt = "What is 2+2?",
                mode = AgentMode.STANDARD,
                temperature = 0.0
            )

            // First call — cache miss, calls LLM
            val result1 = executor.execute(command)
            assertTrue(result1.success) { "First call should succeed" }
            assertEquals("Cached response", result1.content) { "First call content should match" }

            // Second call — cache hit, no LLM call
            val result2 = executor.execute(command)
            assertTrue(result2.success) { "Second call should succeed (cached)" }
            assertEquals("Cached response", result2.content) { "Cached content should match" }

            // Verify LLM was called only once
            verify(exactly = 1) { fixture.requestSpec.call() }
        }

        @Test
        fun `high temperature should bypass cache`() = runTest {
            val fixture = AgentTestFixture()
            fixture.mockCallResponse("Non-deterministic")
            every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec

            val cache = CaffeineResponseCache()
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = AgentTestFixture.defaultProperties(),
                responseCache = cache,
                cacheableTemperature = 0.0
            )

            val command = AgentCommand(
                systemPrompt = "You are helpful",
                userPrompt = "Tell me a joke",
                mode = AgentMode.STANDARD,
                temperature = 0.8 // Above cacheable threshold
            )

            // Both calls should hit LLM
            executor.execute(command)
            executor.execute(command)

            verify(exactly = 2) { fixture.requestSpec.call() }
        }

        @Test
        fun `different prompts should not share cache`() = runTest {
            val fixture = AgentTestFixture()
            every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec

            // Return different responses for each call
            val callSpec1 = fixture.mockFinalResponse("Answer A")
            val callSpec2 = fixture.mockFinalResponse("Answer B")
            every { fixture.requestSpec.call() } returnsMany listOf(callSpec1, callSpec2)

            val cache = CaffeineResponseCache()
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = AgentTestFixture.defaultProperties(),
                responseCache = cache,
                cacheableTemperature = 0.5
            )

            val cmdA = AgentCommand(
                systemPrompt = "sys", userPrompt = "Question A",
                mode = AgentMode.STANDARD, temperature = 0.0
            )
            val cmdB = AgentCommand(
                systemPrompt = "sys", userPrompt = "Question B",
                mode = AgentMode.STANDARD, temperature = 0.0
            )

            val resultA = executor.execute(cmdA)
            val resultB = executor.execute(cmdB)

            assertNotEquals(resultA.content, resultB.content) {
                "Different prompts should not return the same cached content"
            }
            verify(exactly = 2) { fixture.requestSpec.call() }
        }
    }

    @Nested
    inner class CacheDisabled {

        @Test
        fun `null cache should skip caching entirely`() = runTest {
            val fixture = AgentTestFixture()
            fixture.mockCallResponse("No cache")
            every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = AgentTestFixture.defaultProperties(),
                responseCache = null // Disabled
            )

            val command = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "Hello",
                mode = AgentMode.STANDARD,
                temperature = 0.0
            )

            executor.execute(command)
            executor.execute(command)

            // Both calls should hit LLM
            verify(exactly = 2) { fixture.requestSpec.call() }
        }
    }
}
