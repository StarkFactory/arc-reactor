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
            fixture.mockCallResponse("The answer to your question is four (2+2=4).")
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
            assertEquals("The answer to your question is four (2+2=4).", result1.content) { "First call content should match" }

            // 두 번째 호출 — 캐시 히트, LLM 호출 없음
            val result2 = executor.execute(command)
            assertTrue(result2.success) { "Second call should succeed (cached)" }
            assertEquals("The answer to your question is four (2+2=4).", result2.content) { "Cached content should match" }

            // LLM이 한 번만 호출되었는지 확인
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
                temperature = 0.8 // 캐시 가능 임계값 초과
            )

            // 두 호출 모두 LLM에 도달해야 한다
            executor.execute(command)
            executor.execute(command)

            verify(exactly = 2) { fixture.requestSpec.call() }
        }

        @Test
        fun `different prompts은(는) not share cache해야 한다`() = runTest {
            val fixture = AgentTestFixture()
            every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec

            // 각 호출에 대해 다른 응답 반환
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

            // 첫 번째 호출은 빈 응답, 두 번째 호출은 유효한 응답 반환
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

            // First call — empty response → OUTPUT_TOO_SHORT 에러, 캐시 미저장
            val result1 = executor.execute(command)
            assertFalse(result1.success) { "빈 응답은 실패로 처리되어야 한다" }

            // 두 번째 호출 — 빈 응답이 캐시되지 않았으므로 LLM을 다시 호출해야 한다
            val result2 = executor.execute(command)
            assertEquals("Valid answer", result2.content) { "Second call should get valid response, not cached empty" }

            // LLM이 두 번 호출되었는지 확인 (빈 응답은 캐시되지 않음)
            verify(exactly = 2) { fixture.requestSpec.call() }
        }
    }

    @Nested
    inner class CacheDisabled {

        @Test
        fun `null cache은(는) skip caching entirely해야 한다`() = runTest {
            val fixture = AgentTestFixture()
            fixture.mockCallResponse("This response should not be cached at all.")
            every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = AgentTestFixture.defaultProperties(),
                responseCache = null // 캐시 비활성화
            )

            val command = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "Hello",
                mode = AgentMode.STANDARD,
                temperature = 0.0
            )

            executor.execute(command)
            executor.execute(command)

            // 두 호출 모두 LLM에 도달해야 한다
            verify(exactly = 2) { fixture.requestSpec.call() }
        }
    }
}
