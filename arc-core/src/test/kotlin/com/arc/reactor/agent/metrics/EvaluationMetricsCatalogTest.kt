package com.arc.reactor.agent.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [EvaluationMetricsCatalog] 테스트.
 *
 * R234: 카탈로그의 구조적 일관성과 Micrometer 구현체와의 정렬을 검증한다.
 * 카탈로그 ↔ 실제 기록 코드 drift를 원천 차단한다.
 */
class EvaluationMetricsCatalogTest {

    @Nested
    inner class BasicInvariants {

        @Test
        fun `ALL 리스트는 8개 메트릭을 포함해야 한다`() {
            assertEquals(8, EvaluationMetricsCatalog.ALL.size) {
                "R222 6개 + R224 1개 + R242 1개 = 8개"
            }
        }

        @Test
        fun `모든 메트릭 이름은 고유해야 한다`() {
            val names = EvaluationMetricsCatalog.ALL.map { it.name }
            assertEquals(names.size, names.distinct().size) {
                "메트릭 이름이 중복되면 안 된다: $names"
            }
        }

        @Test
        fun `모든 메트릭은 prefix arc_reactor_eval을 가져야 한다`() {
            EvaluationMetricsCatalog.ALL.forEach { metric ->
                assertTrue(
                    metric.name.startsWith(EvaluationMetricsCatalog.METRIC_NAME_PREFIX)
                ) {
                    "메트릭 '${metric.name}'은 '${EvaluationMetricsCatalog.METRIC_NAME_PREFIX}' " +
                        "로 시작해야 한다"
                }
            }
        }

        @Test
        fun `모든 메트릭은 설명을 가져야 한다`() {
            EvaluationMetricsCatalog.ALL.forEach { metric ->
                assertTrue(metric.description.isNotBlank()) {
                    "메트릭 '${metric.name}'은 description이 비어있으면 안 된다"
                }
            }
        }

        @Test
        fun `METRIC_NAME_PREFIX는 arc_reactor_eval이어야 한다`() {
            assertEquals(
                "arc.reactor.eval.",
                EvaluationMetricsCatalog.METRIC_NAME_PREFIX
            )
        }
    }

    @Nested
    inner class TypeDistribution {

        @Test
        fun `COUNTER 유형은 5개여야 한다`() {
            val counters = EvaluationMetricsCatalog.filterByType(
                EvaluationMetricsCatalog.MetricType.COUNTER
            )
            assertEquals(5, counters.size) {
                "TASK_COMPLETED/TOKEN_COST/HUMAN_OVERRIDE/SAFETY_REJECTION/TOOL_RESPONSE_KIND"
            }
        }

        @Test
        fun `TIMER 유형은 1개여야 한다`() {
            val timers = EvaluationMetricsCatalog.filterByType(
                EvaluationMetricsCatalog.MetricType.TIMER
            )
            assertEquals(1, timers.size) { "TASK_DURATION 한 개" }
            assertEquals(
                EvaluationMetricsCatalog.TASK_DURATION.name,
                timers[0].name
            )
        }

        @Test
        fun `DISTRIBUTION_SUMMARY 유형은 2개여야 한다`() {
            val summaries = EvaluationMetricsCatalog.filterByType(
                EvaluationMetricsCatalog.MetricType.DISTRIBUTION_SUMMARY
            )
            assertEquals(2, summaries.size) {
                "R222 TOOL_CALLS + R242 TOOL_RESPONSE_COMPRESSION"
            }
            val names = summaries.map { it.name }.toSet()
            assertTrue(names.contains(EvaluationMetricsCatalog.TOOL_CALLS.name)) {
                "TOOL_CALLS 포함"
            }
            assertTrue(names.contains(EvaluationMetricsCatalog.TOOL_RESPONSE_COMPRESSION.name)) {
                "TOOL_RESPONSE_COMPRESSION 포함"
            }
        }
    }

    @Nested
    inner class MicrometerAlignment {

        /**
         * 카탈로그가 참조하는 메트릭 이름은 MicrometerEvaluationMetricsCollector 상수와
         * 일치해야 한다 (카탈로그는 상수를 직접 참조하므로 자동 보장되지만, 명시적 검증).
         */
        @Test
        fun `카탈로그 이름은 MicrometerEvaluationMetricsCollector 상수와 일치해야 한다`() {
            assertEquals(
                MicrometerEvaluationMetricsCollector.METRIC_TASK_COMPLETED,
                EvaluationMetricsCatalog.TASK_COMPLETED.name
            )
            assertEquals(
                MicrometerEvaluationMetricsCollector.METRIC_TASK_DURATION,
                EvaluationMetricsCatalog.TASK_DURATION.name
            )
            assertEquals(
                MicrometerEvaluationMetricsCollector.METRIC_TOOL_CALLS,
                EvaluationMetricsCatalog.TOOL_CALLS.name
            )
            assertEquals(
                MicrometerEvaluationMetricsCollector.METRIC_TOKEN_COST,
                EvaluationMetricsCatalog.TOKEN_COST.name
            )
            assertEquals(
                MicrometerEvaluationMetricsCollector.METRIC_HUMAN_OVERRIDE,
                EvaluationMetricsCatalog.HUMAN_OVERRIDE.name
            )
            assertEquals(
                MicrometerEvaluationMetricsCollector.METRIC_SAFETY_REJECTION,
                EvaluationMetricsCatalog.SAFETY_REJECTION.name
            )
            assertEquals(
                MicrometerEvaluationMetricsCollector.METRIC_TOOL_RESPONSE_KIND,
                EvaluationMetricsCatalog.TOOL_RESPONSE_KIND.name
            )
            assertEquals(
                MicrometerEvaluationMetricsCollector.METRIC_TOOL_RESPONSE_COMPRESSION,
                EvaluationMetricsCatalog.TOOL_RESPONSE_COMPRESSION.name
            )
        }

        @Test
        fun `TASK_COMPLETED 태그는 result와 error_code여야 한다`() {
            assertEquals(
                listOf("result", "error_code"),
                EvaluationMetricsCatalog.TASK_COMPLETED.tags
            )
        }

        @Test
        fun `TOOL_RESPONSE_KIND 태그는 kind와 tool이어야 한다`() {
            assertEquals(
                listOf("kind", "tool"),
                EvaluationMetricsCatalog.TOOL_RESPONSE_KIND.tags
            )
        }

        @Test
        fun `TOOL_RESPONSE_COMPRESSION 태그는 tool만이어야 한다`() {
            assertEquals(
                listOf("tool"),
                EvaluationMetricsCatalog.TOOL_RESPONSE_COMPRESSION.tags
            ) {
                "R242 압축률은 도구별로만 집계"
            }
        }

        @Test
        fun `TOOL_CALLS는 태그가 없어야 한다`() {
            assertTrue(EvaluationMetricsCatalog.TOOL_CALLS.tags.isEmpty()) {
                "DistributionSummary는 태그 없이 기록"
            }
        }

        /**
         * 실제로 Micrometer 수집기가 카탈로그와 일치하는 이름으로 메트릭을 등록하는지
         * end-to-end로 검증한다.
         */
        @Test
        fun `Micrometer 수집기가 카탈로그와 동일한 이름으로 메트릭을 기록해야 한다`() {
            val registry = SimpleMeterRegistry()
            val collector = MicrometerEvaluationMetricsCollector(registry)

            // 모든 메트릭을 한 번씩 기록
            collector.recordTaskCompleted(success = true, durationMs = 100L)
            collector.recordToolCallCount(count = 2)
            collector.recordTokenCost(costUsd = 0.001, model = "gemini-flash")
            collector.recordHumanOverride(HumanOverrideOutcome.APPROVED, "test_tool")
            collector.recordSafetyRejection(SafetyRejectionStage.GUARD, "injection")
            collector.recordToolResponseKind("list_top_n", "jira_search")
            collector.recordToolResponseCompression(75, "jira_search")

            // 카탈로그의 각 메트릭이 registry에 존재하는지 확인
            EvaluationMetricsCatalog.ALL.forEach { metric ->
                val found = registry.meters.any { it.id.name == metric.name }
                assertTrue(found) {
                    "카탈로그의 메트릭 '${metric.name}'이 Micrometer registry에 존재해야 한다"
                }
            }
        }
    }

    @Nested
    inner class LookupHelpers {

        @Test
        fun `findByName은 등록된 메트릭을 반환해야 한다`() {
            val found = EvaluationMetricsCatalog.findByName(
                "arc.reactor.eval.task.completed"
            )
            assertNotNull(found)
            assertEquals(EvaluationMetricsCatalog.TASK_COMPLETED, found)
        }

        @Test
        fun `findByName은 알 수 없는 이름에 null을 반환해야 한다`() {
            assertNull(EvaluationMetricsCatalog.findByName("arc.reactor.eval.unknown"))
            assertNull(EvaluationMetricsCatalog.findByName(""))
        }

        @Test
        fun `filterByType은 유형별로 정렬된 리스트를 반환해야 한다`() {
            val all = EvaluationMetricsCatalog.ALL
            val counters = EvaluationMetricsCatalog.filterByType(
                EvaluationMetricsCatalog.MetricType.COUNTER
            )
            val timers = EvaluationMetricsCatalog.filterByType(
                EvaluationMetricsCatalog.MetricType.TIMER
            )
            val summaries = EvaluationMetricsCatalog.filterByType(
                EvaluationMetricsCatalog.MetricType.DISTRIBUTION_SUMMARY
            )
            // 합쳐서 전체와 같아야 한다
            assertEquals(all.size, counters.size + timers.size + summaries.size)
        }
    }

    @Nested
    inner class UnitField {

        @Test
        fun `TASK_DURATION 단위는 ms이어야 한다`() {
            assertEquals("ms", EvaluationMetricsCatalog.TASK_DURATION.unit)
        }

        @Test
        fun `TOKEN_COST 단위는 usd이어야 한다`() {
            assertEquals("usd", EvaluationMetricsCatalog.TOKEN_COST.unit)
        }

        @Test
        fun `TOOL_CALLS 단위는 calls여야 한다`() {
            assertEquals("calls", EvaluationMetricsCatalog.TOOL_CALLS.unit)
        }

        @Test
        fun `TOOL_RESPONSE_COMPRESSION 단위는 percent여야 한다`() {
            assertEquals(
                "percent",
                EvaluationMetricsCatalog.TOOL_RESPONSE_COMPRESSION.unit
            )
        }

        @Test
        fun `TASK_COMPLETED는 unit이 null이어야 한다 (단순 카운트)`() {
            assertNull(EvaluationMetricsCatalog.TASK_COMPLETED.unit)
        }
    }
}
