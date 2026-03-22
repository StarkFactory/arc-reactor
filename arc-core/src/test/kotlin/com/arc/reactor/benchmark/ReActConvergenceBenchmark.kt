package com.arc.reactor.benchmark

import com.arc.reactor.agent.metrics.MicrometerSlaMetrics
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import mu.KotlinLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

private val logger = KotlinLogging.logger {}

/**
 * ReAct 수렴 품질 벤치마크.
 *
 * 골든 데이터셋 기반으로 카테고리별 수렴 SLO를 정의하고, 시뮬레이션된 스텝 수에 대해
 * [MicrometerSlaMetrics.recordReActConvergence] 기록 및 SLO 준수율을 검증한다.
 * LLM API 키 없이 실행 가능한 오프라인 벤치마크.
 *
 * ## 카테고리별 수렴 SLO
 * - `standard_direct`: 도구 불필요 → 1 스텝 이하
 * - `security_block`: 즉시 차단 → 1 스텝 이하
 * - `jira_single`, `confluence_search`, `bitbucket`, `swagger`: 단일/소수 도구 → 3 스텝 이하
 * - `multi_tool`: 복합 도구 → 7 스텝 이하
 * - `edge_case`: 경계 입력 → 3 스텝 이하
 *
 * ## 실행 방법
 * ```bash
 * ./gradlew test --tests "*.ReActConvergenceBenchmark" -PincludeBenchmark
 * ```
 *
 * @see MicrometerSlaMetrics Micrometer 기반 SLA 메트릭 구현체
 */
@Tag("benchmark")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReActConvergenceBenchmark {

    private lateinit var registry: MeterRegistry
    private lateinit var slaMetrics: MicrometerSlaMetrics
    private val mapper = jacksonObjectMapper().apply {
        propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
        configure(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
        )
    }
    private val dataset: GoldenDataset = loadGoldenDataset()

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        slaMetrics = MicrometerSlaMetrics(registry)
    }

    // ────────────────────────────────────────────────────
    // 1. 수렴 SLO 검증 벤치마크
    // ────────────────────────────────────────────────────

    /**
     * 카테고리별 수렴 SLO를 시뮬레이션하고 준수율을 검증한다.
     *
     * 각 테스트 케이스에 대해 기대 도구 수 기반 시뮬레이션 스텝을 생성하고,
     * SLO 이내인지 판정한다.
     */
    @Test
    fun `카테고리별 수렴 SLO 준수율 벤치마크`() {
        val results = dataset.testCases.map { tc ->
            evaluateConvergence(tc)
        }
        printConvergenceReport(results)
        assertMinimumSloCompliance(results)
    }

    // ────────────────────────────────────────────────────
    // 2. SlaMetrics 기록 통합 검증
    // ────────────────────────────────────────────────────

    /**
     * [MicrometerSlaMetrics.recordReActConvergence]가 스텝 버킷과
     * 종료 사유를 올바르게 기록하는지 검증한다.
     */
    @Test
    fun `SlaMetrics 수렴 기록 통합 검증`() {
        val scenarios = listOf(
            ConvergenceScenario("standard_direct", 1, "completed"),
            ConvergenceScenario("jira_single", 2, "completed"),
            ConvergenceScenario("multi_tool", 5, "completed"),
            ConvergenceScenario("multi_tool", 12, "max_tool_calls"),
            ConvergenceScenario("edge_case", 1, "completed"),
            ConvergenceScenario("security_block", 1, "completed")
        )
        for (scenario in scenarios) {
            slaMetrics.recordReActConvergence(
                steps = scenario.steps,
                stopReason = scenario.stopReason,
                durationMs = scenario.steps * STEP_DURATION_MS,
                metadata = mapOf("category" to scenario.category)
            )
        }
        verifyMetricsRecorded(scenarios)
    }

    // ────────────────────────────────────────────────────
    // 3. SLO 위반 감지 검증
    // ────────────────────────────────────────────────────

    /**
     * 단순 질문이 과도한 스텝을 소비하는 경우를 SLO 위반으로 감지한다.
     */
    @Test
    fun `SLO 위반 시나리오 감지 검증`() {
        val violations = listOf(
            ConvergenceScenario("standard_direct", 3, "completed"),
            ConvergenceScenario("jira_single", 5, "completed"),
            ConvergenceScenario("security_block", 3, "max_tool_calls"),
            ConvergenceScenario("multi_tool", 10, "max_tool_calls"),
            ConvergenceScenario("edge_case", 6, "timeout")
        )
        val results = violations.map { scenario ->
            val slo = CATEGORY_SLO_MAP[scenario.category] ?: MAX_STEP_FALLBACK
            ConvergenceResult(
                testCaseId = "violation-${scenario.category}",
                category = scenario.category,
                simulatedSteps = scenario.steps,
                sloMaxSteps = slo,
                withinSlo = scenario.steps <= slo,
                stopReason = scenario.stopReason
            )
        }

        val violationCount = results.count { !it.withinSlo }
        violationCount shouldBe violations.size
        printViolationReport(results)
    }

    // ────────────────────────────────────────────────────
    // 4. 수렴 리포트 생성
    // ────────────────────────────────────────────────────

    /**
     * 전체 골든 데이터셋에 대해 수렴 리포트를 생성한다.
     *
     * 카테고리별 평균 스텝, 최대 스텝, SLO 준수율과
     * 전체 요약을 포함한다.
     */
    @Test
    fun `전체 수렴 리포트 생성`() {
        val results = dataset.testCases.map { tc ->
            evaluateConvergence(tc)
        }
        printFullReport(results)
    }

    // ── 수렴 평가 ──

    private fun evaluateConvergence(tc: TestCase): ConvergenceResult {
        val slo = CATEGORY_SLO_MAP[tc.category] ?: MAX_STEP_FALLBACK
        val steps = simulateStepCount(tc)
        val stopReason = determineStopReason(tc, steps, slo)

        slaMetrics.recordReActConvergence(
            steps = steps,
            stopReason = stopReason,
            durationMs = steps * STEP_DURATION_MS,
            metadata = mapOf("category" to tc.category)
        )

        return ConvergenceResult(
            testCaseId = tc.id,
            category = tc.category,
            simulatedSteps = steps,
            sloMaxSteps = slo,
            withinSlo = steps <= slo,
            stopReason = stopReason
        )
    }

    /** 테스트 케이스의 기대 도구 수를 기반으로 시뮬레이션 스텝 수를 결정한다. */
    private fun simulateStepCount(tc: TestCase): Int {
        if (tc.shouldBlock) return 1
        if (tc.expectedTools.isEmpty()) return 1
        return tc.expectedToolCount.coerceAtLeast(1)
    }

    /** 스텝 수와 SLO 기반으로 종료 사유를 결정한다. */
    private fun determineStopReason(
        tc: TestCase,
        steps: Int,
        slo: Int
    ): String = when {
        tc.shouldBlock -> "completed"
        steps > slo -> "max_tool_calls"
        else -> "completed"
    }

    // ── 메트릭 검증 ──

    private fun verifyMetricsRecorded(
        scenarios: List<ConvergenceScenario>
    ) {
        val completedCount = scenarios.count { it.stopReason == "completed" }
        verifyCounterByReason("completed", completedCount)

        val maxToolCallsCount = scenarios.count { it.stopReason == "max_tool_calls" }
        if (maxToolCallsCount > 0) {
            verifyCounterByReason("max_tool_calls", maxToolCallsCount)
        }

        verifyTimerTotalCount(scenarios.size)
    }

    private fun verifyCounterByReason(reason: String, expected: Int) {
        val counters = registry.find("arc.sla.react.convergence.total")
            .tag("stop_reason", reason)
            .counters()
        val total = counters.sumOf { it.count() }
        total shouldBe expected.toDouble()
    }

    private fun verifyTimerTotalCount(expectedTotal: Int) {
        val timers = registry.find("arc.sla.react.convergence")
            .timers()
        val totalCount = timers.sumOf { it.count() }
        totalCount shouldBe expectedTotal.toLong()
    }

    // ── 리포트 출력 ──

    private fun printConvergenceReport(results: List<ConvergenceResult>) {
        val total = results.size
        val passed = results.count { it.withinSlo }
        val rate = passed.toDouble() / total.coerceAtLeast(1)

        val report = buildString {
            appendLine()
            appendLine("=".repeat(REPORT_WIDTH))
            appendLine("  ReAct 수렴 벤치마크 — SLO 준수율 리포트")
            appendLine("=".repeat(REPORT_WIDTH))
            appendLine()
            appendFormattedLine("전체 SLO 준수율", rate, passed, total)
            appendLine()
            appendLine("-".repeat(REPORT_WIDTH))
            appendLine("  카테고리별 상세")
            appendLine("-".repeat(REPORT_WIDTH))
            appendCategoryStats(results)
            appendLine("=".repeat(REPORT_WIDTH))
        }
        logger.info { report }
        println(report)
    }

    private fun printViolationReport(results: List<ConvergenceResult>) {
        val report = buildString {
            appendLine()
            appendLine("-".repeat(REPORT_WIDTH))
            appendLine("  SLO 위반 시나리오 상세")
            appendLine("-".repeat(REPORT_WIDTH))
            for (r in results) {
                appendLine(
                    "  [${r.testCaseId}] " +
                        "steps=${r.simulatedSteps}, " +
                        "slo=${r.sloMaxSteps}, " +
                        "reason=${r.stopReason}"
                )
            }
            appendLine("-".repeat(REPORT_WIDTH))
        }
        logger.info { report }
        println(report)
    }

    private fun printFullReport(results: List<ConvergenceResult>) {
        val total = results.size
        val passed = results.count { it.withinSlo }
        val rate = passed.toDouble() / total.coerceAtLeast(1)

        val categoryStats = results.groupBy { it.category }
            .mapValues { (_, rs) -> computeCategoryStats(rs) }

        val worstCategory = categoryStats
            .minByOrNull { it.value.sloComplianceRate }

        val report = buildString {
            appendLine()
            appendLine("=".repeat(REPORT_WIDTH))
            appendLine("  ReAct 수렴 벤치마크 — 전체 리포트")
            appendLine("=".repeat(REPORT_WIDTH))
            appendLine()
            appendLine("  [요약]")
            appendFormattedLine("전체 쿼리 수", total)
            appendFormattedLine("SLO 통과", rate, passed, total)
            if (worstCategory != null) {
                appendLine(
                    "  %-32s %s (%.1f%%)".format(
                        "최저 준수 카테고리",
                        worstCategory.key,
                        worstCategory.value.sloComplianceRate
                            * PERCENT_MULTIPLIER
                    )
                )
            }
            appendLine()
            appendLine("-".repeat(REPORT_WIDTH))
            appendLine("  [카테고리별 상세]")
            appendLine(
                "  %-20s %6s %6s %8s %8s".format(
                    "카테고리", "평균", "최대", "SLO", "준수율"
                )
            )
            appendLine("-".repeat(REPORT_WIDTH))
            for ((category, stats) in categoryStats.toSortedMap()) {
                appendLine(
                    "  %-20s %6.1f %6d %8d %7.1f%%".format(
                        category,
                        stats.avgSteps,
                        stats.maxSteps,
                        stats.sloMaxSteps,
                        stats.sloComplianceRate * PERCENT_MULTIPLIER
                    )
                )
            }
            appendLine("=".repeat(REPORT_WIDTH))
        }
        logger.info { report }
        println(report)
    }

    // ── 카테고리 통계 ──

    private fun computeCategoryStats(
        results: List<ConvergenceResult>
    ): CategoryStats {
        val steps = results.map { it.simulatedSteps }
        val slo = results.first().sloMaxSteps
        val passed = results.count { it.withinSlo }
        return CategoryStats(
            avgSteps = steps.average(),
            maxSteps = steps.max(),
            sloMaxSteps = slo,
            sloComplianceRate = passed.toDouble() / results.size
        )
    }

    // ── 리포트 유틸리티 ──

    private fun StringBuilder.appendFormattedLine(
        label: String,
        rate: Double,
        count: Int,
        total: Int
    ) {
        appendLine(
            "  %-32s %6.1f%%  (%d/%d)".format(
                label, rate * PERCENT_MULTIPLIER, count, total
            )
        )
    }

    private fun StringBuilder.appendFormattedLine(
        label: String,
        value: Int
    ) {
        appendLine("  %-32s %6d".format(label, value))
    }

    private fun StringBuilder.appendCategoryStats(
        results: List<ConvergenceResult>
    ) {
        results.groupBy { it.category }.toSortedMap()
            .forEach { (category, categoryResults) ->
                val catTotal = categoryResults.size
                val catPassed = categoryResults.count { it.withinSlo }
                val catRate = catPassed.toDouble() / catTotal
                val avgSteps = categoryResults
                    .map { it.simulatedSteps }.average()
                appendLine(
                    "  %-20s %6.1f%%  (%d/%d)  avg=%.1f".format(
                        category,
                        catRate * PERCENT_MULTIPLIER,
                        catPassed, catTotal, avgSteps
                    )
                )
            }
    }

    // ── 최소 SLO 준수율 검증 ──

    private fun assertMinimumSloCompliance(
        results: List<ConvergenceResult>
    ) {
        val total = results.size
        val passed = results.count { it.withinSlo }
        val rate = passed.toDouble() / total.coerceAtLeast(1)
        assert(rate >= MIN_SLO_COMPLIANCE) {
            "SLO 준수율 %.1f%%가 최소 임계값 %.1f%%에 미달".format(
                rate * PERCENT_MULTIPLIER,
                MIN_SLO_COMPLIANCE * PERCENT_MULTIPLIER
            )
        }
    }

    // ── 데이터 로딩 ──

    private fun loadGoldenDataset(): GoldenDataset {
        val stream = javaClass.classLoader
            .getResourceAsStream("golden-dataset.json")
            ?: throw IllegalStateException(
                "golden-dataset.json을 찾을 수 없습니다"
            )
        return stream.use { mapper.readValue(it) }
    }

    // ── 데이터 모델 ──

    private data class GoldenDataset(
        val version: String,
        val description: String = "",
        val testCases: List<TestCase>
    )

    private data class TestCase(
        val id: String,
        val category: String,
        val query: String,
        val expectedMode: String,
        val expectedTools: List<String> = emptyList(),
        val expectedToolCount: Int = 0,
        val expectedResponsePattern: String? = null,
        val shouldBlock: Boolean = false,
        val description: String = ""
    )

    private data class ConvergenceResult(
        val testCaseId: String,
        val category: String,
        val simulatedSteps: Int,
        val sloMaxSteps: Int,
        val withinSlo: Boolean,
        val stopReason: String
    )

    private data class ConvergenceScenario(
        val category: String,
        val steps: Int,
        val stopReason: String
    )

    private data class CategoryStats(
        val avgSteps: Double,
        val maxSteps: Int,
        val sloMaxSteps: Int,
        val sloComplianceRate: Double
    )

    companion object {
        /** 리포트 출력 너비 (문자 수) */
        private const val REPORT_WIDTH = 64

        /** 퍼센트 변환 상수 */
        private const val PERCENT_MULTIPLIER = 100.0

        /** 시뮬레이션 스텝당 소요 시간 (밀리초) */
        private const val STEP_DURATION_MS = 500L

        /** SLO 기본 상한 (미정의 카테고리용) */
        private const val MAX_STEP_FALLBACK = 5

        /** 최소 SLO 준수율 임계값 (80%) */
        private const val MIN_SLO_COMPLIANCE = 0.80

        /** 카테고리별 수렴 SLO 상한 스텝 수 */
        private val CATEGORY_SLO_MAP = mapOf(
            "standard_direct" to 1,
            "security_block" to 1,
            "jira_single" to 3,
            "confluence_search" to 3,
            "bitbucket" to 3,
            "swagger" to 3,
            "multi_tool" to 7,
            "edge_case" to 3
        )
    }
}
