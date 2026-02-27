package com.arc.reactor.promptlab.eval

import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.promptlab.model.EvaluationConfig
import com.arc.reactor.promptlab.model.EvaluationTier
import com.arc.reactor.promptlab.model.TestQuery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec

class LlmJudgeEvaluatorTest {

    private val chatModelProvider: ChatModelProvider = mockk()
    private val chatClient: ChatClient = mockk()
    private val requestSpec: ChatClientRequestSpec = mockk(relaxed = true)
    private val callResponseSpec: CallResponseSpec = mockk()

    private lateinit var evaluator: LlmJudgeEvaluator

    private val defaultQuery = TestQuery(
        query = "What is AI?",
        intent = "search",
        expectedBehavior = "Explain AI"
    )

    @BeforeEach
    fun setup() {
        evaluator = LlmJudgeEvaluator(
            chatModelProvider = chatModelProvider,
            judgeModel = "openai",
            model = "gemini"
        )

        every { chatModelProvider.getChatClient("openai") } returns chatClient
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns callResponseSpec
    }

    @Nested
    inner class SuccessfulJudgment {

        @Test
        fun `should parse valid judgment response`() = runTest {
            every { callResponseSpec.content() } returns
                """{"pass": true, "score": 0.85, "reason": "Good response"}"""

            val result = evaluator.evaluate(
                "AI is artificial intelligence",
                defaultQuery,
                EvaluationConfig()
            )

            assertTrue(result.passed) { "Valid pass=true should result in passed" }
            assertEquals(0.85, result.score) { "Score should be 0.85" }
            assertEquals("Good response", result.reason) { "Reason should match" }
            assertEquals(EvaluationTier.LLM_JUDGE, result.tier) {
                "Tier should be LLM_JUDGE"
            }
        }

        @Test
        fun `should clamp score to valid range`() = runTest {
            every { callResponseSpec.content() } returns
                """{"pass": true, "score": 1.5, "reason": "Over max"}"""

            val result = evaluator.evaluate(
                "Response", defaultQuery, EvaluationConfig()
            )

            assertEquals(1.0, result.score) { "Score should be clamped to 1.0" }
        }

        @Test
        fun `should use custom rubric when provided`() = runTest {
            every { callResponseSpec.content() } returns
                """{"pass": true, "score": 0.9, "reason": "Custom rubric pass"}"""

            val config = EvaluationConfig(customRubric = "Custom rubric here")
            val result = evaluator.evaluate("Response", defaultQuery, config)

            assertTrue(result.passed) { "Should pass with custom rubric" }
        }
    }

    @Nested
    inner class BudgetTracking {

        @Test
        fun `should track token usage across calls`() = runTest {
            every { callResponseSpec.content() } returns
                """{"pass": true, "score": 0.8, "reason": "OK"}"""

            evaluator.evaluate("Response", defaultQuery, EvaluationConfig())
            val firstCount = evaluator.currentTokenUsage()

            assertTrue(firstCount > 0) { "Token counter should increase after call" }

            evaluator.evaluate("Response 2", defaultQuery, EvaluationConfig())
            val secondCount = evaluator.currentTokenUsage()

            assertTrue(secondCount > firstCount) {
                "Token counter should accumulate across calls"
            }
        }

        @Test
        fun `should return budget exhausted when limit reached`() = runTest {
            val config = EvaluationConfig(llmJudgeBudgetTokens = 0)

            val result = evaluator.evaluate("Response", defaultQuery, config)

            assertFalse(result.passed) { "Budget exhausted should not pass" }
            assertEquals(0.0, result.score) { "Score should be 0.0" }
            assertTrue(result.reason.contains("Budget exhausted")) {
                "Reason should mention budget exhaustion"
            }
        }

        @Test
        fun `should not call LLM when budget already exceeded`() = runTest {
            // Exhaust budget by making many calls first
            every { callResponseSpec.content() } returns
                """{"pass": true, "score": 0.8, "reason": "OK"}"""
            // Call until budget is exceeded
            val config = EvaluationConfig(llmJudgeBudgetTokens = 1)
            evaluator.evaluate("Response", defaultQuery, EvaluationConfig())

            val result = evaluator.evaluate("Response", defaultQuery, config)

            assertEquals(0.0, result.score) { "Should return budget exhausted score" }
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `should return fallback on LLM call failure`() = runTest {
            every { requestSpec.call() } throws RuntimeException("API error")

            val result = evaluator.evaluate(
                "Response", defaultQuery, EvaluationConfig()
            )

            assertFalse(result.passed) { "Error should result in not passed" }
            assertEquals(0.0, result.score) { "Score should be 0.0 on error" }
            assertTrue(result.reason.contains("API error")) {
                "Reason should contain error message"
            }
        }

        @Test
        fun `should return fallback on unparseable response`() = runTest {
            every { callResponseSpec.content() } returns "not valid json"

            val result = evaluator.evaluate(
                "Response", defaultQuery, EvaluationConfig()
            )

            assertFalse(result.passed) { "Unparseable response should not pass" }
        }

        @Test
        fun `should handle null content gracefully`() = runTest {
            every { callResponseSpec.content() } returns null

            val result = evaluator.evaluate(
                "Response", defaultQuery, EvaluationConfig()
            )

            assertFalse(result.passed) { "Null content should not pass" }
        }
    }

    @Nested
    inner class SameModelWarning {

        @Test
        fun `should not block when judge model equals target model`() = runTest {
            val sameModelEvaluator = LlmJudgeEvaluator(
                chatModelProvider = chatModelProvider,
                judgeModel = "openai",
                model = "openai"
            )

            every { callResponseSpec.content() } returns
                """{"pass": true, "score": 0.7, "reason": "Same model"}"""

            val result = sameModelEvaluator.evaluate(
                "Response", defaultQuery, EvaluationConfig()
            )

            assertTrue(result.passed) { "Same model should not block evaluation" }
        }
    }
}
