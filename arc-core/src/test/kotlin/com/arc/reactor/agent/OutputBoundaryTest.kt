package com.arc.reactor.agent

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 출력 경계(최소/최대 길이) 기능에 대한 테스트.
 *
 * WARN, RETRY_ONCE, FAIL 모드와 최대 길이 잘림 동작을 검증합니다.
 */
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
        fun `WARN 모드에서 짧은 응답이 통과해야 한다`() = runTest {
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
        fun `재시도 시 더 긴 응답으로 성공해야 한다`() = runTest {
            // 첫 번째 호출은 짧은 응답을 반환하고, 재시도는 더 긴 응답을 반환합니다
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
        fun `재시도해도 짧으면 WARN으로 폴백해야 한다`() = runTest {
            // 두 호출 모두 짧은 응답을 반환합니다
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

        @Test
        fun `재시도 프롬프트에 원래 요청과 이전 짧은 응답 컨텍스트가 포함되어야 한다`() = runTest {
            every { fixture.requestSpec.call() } returnsMany listOf(
                fixture.mockFinalResponse("Hi"),
                fixture.mockFinalResponse("This is a longer, contextualized retry response.")
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = propertiesWithBoundaries(
                    outputMinChars = 20, mode = OutputMinViolationMode.RETRY_ONCE
                )
            )

            val command = AgentCommand(systemPrompt = "Be helpful", userPrompt = "How do I reset my password?")
            val result = executor.execute(command)

            result.assertSuccess("Retry should succeed with contextualized prompt")
            verify {
                fixture.requestSpec.user(match<String> { prompt ->
                    prompt.contains("Original user request:") &&
                        prompt.contains("How do I reset my password?") &&
                        prompt.contains("Previous short response:") &&
                        prompt.contains("Hi")
                })
            }
        }
    }

    @Nested
    inner class OutputMinFail {

        @Test
        fun `짧은 응답이 OUTPUT_TOO_SHORT로 실패해야 한다`() = runTest {
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
        fun `긴 응답이 잘려야 한다`() = runTest {
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
        fun `기본 설정이 기존 동작에 영향을 주지 않아야 한다`() = runTest {
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
