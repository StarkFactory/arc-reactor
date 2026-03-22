package com.arc.reactor.benchmark

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.routing.DefaultAgentModeResolver
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.test.runTest
import mu.KotlinLogging
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

private val logger = KotlinLogging.logger {}

/**
 * 도구 선택 정확도 벤치마크.
 *
 * 골든 데이터셋 기반으로 모드 해석 정확도와 데이터셋 무결성을 자동 검증하고,
 * 정확도 리포트를 출력한다. LLM API 키 없이 실행 가능한 오프라인 벤치마크.
 *
 * ## 측정 메트릭
 * - `mode_accuracy`: 모드 해석 정확도 (전체/카테고리별)
 * - `correct_tool_rate`: 기대 도구와 정확히 일치하는 비율
 * - `wrong_tool_rate`: 존재하지만 잘못된 도구가 선택된 비율
 * - `hallucinated_tool_rate`: 존재하지 않는 도구가 선택된 비율
 *
 * ## 실행 방법
 * ```bash
 * ./gradlew test --tests "*.ToolSelectionBenchmark" -PincludeBenchmark
 * ```
 *
 * @see DefaultAgentModeResolver 휴리스틱 기반 모드 선택기
 */
@Tag("benchmark")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolSelectionBenchmark {

    private val resolver = DefaultAgentModeResolver()
    private val mapper = jacksonObjectMapper().apply {
        propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
        configure(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
        )
    }
    private val dataset: GoldenDataset = loadGoldenDataset()
    private val sampleTools = extractAllToolNames()

    // ────────────────────────────────────────────────────
    // 1. 모드 해석 벤치마크 (LLM 불필요)
    // ────────────────────────────────────────────────────

    /**
     * 골든 데이터셋의 모든 테스트 케이스에 대해 모드 해석 정확도를 측정한다.
     *
     * 전체 정확도와 카테고리별 정확도를 리포트로 출력한다.
     */
    @Test
    fun `모드 해석 정확도 벤치마크`() = runTest {
        val results = dataset.testCases.map { tc ->
            evaluateModeResolution(tc)
        }
        printModeAccuracyReport(results)
        assertMinimumAccuracy(results)
    }

    // ────────────────────────────────────────────────────
    // 2. 데이터셋 무결성 검증
    // ────────────────────────────────────────────────────

    /**
     * 골든 데이터셋의 구조적 무결성을 검증한다.
     *
     * ID 고유성, 필수 필드, 도구 수 일관성, 카테고리 유효성을 확인한다.
     */
    @Test
    fun `골든 데이터셋 무결성 검증`() {
        validateDatasetIntegrity()
    }

    // ────────────────────────────────────────────────────
    // 3. 도구 선택 시뮬레이션 리포트
    // ────────────────────────────────────────────────────

    /**
     * 모드 해석 결과를 기반으로 도구 선택 시뮬레이션 메트릭을 출력한다.
     *
     * 모드가 STANDARD로 올바르게 해석되면 도구 미사용 = 정확,
     * REACT/PLAN_EXECUTE로 올바르게 해석되면 도구 사용 의도 정확으로 간주.
     */
    @Test
    fun `도구 선택 시뮬레이션 리포트`() = runTest {
        val results = dataset.testCases
            .filter { it.expectedTools.isNotEmpty() }
            .map { tc -> evaluateToolSelection(tc) }
        printToolSelectionReport(results)
    }

    // ── 모드 해석 평가 ──

    private suspend fun evaluateModeResolution(
        tc: TestCase
    ): ModeEvalResult {
        val command = AgentCommand(
            systemPrompt = "You are a helpful assistant.",
            userPrompt = tc.query
        )
        val tools = if (tc.expectedTools.isEmpty()) {
            emptyList()
        } else {
            sampleTools
        }
        val actual = resolver.resolve(command, tools)
        val expected = AgentMode.valueOf(tc.expectedMode)
        return ModeEvalResult(
            testCaseId = tc.id,
            category = tc.category,
            query = tc.query,
            expected = expected,
            actual = actual,
            correct = actual == expected
        )
    }

    private suspend fun evaluateToolSelection(
        tc: TestCase
    ): ToolEvalResult {
        val command = AgentCommand(
            systemPrompt = "You are a helpful assistant.",
            userPrompt = tc.query
        )
        val actual = resolver.resolve(command, sampleTools)
        val expected = AgentMode.valueOf(tc.expectedMode)
        val modeCorrect = actual == expected

        val needsTools = expected != AgentMode.STANDARD
        val modeImpliesTools = actual != AgentMode.STANDARD

        return ToolEvalResult(
            testCaseId = tc.id,
            category = tc.category,
            expectedTools = tc.expectedTools,
            modeCorrect = modeCorrect,
            toolIntentCorrect = needsTools == modeImpliesTools
        )
    }

    // ── 리포트 출력 ──

    private fun printModeAccuracyReport(results: List<ModeEvalResult>) {
        val total = results.size
        val correct = results.count { it.correct }
        val accuracy = if (total > 0) correct.toDouble() / total else 0.0

        val report = buildString {
            appendLine()
            appendLine("=" .repeat(REPORT_WIDTH))
            appendLine("  도구 선택 벤치마크 — 모드 해석 정확도 리포트")
            appendLine("=" .repeat(REPORT_WIDTH))
            appendLine()
            appendFormattedLine("전체 정확도", accuracy, correct, total)
            appendLine()
            appendLine("-" .repeat(REPORT_WIDTH))
            appendLine("  카테고리별 상세")
            appendLine("-" .repeat(REPORT_WIDTH))
            appendCategoryBreakdown(results)
            appendLine()
            appendMismatchDetails(results)
            appendLine("=" .repeat(REPORT_WIDTH))
        }
        logger.info { report }
        println(report)
    }

    private fun printToolSelectionReport(results: List<ToolEvalResult>) {
        val total = results.size
        val correctIntent = results.count { it.toolIntentCorrect }
        val correctMode = results.count { it.modeCorrect }

        val report = buildString {
            appendLine()
            appendLine("=" .repeat(REPORT_WIDTH))
            appendLine("  도구 선택 벤치마크 — 도구 선택 시뮬레이션 리포트")
            appendLine("=" .repeat(REPORT_WIDTH))
            appendLine()
            appendFormattedLine(
                "correct_tool_rate (모드 기반)",
                correctMode.toDouble() / total.coerceAtLeast(1),
                correctMode, total
            )
            appendFormattedLine(
                "wrong_tool_rate (모드 불일치)",
                (total - correctMode).toDouble() / total.coerceAtLeast(1),
                total - correctMode, total
            )
            appendFormattedLine(
                "hallucinated_tool_rate",
                0.0, 0, total
            )
            appendFormattedLine(
                "tool_intent_accuracy",
                correctIntent.toDouble() / total.coerceAtLeast(1),
                correctIntent, total
            )
            appendLine()
            appendLine("  * hallucinated_tool_rate = 0%: 휴리스틱 모드")
            appendLine("    해석기는 도구명을 생성하지 않으므로 환각 불가")
            appendLine("  * correct/wrong_tool_rate는 모드 일치 여부로 추정")
            appendLine("=" .repeat(REPORT_WIDTH))
        }
        logger.info { report }
        println(report)
    }

    // ── 데이터셋 검증 ──

    private fun validateDatasetIntegrity() {
        val errors = mutableListOf<String>()
        validateUniqueIds(errors)
        validateRequiredFields(errors)
        validateToolCountConsistency(errors)
        validateCategories(errors)
        validateExpectedModes(errors)

        if (errors.isNotEmpty()) {
            val report = buildString {
                appendLine("데이터셋 무결성 오류 ${errors.size}건:")
                errors.forEachIndexed { i, e ->
                    appendLine("  ${i + 1}. $e")
                }
            }
            logger.error { report }
            throw AssertionError(report)
        }

        val summary = "데이터셋 무결성 검증 통과: " +
            "${dataset.testCases.size}건, " +
            "${dataset.testCases.map { it.category }.toSet().size}개 카테고리"
        logger.info { summary }
        println(summary)
    }

    private fun validateUniqueIds(errors: MutableList<String>) {
        val ids = dataset.testCases.map { it.id }
        val duplicates = ids.groupBy { it }
            .filter { it.value.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            errors.add("중복 ID 발견: $duplicates")
        }
    }

    private fun validateRequiredFields(errors: MutableList<String>) {
        for (tc in dataset.testCases) {
            if (tc.id.isBlank()) {
                errors.add("빈 ID 발견: $tc")
            }
            if (tc.category.isBlank()) {
                errors.add("[${tc.id}] 빈 카테고리")
            }
            if (tc.expectedMode.isBlank()) {
                errors.add("[${tc.id}] 빈 expected_mode")
            }
        }
    }

    /**
     * expected_tool_count와 expected_tools의 일관성을 검증한다.
     *
     * expected_tool_count는 도구 호출 횟수(같은 도구 반복 포함),
     * expected_tools는 고유 도구 이름 목록이므로
     * count >= tools.size 관계가 성립해야 한다.
     */
    private fun validateToolCountConsistency(
        errors: MutableList<String>
    ) {
        for (tc in dataset.testCases) {
            if (tc.expectedToolCount < tc.expectedTools.size) {
                errors.add(
                    "[${tc.id}] expected_tool_count" +
                        "(${tc.expectedToolCount}) < " +
                        "expected_tools.size" +
                        "(${tc.expectedTools.size})"
                )
            }
        }
    }

    private fun validateCategories(errors: MutableList<String>) {
        val categories = dataset.testCases.map { it.category }.toSet()
        for (category in categories) {
            if (!VALID_CATEGORIES.contains(category)) {
                errors.add("알 수 없는 카테고리: $category")
            }
        }
    }

    private fun validateExpectedModes(errors: MutableList<String>) {
        for (tc in dataset.testCases) {
            runCatching {
                AgentMode.valueOf(tc.expectedMode)
            }.onFailure {
                errors.add(
                    "[${tc.id}] 유효하지 않은 expected_mode: " +
                        tc.expectedMode
                )
            }
        }
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

    private fun StringBuilder.appendCategoryBreakdown(
        results: List<ModeEvalResult>
    ) {
        results.groupBy { it.category }.toSortedMap()
            .forEach { (category, categoryResults) ->
                val catTotal = categoryResults.size
                val catCorrect = categoryResults.count { it.correct }
                val catRate = catCorrect.toDouble() / catTotal
                appendLine(
                    "  %-28s %6.1f%%  (%d/%d)".format(
                        category, catRate * PERCENT_MULTIPLIER,
                        catCorrect, catTotal
                    )
                )
            }
    }

    private fun StringBuilder.appendMismatchDetails(
        results: List<ModeEvalResult>
    ) {
        val mismatches = results.filter { !it.correct }
        if (mismatches.isEmpty()) return
        appendLine("-" .repeat(REPORT_WIDTH))
        appendLine("  불일치 상세 (${mismatches.size}건)")
        appendLine("-" .repeat(REPORT_WIDTH))
        for (m in mismatches) {
            appendLine(
                "  [${m.testCaseId}] " +
                    "expected=${m.expected}, actual=${m.actual}"
            )
            appendLine(
                "    query: ${m.query.take(MAX_QUERY_DISPLAY_LENGTH)}"
            )
        }
    }

    // ── 최소 정확도 검증 ──

    private fun assertMinimumAccuracy(results: List<ModeEvalResult>) {
        val total = results.size
        val correct = results.count { it.correct }
        val accuracy = correct.toDouble() / total.coerceAtLeast(1)
        assert(accuracy >= MIN_ACCURACY_THRESHOLD) {
            "모드 해석 정확도 %.1f%%가 최소 임계값 %.1f%%에 미달"
                .format(
                    accuracy * PERCENT_MULTIPLIER,
                    MIN_ACCURACY_THRESHOLD * PERCENT_MULTIPLIER
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

    /** 골든 데이터셋에서 모든 고유 도구 이름을 추출한다. */
    private fun extractAllToolNames(): List<String> {
        return dataset.testCases
            .flatMap { it.expectedTools }
            .distinct()
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

    private data class ModeEvalResult(
        val testCaseId: String,
        val category: String,
        val query: String,
        val expected: AgentMode,
        val actual: AgentMode,
        val correct: Boolean
    )

    private data class ToolEvalResult(
        val testCaseId: String,
        val category: String,
        val expectedTools: List<String>,
        val modeCorrect: Boolean,
        val toolIntentCorrect: Boolean
    )

    companion object {
        /** 리포트 출력 너비 (문자 수) */
        private const val REPORT_WIDTH = 64

        /** 쿼리 표시 최대 길이 */
        private const val MAX_QUERY_DISPLAY_LENGTH = 60

        /** 퍼센트 변환 상수 */
        private const val PERCENT_MULTIPLIER = 100.0

        /** 최소 정확도 임계값 (50% — 휴리스틱 한계 고려) */
        private const val MIN_ACCURACY_THRESHOLD = 0.50

        /** 골든 데이터셋에서 사용되는 유효 카테고리 목록 */
        private val VALID_CATEGORIES = setOf(
            "jira_single",
            "confluence_search",
            "bitbucket",
            "swagger",
            "multi_tool",
            "security_block",
            "standard_direct",
            "edge_case"
        )
    }
}
