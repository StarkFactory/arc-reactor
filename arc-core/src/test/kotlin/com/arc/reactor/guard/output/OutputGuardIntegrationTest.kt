package com.arc.reactor.guard.output

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.guard.output.impl.PiiMaskingOutputGuard
import com.arc.reactor.guard.output.impl.OutputBlockPattern
import com.arc.reactor.guard.output.impl.PatternAction
import com.arc.reactor.guard.output.impl.RegexPatternOutputGuard
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 에 대한 통합 테스트. OutputGuard with SpringAiAgentExecutor.
 *
 * 다음을 검증합니다: output guard pipeline is applied to execute() results.
 */
class OutputGuardIntegrationTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class PiiMasking {

        @Test
        fun `PII in LLM response은(는) masked이다`() = runTest {
            fixture.mockCallResponse("고객 전화번호는 010-1234-5678 입니다.")

            val pipeline = OutputGuardPipeline(listOf(PiiMaskingOutputGuard()))
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                outputGuardPipeline = pipeline
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "고객 정보 알려줘")
            )

            assertTrue(result.success) { "Should succeed with masked content" }
            assertNotNull(result.content) { "Content should not be null" }
            assertFalse(result.content.orEmpty().contains("010-1234-5678")) {
                "Phone number should be masked in response"
            }
            assertTrue(result.content.orEmpty().contains("***-****-****")) {
                "Masked phone should appear in response"
            }
        }

        @Test
        fun `LLM response passes through unchanged를 정리한다`() = runTest {
            fixture.mockCallResponse("오늘 날씨가 좋습니다.")

            val pipeline = OutputGuardPipeline(listOf(PiiMaskingOutputGuard()))
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                outputGuardPipeline = pipeline
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "날씨")
            )

            assertTrue(result.success) { "Should succeed" }
            assertEquals("오늘 날씨가 좋습니다.", result.content) {
                "Clean content should pass through unchanged"
            }
        }
    }

    @Nested
    inner class PatternBlocking {

        @Test
        fun `pattern blocks response with OUTPUT_GUARD_REJECTED를 거부한다`() = runTest {
            fixture.mockCallResponse("This is for INTERNAL USE ONLY.")

            val pipeline = OutputGuardPipeline(
                listOf(
                    RegexPatternOutputGuard(
                        listOf(
                            OutputBlockPattern(
                                name = "InternalDoc",
                                pattern = "(?i)internal\\s+use\\s+only",
                                action = PatternAction.REJECT
                            )
                        )
                    )
                )
            )
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                outputGuardPipeline = pipeline
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Show doc")
            )

            assertFalse(result.success) { "Should fail due to output guard rejection" }
            assertEquals(AgentErrorCode.OUTPUT_GUARD_REJECTED, result.errorCode) {
                "Error code should be OUTPUT_GUARD_REJECTED"
            }
            assertNotNull(result.errorMessage) { "Error message should not be null" }
        }

        @Test
        fun `pattern redacts content and succeeds를 마스킹한다`() = runTest {
            fixture.mockCallResponse("API key: sk-abc123secret")

            val pipeline = OutputGuardPipeline(
                listOf(
                    RegexPatternOutputGuard(
                        listOf(
                            OutputBlockPattern(
                                name = "APIKey",
                                pattern = "sk-[a-zA-Z0-9]+",
                                action = PatternAction.MASK
                            )
                        )
                    )
                )
            )
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                outputGuardPipeline = pipeline
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Show key")
            )

            assertTrue(result.success) { "Should succeed with masked content" }
            assertNotNull(result.content) { "Content should not be null" }
            assertFalse(result.content.orEmpty().contains("sk-abc123secret")) {
                "API key should be masked"
            }
            assertTrue(result.content.orEmpty().contains("[REDACTED]")) {
                "Should contain [REDACTED] placeholder"
            }
        }
    }

    @Nested
    inner class WithoutOutputGuard {

        @Test
        fun `null인 pipeline means no output guard`() = runTest {
            fixture.mockCallResponse("Response with PII: 010-1234-5678")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                outputGuardPipeline = null
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertTrue(result.success) { "Should succeed without output guard" }
            assertTrue(result.content!!.contains("010-1234-5678")) {
                "PII should remain when no output guard"
            }
        }

        @Test
        fun `output은(는) guard not applied on failure result`() = runTest {
            val guardCalled = java.util.concurrent.atomic.AtomicBoolean(false)
            val spyStage = object : OutputGuardStage {
                override val stageName = "Spy"
                override val order = 10
                override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                    guardCalled.set(true)
                    return OutputGuardResult.Allowed.DEFAULT
                }
            }

            val pipeline = OutputGuardPipeline(listOf(spyStage))
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                outputGuardPipeline = pipeline
            )

            io.mockk.every { fixture.callResponseSpec.chatResponse() } throws RuntimeException("LLM down")

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertFalse(result.success) { "Should fail due to LLM error" }
            assertFalse(guardCalled.get()) { "Output guard should not be called on failure" }
        }
    }

    @Nested
    inner class FailCloseBehavior {

        @Test
        fun `stage은(는) exception causes OUTPUT_GUARD_REJECTED (fail-close)`() = runTest {
            fixture.mockCallResponse("Normal response")

            val crashingStage = object : OutputGuardStage {
                override val stageName = "Crasher"
                override val order = 10
                override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                    throw RuntimeException("Stage crashed")
                }
            }

            val pipeline = OutputGuardPipeline(listOf(crashingStage))
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                outputGuardPipeline = pipeline
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "Test", userPrompt = "Hello")
            )

            assertFalse(result.success) { "Should fail due to output guard crash (fail-close)" }
            assertEquals(AgentErrorCode.OUTPUT_GUARD_REJECTED, result.errorCode) {
                "Error code should be OUTPUT_GUARD_REJECTED"
            }
        }
    }
}
