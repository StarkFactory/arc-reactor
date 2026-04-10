package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.MicrometerEvaluationMetricsCollector
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R254: [parseToolArguments] 함수의 PARSING stage 자동 기록 테스트.
 *
 * `execution.error{stage="parsing"}` 메트릭이 JSON 파싱 실패 시 올바르게 기록되는지 검증.
 * 기존 top-level 함수의 fail-open 동작(빈 맵 반환)은 불변.
 */
class ToolArgumentParserR254Test {

    private fun newCollector(): Pair<SimpleMeterRegistry, EvaluationMetricsCollector> {
        val registry = SimpleMeterRegistry()
        return registry to MicrometerEvaluationMetricsCollector(registry)
    }

    @Test
    fun `R254 유효한 JSON은 파싱되고 기록되지 않음`() {
        val (registry, collector) = newCollector()
        val result = parseToolArguments("""{"query":"test","limit":10}""", collector)

        assertEquals("test", result["query"])
        assertEquals(10, result["limit"])

        val meter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .counter()
        assertNull(meter) { "정상 파싱에서는 기록 안 됨" }
    }

    @Test
    fun `R254 유효하지 않은 JSON은 PARSING stage로 기록되고 빈 맵 반환`() {
        val (registry, collector) = newCollector()
        val result = parseToolArguments("not valid json {{{", collector)

        // fail-open — 빈 맵 반환
        assertTrue(result.isEmpty()) { "파싱 실패 시 빈 맵" }

        val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "parsing")
            .counter()
        assertNotNull(counter) {
            "파싱 예외가 PARSING stage로 기록되어야 한다"
        }
        assertEquals(1.0, counter!!.count())
    }

    @Test
    fun `R254 null 또는 blank JSON은 기록 없이 빈 맵 반환`() {
        val (registry, collector) = newCollector()

        val nullResult = parseToolArguments(null, collector)
        val blankResult = parseToolArguments("   ", collector)
        val emptyResult = parseToolArguments("", collector)

        assertTrue(nullResult.isEmpty())
        assertTrue(blankResult.isEmpty())
        assertTrue(emptyResult.isEmpty())

        val meter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .counter()
        assertNull(meter) {
            "null/blank는 파싱 시도 자체가 없으므로 기록 없음 (Early return)"
        }
    }

    @Test
    fun `R254 여러 파싱 실패가 각각 카운트되어야 한다`() {
        val (registry, collector) = newCollector()

        parseToolArguments("{broken", collector)
        parseToolArguments("another { broken", collector)
        parseToolArguments("}}}", collector)

        val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "parsing")
            .counter()
        assertNotNull(counter)
        assertEquals(3.0, counter!!.count()) {
            "3회 파싱 실패 누적"
        }
    }

    @Test
    fun `R254 기본 파라미터(NoOp)는 backward compat 유지`() {
        // 파라미터 생략 → NoOpEvaluationMetricsCollector 기본값
        val result = parseToolArguments("{broken json")
        assertTrue(result.isEmpty()) { "fail-open 동작 그대로" }
        // 예외 없이 반환되면 PASS
    }

    @Test
    fun `R254 NoOp 명시 주입도 backward compat`() {
        val result = parseToolArguments("{broken", NoOpEvaluationMetricsCollector)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `R254 JSON 객체가 아닌 배열 JSON은 파싱 실패로 기록`() {
        val (registry, collector) = newCollector()
        // 배열은 Map<String, Any?>로 파싱 불가 → 예외
        parseToolArguments("[1, 2, 3]", collector)

        val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "parsing")
            .counter()
        assertNotNull(counter) {
            "Map 타입 불일치도 파싱 실패로 기록"
        }
    }
}
