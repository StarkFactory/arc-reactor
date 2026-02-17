package com.arc.reactor.agent

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import io.mockk.every
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OutputBoundaryTest {

    private lateinit var fixture: AgentTestFixture

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    private fun propertiesWithBoundaries(
        outputMinChars: Int = 0,
        outputMaxChars: Int = 0,
        mode: OutputMinViolationMode = OutputMinViolationMode.WARN
    ): AgentProperties = AgentTestFixture.defaultProperties().copy(
        boundaries = BoundaryProperties(
            outputMinChars = outputMinChars,
            outputMaxChars = outputMaxChars,
            outputMinViolationMode = mode
        )
    )

    @Nested
    inner class OutputMinWarn {

        @Test
        fun `short response passes through in WARN mode`() = runTest {
            fixture.mockCallResponse("Hi")
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = propertiesWithBoundaries(
                    outputMinChars = 50, mode = OutputMinViolationMode.WARN
                )
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Be helpful", userPrompt = "Hello")
            )

            result.assertSuccess("WARN mode should pass through short response")
            assertEquals("Hi", result.content) {
                "Content should be unchanged in WARN mode"
            }
        }
    }

    @Nested
    inner class OutputMinRetryOnce {

        @Test
        fun `retry succeeds with longer response`() = runTest {
            // First call returns short response, retry returns longer
            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockFinalResponse("Hi"),
                fixture.mockFinalResponse("Hello! I'm happy to help you with anything you need today.")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = propertiesWithBoundaries(
                    outputMinChars = 20, mode = OutputMinViolationMode.RETRY_ONCE
                )
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Be helpful", userPrompt = "Hello")
            )

            result.assertSuccess("RETRY_ONCE should succeed when retry produces longer response")
            assertNotNull(result.content) { "Content should not be null" }
            assertTrue(result.content.orEmpty().length >= 20) {
                "Content should meet min length after retry, got: ${result.content.orEmpty().length}"
            }
        }

        @Test
        fun `retry still short falls back to WARN`() = runTest {
            // Both calls return short responses
            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockFinalResponse("Hi"),
                fixture.mockFinalResponse("Hey")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = propertiesWithBoundaries(
                    outputMinChars = 50, mode = OutputMinViolationMode.RETRY_ONCE
                )
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Be helpful", userPrompt = "Hello")
            )

            result.assertSuccess("RETRY_ONCE should fall back to WARN when retry is still short")
        }
    }

    @Nested
    inner class OutputMinFail {

        @Test
        fun `short response fails with OUTPUT_TOO_SHORT`() = runTest {
            fixture.mockCallResponse("Hi")
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = propertiesWithBoundaries(
                    outputMinChars = 50, mode = OutputMinViolationMode.FAIL
                )
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Be helpful", userPrompt = "Hello")
            )

            result.assertErrorCode(AgentErrorCode.OUTPUT_TOO_SHORT)
        }
    }

    @Nested
    inner class OutputMaxTruncate {

        @Test
        fun `long response is truncated`() = runTest {
            fixture.mockCallResponse("a".repeat(200))
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = propertiesWithBoundaries(outputMaxChars = 50)
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Be helpful", userPrompt = "Hello")
            )

            result.assertSuccess("Truncated response should still be success")
            assertNotNull(result.content) { "Content should not be null" }
            assertTrue(result.content.orEmpty().length <= 50 + 30) {
                "Content should be truncated to ~50 chars plus notice, got: ${result.content.orEmpty().length}"
            }
        }
    }

    @Nested
    inner class DefaultConfig {

        @Test
        fun `default config does not affect existing behavior`() = runTest {
            fixture.mockCallResponse("Short")
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = AgentTestFixture.defaultProperties()
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Be helpful", userPrompt = "Hello")
            )

            result.assertSuccess("Default boundaries (all 0) should not affect results")
            assertEquals("Short", result.content) {
                "Content should be unchanged with default config"
            }
        }
    }
}
