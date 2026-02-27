package com.arc.reactor.promptlab

import com.arc.reactor.promptlab.model.EvaluationResult
import com.arc.reactor.promptlab.model.EvaluationTier
import com.arc.reactor.promptlab.model.Experiment
import com.arc.reactor.promptlab.model.RecommendationConfidence
import com.arc.reactor.promptlab.model.TestQuery
import com.arc.reactor.promptlab.model.TokenUsageSummary
import com.arc.reactor.promptlab.model.Trial
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class ReportGeneratorTest {

    private lateinit var generator: ReportGenerator

    @BeforeEach
    fun setUp() {
        generator = ReportGenerator()
    }

    @Nested
    inner class BasicReport {

        @Test
        fun `should generate report with correct experiment info`() {
            val experiment = buildExperiment()
            val trials = listOf(
                buildTrial(experiment.id, "baseline-v", 1),
                buildTrial(experiment.id, "candidate-v", 2)
            )

            val report = generator.generate(experiment, trials)

            assertEquals(experiment.id, report.experimentId) { "Experiment ID should match" }
            assertEquals(experiment.name, report.experimentName) { "Name should match" }
            assertEquals(trials.size, report.totalTrials) { "Total trials should match" }
        }

        @Test
        fun `should include version summaries for all versions`() {
            val experiment = buildExperiment()
            val trials = listOf(
                buildTrial(experiment.id, "baseline-v", 1),
                buildTrial(experiment.id, "candidate-v", 2)
            )

            val report = generator.generate(experiment, trials)

            assertEquals(2, report.versionSummaries.size) { "Should have 2 version summaries" }
            val baseline = report.versionSummaries.find { it.isBaseline }
            assertNotNull(baseline) { "Should have a baseline summary" }
            assertEquals("baseline-v", baseline!!.versionId) { "Baseline ID should match" }
        }
    }

    @Nested
    inner class VersionSummaryCalculations {

        @Test
        fun `should calculate pass rate correctly`() {
            val experiment = buildExperiment()
            val trials = listOf(
                buildTrial(experiment.id, "baseline-v", 1, passed = true),
                buildTrial(experiment.id, "baseline-v", 1, passed = true),
                buildTrial(experiment.id, "baseline-v", 1, passed = false),
                buildTrial(experiment.id, "candidate-v", 2, passed = true),
                buildTrial(experiment.id, "candidate-v", 2, passed = true),
                buildTrial(experiment.id, "candidate-v", 2, passed = true)
            )

            val report = generator.generate(experiment, trials)

            val baselineSummary = report.versionSummaries.find { it.isBaseline }!!
            assertEquals(2.0 / 3.0, baselineSummary.passRate, 0.01) { "Baseline pass rate should be 2/3" }

            val candidateSummary = report.versionSummaries.find { !it.isBaseline }!!
            assertEquals(1.0, candidateSummary.passRate, 0.01) { "Candidate pass rate should be 1.0" }
        }

        @Test
        fun `should calculate token usage totals`() {
            val experiment = buildExperiment()
            val trials = listOf(
                buildTrial(experiment.id, "baseline-v", 1, tokens = TokenUsageSummary(100, 50)),
                buildTrial(experiment.id, "baseline-v", 1, tokens = TokenUsageSummary(200, 100)),
                buildTrial(experiment.id, "candidate-v", 2, tokens = TokenUsageSummary(80, 40))
            )

            val report = generator.generate(experiment, trials)

            val baseline = report.versionSummaries.find { it.isBaseline }!!
            assertEquals(450, baseline.totalTokens) { "Baseline total tokens: (100+50)+(200+100)" }

            val candidate = report.versionSummaries.find { !it.isBaseline }!!
            assertEquals(120, candidate.totalTokens) { "Candidate total tokens: 80+40" }
        }

        @Test
        fun `should calculate tier breakdown`() {
            val experiment = buildExperiment()
            val trials = listOf(
                buildTrial(
                    experiment.id, "baseline-v", 1, passed = true,
                    evaluations = listOf(
                        EvaluationResult(EvaluationTier.STRUCTURAL, true, 1.0, "OK"),
                        EvaluationResult(EvaluationTier.RULES, true, 0.8, "OK"),
                        EvaluationResult(EvaluationTier.LLM_JUDGE, false, 0.4, "Needs work")
                    )
                )
            )

            val report = generator.generate(experiment, trials)

            val baseline = report.versionSummaries.find { it.isBaseline }!!
            val structuralStats = baseline.tierBreakdown[EvaluationTier.STRUCTURAL]!!
            assertEquals(1, structuralStats.passCount) { "Structural pass count" }
            assertEquals(0, structuralStats.failCount) { "Structural fail count" }

            val llmStats = baseline.tierBreakdown[EvaluationTier.LLM_JUDGE]!!
            assertEquals(0, llmStats.passCount) { "LLM judge pass count" }
            assertEquals(1, llmStats.failCount) { "LLM judge fail count" }
        }

        @Test
        fun `should track tool usage frequency`() {
            val experiment = buildExperiment()
            val trials = listOf(
                buildTrial(experiment.id, "baseline-v", 1, tools = listOf("search", "calculate")),
                buildTrial(experiment.id, "baseline-v", 1, tools = listOf("search", "search"))
            )

            val report = generator.generate(experiment, trials)

            val baseline = report.versionSummaries.find { it.isBaseline }!!
            assertEquals(3, baseline.toolUsageFrequency["search"]) { "search should appear 3 times" }
            assertEquals(1, baseline.toolUsageFrequency["calculate"]) { "calculate should appear 1 time" }
        }
    }

    @Nested
    inner class QueryComparisons {

        @Test
        fun `should create per-query comparison across versions`() {
            val query = TestQuery(query = "What is AI?")
            val experiment = buildExperiment(queries = listOf(query))
            val trials = listOf(
                buildTrial(experiment.id, "baseline-v", 1, query = query, response = "AI is..."),
                buildTrial(experiment.id, "candidate-v", 2, query = query, response = "Artificial Intelligence is...")
            )

            val report = generator.generate(experiment, trials)

            assertEquals(1, report.queryComparisons.size) { "Should have 1 query comparison" }
            val comparison = report.queryComparisons.first()
            assertEquals(query, comparison.query) { "Query should match" }
            assertEquals(2, comparison.versionResults.size) { "Should have 2 version results" }
        }
    }

    @Nested
    inner class Recommendations {

        @Test
        fun `should recommend candidate when significantly better`() {
            val experiment = buildExperiment()
            val trials = listOf(
                buildTrial(experiment.id, "baseline-v", 1, passed = false, score = 0.3),
                buildTrial(experiment.id, "baseline-v", 1, passed = false, score = 0.4),
                buildTrial(experiment.id, "candidate-v", 2, passed = true, score = 0.9),
                buildTrial(experiment.id, "candidate-v", 2, passed = true, score = 0.95)
            )

            val report = generator.generate(experiment, trials)

            assertEquals("candidate-v", report.recommendation.bestVersionId) { "Should recommend candidate" }
            assertEquals(RecommendationConfidence.HIGH, report.recommendation.confidence) {
                "Confidence should be HIGH for >10% gap"
            }
        }

        @Test
        fun `should recommend baseline when candidates are worse`() {
            val experiment = buildExperiment()
            val trials = listOf(
                buildTrial(experiment.id, "baseline-v", 1, passed = true, score = 0.9),
                buildTrial(experiment.id, "candidate-v", 2, passed = false, score = 0.3)
            )

            val report = generator.generate(experiment, trials)

            assertEquals("baseline-v", report.recommendation.bestVersionId) { "Should recommend baseline" }
        }

        @Test
        fun `should have LOW confidence for close results`() {
            val experiment = buildExperiment()
            val trials = listOf(
                buildTrial(experiment.id, "baseline-v", 1, passed = true, score = 0.85),
                buildTrial(experiment.id, "candidate-v", 2, passed = true, score = 0.87)
            )

            val report = generator.generate(experiment, trials)

            assertEquals(RecommendationConfidence.LOW, report.recommendation.confidence) {
                "Confidence should be LOW for <5% gap"
            }
        }

        @Test
        fun `should include improvements list when candidate is better`() {
            val experiment = buildExperiment()
            val trials = listOf(
                buildTrial(experiment.id, "baseline-v", 1, passed = false, score = 0.3, durationMs = 500),
                buildTrial(experiment.id, "candidate-v", 2, passed = true, score = 0.9, durationMs = 200)
            )

            val report = generator.generate(experiment, trials)

            assertTrue(report.recommendation.improvements.isNotEmpty()) {
                "Should have improvements listed"
            }
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `should handle empty trials`() {
            val experiment = buildExperiment()
            val report = generator.generate(experiment, emptyList())

            assertEquals(0, report.totalTrials) { "Total trials should be 0" }
            assertTrue(report.versionSummaries.isEmpty()) { "Version summaries should be empty" }
            assertEquals(RecommendationConfidence.LOW, report.recommendation.confidence) {
                "Confidence should be LOW with no data"
            }
        }

        @Test
        fun `should handle single version`() {
            val experiment = buildExperiment(candidateIds = emptyList())
            val trials = listOf(
                buildTrial(experiment.id, "baseline-v", 1, passed = true)
            )

            val report = generator.generate(experiment, trials)

            assertEquals(1, report.versionSummaries.size) { "Should have 1 version summary" }
            assertNotNull(report.recommendation) { "Should still produce recommendation" }
        }
    }

    // ── Helpers ──

    private fun buildTrials(experiment: Experiment): List<Trial> {
        return listOf(
            buildTrial(experiment.id, "baseline-v", 1, passed = true),
            buildTrial(experiment.id, "baseline-v", 1, passed = false),
            buildTrial(experiment.id, "candidate-v", 2, passed = true),
            buildTrial(experiment.id, "candidate-v", 2, passed = true)
        )
    }

    private fun buildExperiment(
        queries: List<TestQuery> = listOf(TestQuery(query = "test query")),
        candidateIds: List<String> = listOf("candidate-v")
    ): Experiment {
        return Experiment(
            id = "exp-1",
            name = "Test Experiment",
            templateId = "tmpl-1",
            baselineVersionId = "baseline-v",
            candidateVersionIds = candidateIds,
            testQueries = queries,
            createdAt = Instant.now()
        )
    }

    private fun buildTrial(
        experimentId: String,
        versionId: String,
        versionNumber: Int,
        passed: Boolean = true,
        score: Double = if (passed) 1.0 else 0.3,
        query: TestQuery = TestQuery(query = "test query"),
        response: String? = "Test response",
        tools: List<String> = emptyList(),
        tokens: TokenUsageSummary? = null,
        durationMs: Long = 100,
        evaluations: List<EvaluationResult>? = null
    ): Trial {
        val evals = evaluations ?: listOf(
            EvaluationResult(
                tier = EvaluationTier.STRUCTURAL,
                passed = passed,
                score = score,
                reason = if (passed) "Passed" else "Failed"
            )
        )
        return Trial(
            experimentId = experimentId,
            promptVersionId = versionId,
            promptVersionNumber = versionNumber,
            testQuery = query,
            response = response,
            success = true,
            toolsUsed = tools,
            tokenUsage = tokens,
            durationMs = durationMs,
            evaluations = evals,
            executedAt = Instant.now()
        )
    }
}
