package com.arc.reactor.response

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.response.impl.MaxLengthResponseFilter
import io.mockk.every
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Integration tests for ResponseFilter with SpringAiAgentExecutor.
 *
 * Verifies that filters are applied to execute() results.
 */
class ResponseFilterIntegrationTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class ExecuteWithFilter {

        @Test
        fun `should apply max length filter to execute result`() = runTest {
            fixture.mockCallResponse("A".repeat(200))

            val chain = ResponseFilterChain(listOf(MaxLengthResponseFilter(maxLength = 50)))
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                responseFilterChain = chain
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertTrue(result.success) { "Should succeed, got: ${result.errorMessage}" }
            val content = result.content
            assertNotNull(content) { "Content should not be null" }
            assertTrue(content!!.contains("[Response truncated]")) {
                "Response should be truncated, length: ${content.length}"
            }
            assertTrue(content.length < 200) {
                "Truncated content should be shorter than original"
            }
        }

        @Test
        fun `should not apply filter when content is within limit`() = runTest {
            fixture.mockCallResponse("Short response")

            val chain = ResponseFilterChain(listOf(MaxLengthResponseFilter(maxLength = 1000)))
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                responseFilterChain = chain
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertTrue(result.success) { "Should succeed" }
            assertEquals("Short response", result.content) {
                "Content within limit should be unchanged"
            }
        }

        @Test
        fun `should not apply filter on failure result`() = runTest {
            every { fixture.callResponseSpec.chatResponse() } throws RuntimeException("LLM down")

            val filterCalled = java.util.concurrent.atomic.AtomicBoolean(false)
            val spyFilter = object : ResponseFilter {
                override suspend fun filter(content: String, context: ResponseFilterContext): String {
                    filterCalled.set(true)
                    return content
                }
            }

            val chain = ResponseFilterChain(listOf(spyFilter))
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                responseFilterChain = chain
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertFalse(result.success) { "Should fail" }
            assertFalse(filterCalled.get()) { "Filter should not be called on failure" }
        }

        @Test
        fun `should work without filter chain (null)`() = runTest {
            fixture.mockCallResponse("No filter applied")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                responseFilterChain = null
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertTrue(result.success) { "Should succeed without filter chain" }
            assertEquals("No filter applied", result.content) {
                "Content should be unmodified without filter chain"
            }
        }

        @Test
        fun `should apply custom filter transforming content`() = runTest {
            fixture.mockCallResponse("hello world")

            val uppercaseFilter = object : ResponseFilter {
                override suspend fun filter(content: String, context: ResponseFilterContext) =
                    content.uppercase()
            }

            val chain = ResponseFilterChain(listOf(uppercaseFilter))
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                responseFilterChain = chain
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertTrue(result.success) { "Should succeed" }
            assertEquals("HELLO WORLD", result.content) {
                "Custom filter should transform content"
            }
        }

        @Test
        fun `should continue if filter throws exception`() = runTest {
            fixture.mockCallResponse("original content")

            val failingFilter = object : ResponseFilter {
                override suspend fun filter(content: String, context: ResponseFilterContext): String {
                    throw RuntimeException("Filter crashed")
                }
            }

            val chain = ResponseFilterChain(listOf(failingFilter))
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                responseFilterChain = chain
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertTrue(result.success) { "Should succeed even if filter fails" }
            assertEquals("original content", result.content) {
                "Should return original content when filter fails"
            }
        }
    }
}
