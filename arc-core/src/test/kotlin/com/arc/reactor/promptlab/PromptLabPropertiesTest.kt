package com.arc.reactor.promptlab

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * PromptLabProperties 기본값 및 중첩 설정 프로퍼티 검증 테스트.
 *
 * WHY: 기본값이 비즈니스 규칙을 정의하므로(실험 비용, 동시성 한도 등)
 * 의도치 않은 변경을 조기에 탐지한다.
 */
class PromptLabPropertiesTest {

    @Nested
    inner class PromptLabPropertiesDefaults {

        private val props = PromptLabProperties()

        @Test
        fun `기본값 enabled는 false여야 한다`() {
            assertFalse(props.enabled) {
                "PromptLab은 기본적으로 비활성화(opt-in)여야 한다"
            }
        }

        @Test
        fun `기본값 maxConcurrentExperiments는 3이어야 한다`() {
            assertEquals(3, props.maxConcurrentExperiments) {
                "동시 실험 기본 한도는 3이어야 한다"
            }
        }

        @Test
        fun `기본값 maxQueriesPerExperiment는 100이어야 한다`() {
            assertEquals(100, props.maxQueriesPerExperiment) {
                "실험당 최대 쿼리 수 기본값은 100이어야 한다"
            }
        }

        @Test
        fun `기본값 maxVersionsPerExperiment는 10이어야 한다`() {
            assertEquals(10, props.maxVersionsPerExperiment) {
                "실험당 최대 버전 수 기본값은 10이어야 한다"
            }
        }

        @Test
        fun `기본값 maxRepetitions는 5이어야 한다`() {
            assertEquals(5, props.maxRepetitions) {
                "버전-쿼리 쌍당 최대 반복 횟수 기본값은 5이어야 한다"
            }
        }

        @Test
        fun `기본값 defaultJudgeModel은 null이어야 한다`() {
            assertNull(props.defaultJudgeModel) {
                "defaultJudgeModel 기본값은 null (실험 모델과 동일)이어야 한다"
            }
        }

        @Test
        fun `기본값 defaultJudgeBudgetTokens는 100_000이어야 한다`() {
            assertEquals(100_000, props.defaultJudgeBudgetTokens) {
                "LLM 심판 기본 토큰 예산은 100,000이어야 한다"
            }
        }

        @Test
        fun `기본값 experimentTimeoutMs는 600_000이어야 한다`() {
            assertEquals(600_000L, props.experimentTimeoutMs) {
                "실험 타임아웃 기본값은 600,000ms (10분)이어야 한다"
            }
        }

        @Test
        fun `기본값 candidateCount는 3이어야 한다`() {
            assertEquals(3, props.candidateCount) {
                "자동 생성 후보 프롬프트 수 기본값은 3이어야 한다"
            }
        }

        @Test
        fun `기본값 minNegativeFeedback는 5이어야 한다`() {
            assertEquals(5, props.minNegativeFeedback) {
                "자동 파이프라인 트리거 최소 부정 피드백 수 기본값은 5이어야 한다"
            }
        }
    }

    @Nested
    inner class SchedulePropertiesDefaults {

        private val schedule = ScheduleProperties()

        @Test
        fun `기본값 enabled는 false여야 한다`() {
            assertFalse(schedule.enabled) {
                "스케줄 자동 최적화는 기본적으로 비활성화(opt-in)여야 한다"
            }
        }

        @Test
        fun `기본값 cron은 매일 오전 2시 표현식이어야 한다`() {
            assertEquals("0 0 2 * * *", schedule.cron) {
                "기본 크론 표현식은 매일 오전 2시(0 0 2 * * *)이어야 한다"
            }
        }

        @Test
        fun `기본값 templateIds는 빈 목록이어야 한다`() {
            assertTrue(schedule.templateIds.isEmpty()) {
                "기본 templateIds는 빈 목록(모든 템플릿 대상)이어야 한다"
            }
        }
    }

    @Nested
    inner class LiveExperimentPropertiesDefaults {

        private val live = LiveExperimentProperties()

        @Test
        fun `기본값 enabled는 false여야 한다`() {
            assertFalse(live.enabled) {
                "라이브 A/B 테스트는 기본적으로 비활성화(opt-in)여야 한다"
            }
        }

        @Test
        fun `기본값 maxRunningExperiments는 5이어야 한다`() {
            assertEquals(5, live.maxRunningExperiments) {
                "동시 실행 가능한 라이브 실험 기본 한도는 5이어야 한다"
            }
        }

        @Test
        fun `기본값 maxResultsPerExperiment는 10_000이어야 한다`() {
            assertEquals(10_000, live.maxResultsPerExperiment) {
                "실험당 최대 결과 보관 수 기본값은 10,000이어야 한다"
            }
        }
    }

    @Nested
    inner class NestedPropertiesComposition {

        @Test
        fun `PromptLabProperties에 중첩된 ScheduleProperties 기본값이 올바르게 구성되어야 한다`() {
            val props = PromptLabProperties()

            assertFalse(props.schedule.enabled) {
                "중첩된 schedule.enabled는 false여야 한다"
            }
            assertEquals("0 0 2 * * *", props.schedule.cron) {
                "중첩된 schedule.cron 기본값이 올바르지 않다"
            }
        }

        @Test
        fun `PromptLabProperties에 중첩된 LiveExperimentProperties 기본값이 올바르게 구성되어야 한다`() {
            val props = PromptLabProperties()

            assertFalse(props.liveExperiment.enabled) {
                "중첩된 liveExperiment.enabled는 false여야 한다"
            }
            assertEquals(5, props.liveExperiment.maxRunningExperiments) {
                "중첩된 liveExperiment.maxRunningExperiments 기본값이 올바르지 않다"
            }
        }

        @Test
        fun `copy로 특정 필드만 변경해도 나머지 기본값은 유지되어야 한다`() {
            val props = PromptLabProperties(enabled = true)

            assertTrue(props.enabled) { "enabled를 true로 설정해야 한다" }
            assertEquals(3, props.maxConcurrentExperiments) {
                "copy 후 maxConcurrentExperiments 기본값은 변경되지 않아야 한다"
            }
            assertEquals(100, props.maxQueriesPerExperiment) {
                "copy 후 maxQueriesPerExperiment 기본값은 변경되지 않아야 한다"
            }
        }
    }

    @Nested
    inner class CustomValues {

        @Test
        fun `커스텀 값으로 생성된 PromptLabProperties가 올바르게 저장되어야 한다`() {
            val props = PromptLabProperties(
                enabled = true,
                maxConcurrentExperiments = 10,
                maxQueriesPerExperiment = 50,
                defaultJudgeModel = "gemini-pro",
                experimentTimeoutMs = 300_000L,
                minNegativeFeedback = 10
            )

            assertTrue(props.enabled) { "enabled가 true여야 한다" }
            assertEquals(10, props.maxConcurrentExperiments) {
                "maxConcurrentExperiments가 10이어야 한다"
            }
            assertEquals(50, props.maxQueriesPerExperiment) {
                "maxQueriesPerExperiment가 50이어야 한다"
            }
            assertEquals("gemini-pro", props.defaultJudgeModel) {
                "defaultJudgeModel이 gemini-pro여야 한다"
            }
            assertEquals(300_000L, props.experimentTimeoutMs) {
                "experimentTimeoutMs가 300,000이어야 한다"
            }
            assertEquals(10, props.minNegativeFeedback) {
                "minNegativeFeedback이 10이어야 한다"
            }
        }

        @Test
        fun `커스텀 ScheduleProperties가 올바르게 저장되어야 한다`() {
            val schedule = ScheduleProperties(
                enabled = true,
                cron = "0 0 3 * * MON",
                templateIds = listOf("tpl-1", "tpl-2")
            )

            assertTrue(schedule.enabled) { "schedule.enabled가 true여야 한다" }
            assertEquals("0 0 3 * * MON", schedule.cron) {
                "schedule.cron이 올바르게 저장되어야 한다"
            }
            assertEquals(listOf("tpl-1", "tpl-2"), schedule.templateIds) {
                "schedule.templateIds가 올바르게 저장되어야 한다"
            }
        }

        @Test
        fun `커스텀 LiveExperimentProperties가 올바르게 저장되어야 한다`() {
            val live = LiveExperimentProperties(
                enabled = true,
                maxRunningExperiments = 20,
                maxResultsPerExperiment = 50_000
            )

            assertTrue(live.enabled) { "liveExperiment.enabled가 true여야 한다" }
            assertEquals(20, live.maxRunningExperiments) {
                "liveExperiment.maxRunningExperiments가 20이어야 한다"
            }
            assertEquals(50_000, live.maxResultsPerExperiment) {
                "liveExperiment.maxResultsPerExperiment가 50,000이어야 한다"
            }
        }
    }
}
