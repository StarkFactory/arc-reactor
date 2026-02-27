package com.arc.reactor.promptlab

import com.arc.reactor.promptlab.model.EvaluationTier
import com.arc.reactor.promptlab.model.Experiment
import com.arc.reactor.promptlab.model.ExperimentReport
import com.arc.reactor.promptlab.model.QueryComparison
import com.arc.reactor.promptlab.model.QueryVersionResult
import com.arc.reactor.promptlab.model.Recommendation
import com.arc.reactor.promptlab.model.RecommendationConfidence
import com.arc.reactor.promptlab.model.TierStats
import com.arc.reactor.promptlab.model.Trial
import com.arc.reactor.promptlab.model.VersionSummary
import mu.KotlinLogging
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Generates comparison reports from experiment trial data.
 */
class ReportGenerator {

    fun generate(experiment: Experiment, trials: List<Trial>): ExperimentReport {
        val versionSummaries = buildVersionSummaries(trials, experiment)
        val queryComparisons = buildQueryComparisons(experiment, trials)
        val recommendation = deriveRecommendation(versionSummaries)

        logger.debug {
            "Generated report for experiment=${experiment.id}, " +
                "versions=${versionSummaries.size}, trials=${trials.size}"
        }

        return ExperimentReport(
            experimentId = experiment.id,
            experimentName = experiment.name,
            generatedAt = Instant.now(),
            totalTrials = trials.size,
            versionSummaries = versionSummaries,
            queryComparisons = queryComparisons,
            recommendation = recommendation
        )
    }

    private fun buildVersionSummaries(
        trials: List<Trial>,
        experiment: Experiment
    ): List<VersionSummary> {
        return trials.groupBy { it.promptVersionId }.map { (versionId, vTrials) ->
            buildSingleVersionSummary(
                versionId = versionId,
                trials = vTrials,
                isBaseline = versionId == experiment.baselineVersionId
            )
        }
    }

    private fun buildSingleVersionSummary(
        versionId: String,
        trials: List<Trial>,
        isBaseline: Boolean
    ): VersionSummary {
        val passCount = trials.count { t -> t.evaluations.all { it.passed } }
        val avgScore = computeAvgScore(trials)
        val errorCount = trials.count { !it.success }
        val tierBreakdown = buildTierBreakdown(trials)
        val toolFrequency = buildToolFrequency(trials)

        return VersionSummary(
            versionId = versionId,
            versionNumber = trials.first().promptVersionNumber,
            isBaseline = isBaseline,
            totalTrials = trials.size,
            passCount = passCount,
            passRate = if (trials.isNotEmpty()) passCount.toDouble() / trials.size else 0.0,
            avgScore = avgScore,
            avgDurationMs = trials.map { it.durationMs }.average(),
            totalTokens = trials.sumOf { it.tokenUsage?.totalTokens ?: 0 },
            tierBreakdown = tierBreakdown,
            toolUsageFrequency = toolFrequency,
            errorRate = if (trials.isNotEmpty()) errorCount.toDouble() / trials.size else 0.0
        )
    }

    private fun computeAvgScore(trials: List<Trial>): Double {
        val scores = trials.flatMap { t -> t.evaluations.map { it.score } }
        return if (scores.isNotEmpty()) scores.average() else 0.0
    }

    private fun buildTierBreakdown(
        trials: List<Trial>
    ): Map<EvaluationTier, TierStats> {
        return EvaluationTier.entries.associateWith { tier ->
            val tierResults = trials.flatMap { t ->
                t.evaluations.filter { it.tier == tier }
            }
            if (tierResults.isEmpty()) {
                TierStats(0, 0, 0.0, 0.0)
            } else {
                val passed = tierResults.count { it.passed }
                val failed = tierResults.size - passed
                TierStats(
                    passCount = passed,
                    failCount = failed,
                    passRate = passed.toDouble() / tierResults.size,
                    avgScore = tierResults.map { it.score }.average()
                )
            }
        }
    }

    private fun buildToolFrequency(trials: List<Trial>): Map<String, Int> {
        return trials.flatMap { it.toolsUsed }
            .groupingBy { it }
            .eachCount()
    }

    private fun buildQueryComparisons(
        experiment: Experiment,
        trials: List<Trial>
    ): List<QueryComparison> {
        return experiment.testQueries.map { query ->
            val matchingTrials = trials.filter { it.testQuery == query }
            val versionResults = matchingTrials.groupBy { it.promptVersionId }
                .map { (vId, vTrials) -> buildQueryVersionResult(vId, vTrials) }

            QueryComparison(query = query, versionResults = versionResults)
        }
    }

    private fun buildQueryVersionResult(
        versionId: String,
        trials: List<Trial>
    ): QueryVersionResult {
        val best = trials.maxByOrNull { t ->
            val scores = t.evaluations.map { it.score }
            if (scores.isEmpty()) 0.0 else scores.average()
        } ?: trials.first()

        val scores = best.evaluations.map { it.score }
        return QueryVersionResult(
            versionId = versionId,
            versionNumber = best.promptVersionNumber,
            response = best.response,
            passed = best.evaluations.all { it.passed },
            score = if (scores.isEmpty()) 0.0 else scores.average(),
            durationMs = best.durationMs,
            evaluationDetails = best.evaluations
        )
    }

    private fun deriveRecommendation(
        summaries: List<VersionSummary>
    ): Recommendation {
        val best = summaries.maxByOrNull { it.passRate * 0.6 + it.avgScore * 0.4 }
            ?: return fallbackRecommendation(summaries)

        val baseline = summaries.find { it.isBaseline }
        val confidence = computeConfidence(best, baseline)
        val improvements = identifyImprovements(best, baseline)
        val warnings = identifyWarnings(best, baseline)

        return Recommendation(
            bestVersionId = best.versionId,
            bestVersionNumber = best.versionNumber,
            confidence = confidence,
            reasoning = buildReasoning(best, baseline),
            improvements = improvements,
            warnings = warnings
        )
    }

    private fun computeConfidence(
        best: VersionSummary,
        baseline: VersionSummary?
    ): RecommendationConfidence {
        if (baseline == null) return RecommendationConfidence.LOW
        val delta = best.passRate - baseline.passRate
        return when {
            delta > 0.10 -> RecommendationConfidence.HIGH
            delta > 0.05 -> RecommendationConfidence.MEDIUM
            else -> RecommendationConfidence.LOW
        }
    }

    private fun identifyImprovements(
        best: VersionSummary,
        baseline: VersionSummary?
    ): List<String> {
        if (baseline == null || best.isBaseline) return emptyList()
        val improvements = mutableListOf<String>()
        if (best.passRate > baseline.passRate) {
            improvements.add("Pass rate improved: ${fmtPct(baseline.passRate)} -> ${fmtPct(best.passRate)}")
        }
        if (best.avgScore > baseline.avgScore) {
            improvements.add("Avg score improved: ${fmtScore(baseline.avgScore)} -> ${fmtScore(best.avgScore)}")
        }
        if (best.avgDurationMs < baseline.avgDurationMs) {
            improvements.add("Faster response: ${baseline.avgDurationMs.toLong()}ms -> ${best.avgDurationMs.toLong()}ms")
        }
        return improvements
    }

    private fun identifyWarnings(
        best: VersionSummary,
        baseline: VersionSummary?
    ): List<String> {
        if (baseline == null) return listOf("No baseline for comparison")
        val warnings = mutableListOf<String>()
        if (best.errorRate > baseline.errorRate) {
            warnings.add("Error rate increased: ${fmtPct(baseline.errorRate)} -> ${fmtPct(best.errorRate)}")
        }
        if (best.totalTokens > baseline.totalTokens * 1.5) {
            warnings.add("Token usage significantly higher: ${baseline.totalTokens} -> ${best.totalTokens}")
        }
        return warnings
    }

    private fun buildReasoning(
        best: VersionSummary,
        baseline: VersionSummary?
    ): String {
        if (baseline == null) return "Selected version ${best.versionNumber} (no baseline comparison)"
        if (best.isBaseline) return "Baseline version ${best.versionNumber} remains the best option"
        return "Version ${best.versionNumber} outperforms baseline: " +
            "pass rate ${fmtPct(best.passRate)} vs ${fmtPct(baseline.passRate)}, " +
            "avg score ${fmtScore(best.avgScore)} vs ${fmtScore(baseline.avgScore)}"
    }

    private fun fallbackRecommendation(
        summaries: List<VersionSummary>
    ): Recommendation {
        val first = summaries.firstOrNull()
        return Recommendation(
            bestVersionId = first?.versionId.orEmpty(),
            bestVersionNumber = first?.versionNumber ?: 0,
            confidence = RecommendationConfidence.LOW,
            reasoning = "Insufficient data for recommendation",
            warnings = listOf("No trial data available")
        )
    }

    private fun fmtPct(value: Double): String = "%.1f%%".format(value * 100)
    private fun fmtScore(value: Double): String = "%.3f".format(value)
}
