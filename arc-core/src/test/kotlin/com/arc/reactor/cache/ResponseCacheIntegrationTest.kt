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
 * SpringAiAgentExecutor와 응답 캐싱의 통합 테스트.
 */
class ResponseCacheIntegrationTest {

    @Nested
    inner class CacheHitAndMiss {

        @Test
        fun `second call with same input은(는) return cached response해야 한다`() = runTest {
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

            // 첫 번째 호출 — 캐시 미스, LLM 호출
            val result1 = executor.execute(command)
            assertTrue(result1.success) { "First call should succeed" }
            assertEquals("Cached response", result1.content) { "First call content should match" }

            // 두 번째 호출 — 캐시 히트, LLM 호출 없음
            val result2 = executor.execute(command)
            assertTrue(result2.success) { "Second call should succeed (cached)" }
            assertEquals("Cached response", result2.content) { "Cached content should match" }

            // LLM was called only once 확인
            verify(exactly = 1) { fixture.requestSpec.call() }
        }

        @Test
        fun `high temperature은(는) bypass cache해야 한다`() = runTest {
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

            // Both calls은(는) hit LLM해야 합니다
            executor.execute(command)
            executor.execute(command)

            verify(exactly = 2) { fixture.requestSpec.call() }
        }

        @Test
        fun `different prompts은(는) not share cache해야 한다`() = runTest {
            val fixture = AgentTestFixture()
            every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec

            // different responses for each call 반환
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
    inner class EmptyResponsePoisoningPrevention {

        @Test
        fun `empty LLM response should not be cached`() = runTest {
            val fixture = AgentTestFixture()
            every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec

            // First call returns empty, second returns valid
            val emptyCallSpec = fixture.mockFinalResponse("")
            val validCallSpec = fixture.mockFinalResponse("Valid answer")
            every { fixture.requestSpec.call() } returnsMany listOf(emptyCallSpec, validCallSpec)

            val cache = CaffeineResponseCache()
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = AgentTestFixture.defaultProperties(),
                responseCache = cache,
                cacheableTemperature = 0.5
            )

            val command = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "15 곱하기 23은 얼마야?",
                mode = AgentMode.STANDARD,
                temperature = 0.0
            )

            // First call — empty response, should NOT be cached
            val result1 = executor.execute(command)
            assertTrue(result1.content.isNullOrBlank()) { "First call should return empty content" }

            // Second call — should hit LLM again (not cached empty)
            val result2 = executor.execute(command)
            assertEquals("Valid answer", result2.content) { "Second call should get valid response, not cached empty" }

            // LLM was called twice (empty response was not cached)
            verify(exactly = 2) { fixture.requestSpec.call() }
        }
    }

    @Nested
    inner class CacheDisabled {

        @Test
        fun `null cache은(는) skip caching entirely해야 한다`() = runTest {
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

            // Both calls은(는) hit LLM해야 합니다
            verify(exactly = 2) { fixture.requestSpec.call() }
        }
    }
}
