package com.arc.reactor.resilience

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.resilience.impl.NoOpFallbackStrategy
import io.mockk.every
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.prompt.ChatOptions
import kotlin.coroutines.cancellation.CancellationException

class ExecutorFallbackIntegrationTest {

    @Nested
    inner class FallbackOnFailure {

        @Test
        fun `fallback triggers when LLM call fails`() = runTest {
            val fixture = AgentTestFixture()
            every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec
            // Primary call fails
            every { fixture.callResponseSpec.chatResponse() } throws RuntimeException("LLM unavailable")

            val fallback = FixedFallbackStrategy(
                AgentResult.success(content = "Fallback answer")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = AgentTestFixture.defaultProperties(),
                fallbackStrategy = fallback
            )

            val command = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "Hello",
                mode = AgentMode.STANDARD,
                temperature = 0.0
            )

            val result = executor.execute(command)

            assertTrue(result.success) { "Should succeed via fallback" }
            assertEquals("Fallback answer", result.content) { "Content should come from fallback" }
        }

        @Test
        fun `fallback not triggered on success`() = runTest {
            val fixture = AgentTestFixture()
            fixture.mockCallResponse("Primary response")
            every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec

            val fallback = FixedFallbackStrategy(
                AgentResult.success(content = "Fallback answer")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = AgentTestFixture.defaultProperties(),
                fallbackStrategy = fallback
            )

            val command = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "Hello",
                mode = AgentMode.STANDARD,
                temperature = 0.0
            )

            val result = executor.execute(command)

            assertTrue(result.success) { "Should succeed with primary" }
            assertEquals("Primary response", result.content) { "Content should come from primary" }
            assertFalse(fallback.called) { "Fallback should not be invoked on success" }
        }

        @Test
        fun `fallback returns null falls through to original error`() = runTest {
            val fixture = AgentTestFixture()
            every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec
            every { fixture.callResponseSpec.chatResponse() } throws RuntimeException("LLM unavailable")

            val fallback = NoOpFallbackStrategy()

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = AgentTestFixture.defaultProperties(),
                fallbackStrategy = fallback
            )

            val command = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "Hello",
                mode = AgentMode.STANDARD,
                temperature = 0.0
            )

            val result = executor.execute(command)

            assertFalse(result.success) { "Should fail when fallback returns null" }
            assertNotNull(result.errorMessage) { "Should have error message" }
        }

        @Test
        fun `fallback exception does not mask original error`() = runTest {
            val fixture = AgentTestFixture()
            every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec
            every { fixture.callResponseSpec.chatResponse() } throws RuntimeException("LLM down")

            val fallback = ExplodingFallbackStrategy()

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = AgentTestFixture.defaultProperties(),
                fallbackStrategy = fallback
            )

            val command = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "Hello",
                mode = AgentMode.STANDARD,
                temperature = 0.0
            )

            val result = executor.execute(command)

            assertFalse(result.success) { "Should fail when fallback also throws" }
            assertNotNull(result.errorMessage) { "Should have original error message" }
        }
    }

    @Nested
    inner class NullFallback {

        @Test
        fun `null fallback strategy skips fallback entirely`() = runTest {
            val fixture = AgentTestFixture()
            every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec
            every { fixture.callResponseSpec.chatResponse() } throws RuntimeException("LLM down")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = AgentTestFixture.defaultProperties(),
                fallbackStrategy = null
            )

            val command = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "Hello",
                mode = AgentMode.STANDARD,
                temperature = 0.0
            )

            val result = executor.execute(command)

            assertFalse(result.success) { "Should fail without fallback" }
        }
    }
}

/**
 * Test helper: returns a fixed result.
 */
private class FixedFallbackStrategy(private val result: AgentResult?) : FallbackStrategy {
    var called = false
        private set

    override suspend fun execute(command: AgentCommand, originalError: Exception): AgentResult? {
        called = true
        return result
    }
}

/**
 * Test helper: always throws.
 */
private class ExplodingFallbackStrategy : FallbackStrategy {
    override suspend fun execute(command: AgentCommand, originalError: Exception): AgentResult? {
        throw RuntimeException("Fallback also failed")
    }
}
