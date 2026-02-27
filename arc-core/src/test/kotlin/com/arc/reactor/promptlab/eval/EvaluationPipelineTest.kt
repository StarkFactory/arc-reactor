package com.arc.reactor.promptlab.eval

import com.arc.reactor.promptlab.model.EvaluationConfig
import com.arc.reactor.promptlab.model.EvaluationResult
import com.arc.reactor.promptlab.model.EvaluationTier
import com.arc.reactor.promptlab.model.TestQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EvaluationPipelineTest {

    private val structural: StructuralEvaluator = mockk()
    private val rules: RuleBasedEvaluator = mockk()
    private val llmJudge: LlmJudgeEvaluator = mockk()

    private val defaultQuery = TestQuery(query = "What is AI?")
    private val defaultConfig = EvaluationConfig()

    private fun passResult(tier: EvaluationTier) = EvaluationResult(
        tier = tier, passed = true, score = 1.0, reason = "Passed"
    )

    private fun failResult(tier: EvaluationTier) = EvaluationResult(
        tier = tier, passed = false, score = 0.3, reason = "Failed"
    )

    @BeforeEach
    fun setup() {
        coEvery { structural.evaluate(any(), any()) } returns
            passResult(EvaluationTier.STRUCTURAL)
        coEvery { rules.evaluate(any(), any()) } returns
            passResult(EvaluationTier.RULES)
        coEvery { llmJudge.evaluate(any(), any(), any()) } returns
            passResult(EvaluationTier.LLM_JUDGE)
    }

    @Nested
    inner class AllTiersPass {

        @Test
        fun `should return results from all 3 tiers when all pass`() = runTest {
            val pipeline = EvaluationPipeline(
                structural, rules, llmJudge, defaultConfig
            )

            val results = pipeline.evaluate("response", defaultQuery)

            assertEquals(3, results.size) { "Should have 3 tier results" }
            assertEquals(EvaluationTier.STRUCTURAL, results[0].tier) {
                "First should be STRUCTURAL"
            }
            assertEquals(EvaluationTier.RULES, results[1].tier) {
                "Second should be RULES"
            }
            assertEquals(EvaluationTier.LLM_JUDGE, results[2].tier) {
                "Third should be LLM_JUDGE"
            }
            assertTrue(results.all { it.passed }) {
                "All tiers should pass"
            }
        }
    }

    @Nested
    inner class FailFast {

        @Test
        fun `should skip rules and LLM when structural fails`() = runTest {
            coEvery { structural.evaluate(any(), any()) } returns
                failResult(EvaluationTier.STRUCTURAL)

            val pipeline = EvaluationPipeline(
                structural, rules, llmJudge, defaultConfig
            )
            val results = pipeline.evaluate("response", defaultQuery)

            assertEquals(1, results.size) { "Should only have structural result" }
            assertFalse(results[0].passed) { "Structural should have failed" }
            coVerify(exactly = 0) { rules.evaluate(any(), any()) }
            coVerify(exactly = 0) { llmJudge.evaluate(any(), any(), any()) }
        }

        @Test
        fun `should skip LLM when rules fail`() = runTest {
            coEvery { rules.evaluate(any(), any()) } returns
                failResult(EvaluationTier.RULES)

            val pipeline = EvaluationPipeline(
                structural, rules, llmJudge, defaultConfig
            )
            val results = pipeline.evaluate("response", defaultQuery)

            assertEquals(2, results.size) { "Should have structural and rules" }
            assertTrue(results[0].passed) { "Structural should pass" }
            assertFalse(results[1].passed) { "Rules should fail" }
            coVerify(exactly = 0) { llmJudge.evaluate(any(), any(), any()) }
        }
    }

    @Nested
    inner class TierDisabling {

        @Test
        fun `should skip structural when disabled`() = runTest {
            val config = EvaluationConfig(structuralEnabled = false)
            val pipeline = EvaluationPipeline(
                structural, rules, llmJudge, config
            )

            val results = pipeline.evaluate("response", defaultQuery)

            assertEquals(2, results.size) { "Should have rules and LLM only" }
            assertEquals(EvaluationTier.RULES, results[0].tier) {
                "First should be RULES"
            }
            coVerify(exactly = 0) { structural.evaluate(any(), any()) }
        }

        @Test
        fun `should skip rules when disabled`() = runTest {
            val config = EvaluationConfig(rulesEnabled = false)
            val pipeline = EvaluationPipeline(
                structural, rules, llmJudge, config
            )

            val results = pipeline.evaluate("response", defaultQuery)

            assertEquals(2, results.size) { "Should have structural and LLM" }
            coVerify(exactly = 0) { rules.evaluate(any(), any()) }
        }

        @Test
        fun `should skip LLM judge when disabled`() = runTest {
            val config = EvaluationConfig(llmJudgeEnabled = false)
            val pipeline = EvaluationPipeline(
                structural, rules, llmJudge, config
            )

            val results = pipeline.evaluate("response", defaultQuery)

            assertEquals(2, results.size) { "Should have structural and rules" }
            coVerify(exactly = 0) { llmJudge.evaluate(any(), any(), any()) }
        }

        @Test
        fun `should handle null LLM judge`() = runTest {
            val pipeline = EvaluationPipeline(
                structural, rules, null, defaultConfig
            )

            val results = pipeline.evaluate("response", defaultQuery)

            assertEquals(2, results.size) { "Should have structural and rules only" }
        }

        @Test
        fun `should return empty when all tiers disabled`() = runTest {
            val config = EvaluationConfig(
                structuralEnabled = false,
                rulesEnabled = false,
                llmJudgeEnabled = false
            )
            val pipeline = EvaluationPipeline(
                structural, rules, llmJudge, config
            )

            val results = pipeline.evaluate("response", defaultQuery)

            assertTrue(results.isEmpty()) { "Should return empty results" }
        }
    }

    @Nested
    inner class Factory {

        @Test
        fun `should create pipeline with config`() = runTest {
            val factory = EvaluationPipelineFactory(
                structural, rules, llmJudge
            )
            val config = EvaluationConfig(llmJudgeEnabled = false)

            val pipeline = factory.create(config)
            val results = pipeline.evaluate("response", defaultQuery)

            assertEquals(2, results.size) { "Factory-created pipeline should work" }
            coVerify(exactly = 0) { llmJudge.evaluate(any(), any(), any()) }
        }
    }
}
