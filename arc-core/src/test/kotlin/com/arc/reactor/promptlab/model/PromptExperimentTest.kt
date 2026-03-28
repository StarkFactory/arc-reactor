package com.arc.reactor.promptlab.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * PromptExperiment 모델 계층 테스트.
 *
 * ExperimentMetrics 계산 프로퍼티와 PromptExperiment 초기화 검증을 커버한다.
 */
class PromptExperimentTest {

    // ── ExperimentMetrics ──────────────────────────────────────────────────

    @Nested
    inner class ExperimentMetricsSuccessRate {

        @Test
        fun `샘플이 없을 때 controlSuccessRate는 0이어야 한다`() {
            val metrics = ExperimentMetrics(controlSuccessCount = 0, controlTotalCount = 0)

            assertEquals(0.0, metrics.controlSuccessRate) {
                "샘플 없는 경우 control 성공률은 0.0이어야 한다"
            }
        }

        @Test
        fun `샘플이 없을 때 variantSuccessRate는 0이어야 한다`() {
            val metrics = ExperimentMetrics(variantSuccessCount = 0, variantTotalCount = 0)

            assertEquals(0.0, metrics.variantSuccessRate) {
                "샘플 없는 경우 variant 성공률은 0.0이어야 한다"
            }
        }

        @Test
        fun `controlSuccessRate는 성공 수를 전체 수로 나눠야 한다`() {
            val metrics = ExperimentMetrics(controlSuccessCount = 3, controlTotalCount = 4)

            assertEquals(0.75, metrics.controlSuccessRate) {
                "control 성공률은 3/4 = 0.75이어야 한다"
            }
        }

        @Test
        fun `variantSuccessRate는 성공 수를 전체 수로 나눠야 한다`() {
            val metrics = ExperimentMetrics(variantSuccessCount = 9, variantTotalCount = 10)

            assertEquals(0.9, metrics.variantSuccessRate) {
                "variant 성공률은 9/10 = 0.9이어야 한다"
            }
        }

        @Test
        fun `모든 샘플이 성공일 때 성공률은 1이어야 한다`() {
            val metrics = ExperimentMetrics(controlSuccessCount = 5, controlTotalCount = 5)

            assertEquals(1.0, metrics.controlSuccessRate) {
                "전원 성공이면 성공률은 1.0이어야 한다"
            }
        }

        @Test
        fun `성공이 0건일 때 성공률은 0이어야 한다`() {
            val metrics = ExperimentMetrics(controlSuccessCount = 0, controlTotalCount = 10)

            assertEquals(0.0, metrics.controlSuccessRate) {
                "성공이 없으면 성공률은 0.0이어야 한다"
            }
        }
    }

    @Nested
    inner class ExperimentMetricsTotalSampleCount {

        @Test
        fun `totalSampleCount는 control과 variant 합산이어야 한다`() {
            val metrics = ExperimentMetrics(controlTotalCount = 30, variantTotalCount = 10)

            assertEquals(40, metrics.totalSampleCount) {
                "총 샘플 수는 control(30) + variant(10) = 40이어야 한다"
            }
        }

        @Test
        fun `모든 카운트가 0이면 totalSampleCount는 0이어야 한다`() {
            val metrics = ExperimentMetrics()

            assertEquals(0, metrics.totalSampleCount) {
                "기본 값에서 총 샘플 수는 0이어야 한다"
            }
        }
    }

    @Nested
    inner class ExperimentMetricsAvgLatency {

        @Test
        fun `샘플이 없을 때 controlAvgLatencyMs는 0이어야 한다`() {
            val metrics = ExperimentMetrics(controlTotalLatencyMs = 1000, controlTotalCount = 0)

            assertEquals(0L, metrics.controlAvgLatencyMs) {
                "샘플 없는 경우 control 평균 지연시간은 0이어야 한다"
            }
        }

        @Test
        fun `샘플이 없을 때 variantAvgLatencyMs는 0이어야 한다`() {
            val metrics = ExperimentMetrics(variantTotalLatencyMs = 2000, variantTotalCount = 0)

            assertEquals(0L, metrics.variantAvgLatencyMs) {
                "샘플 없는 경우 variant 평균 지연시간은 0이어야 한다"
            }
        }

        @Test
        fun `controlAvgLatencyMs는 누적 지연시간을 샘플 수로 나눠야 한다`() {
            val metrics = ExperimentMetrics(controlTotalLatencyMs = 600L, controlTotalCount = 3)

            assertEquals(200L, metrics.controlAvgLatencyMs) {
                "control 평균 지연시간은 600 / 3 = 200ms이어야 한다"
            }
        }

        @Test
        fun `variantAvgLatencyMs는 누적 지연시간을 샘플 수로 나눠야 한다`() {
            val metrics = ExperimentMetrics(variantTotalLatencyMs = 400L, variantTotalCount = 4)

            assertEquals(100L, metrics.variantAvgLatencyMs) {
                "variant 평균 지연시간은 400 / 4 = 100ms이어야 한다"
            }
        }
    }

    // ── PromptExperiment ───────────────────────────────────────────────────

    @Nested
    inner class PromptExperimentInitValidation {

        @Test
        fun `trafficPercent가 0이면 정상 생성되어야 한다`() {
            val experiment = PromptExperiment(
                name = "실험-최소",
                controlPrompt = "기준 프롬프트",
                variantPrompt = "후보 프롬프트",
                trafficPercent = 0
            )

            assertEquals(0, experiment.trafficPercent) {
                "trafficPercent 0은 허용되어야 한다"
            }
        }

        @Test
        fun `trafficPercent가 100이면 정상 생성되어야 한다`() {
            val experiment = PromptExperiment(
                name = "실험-최대",
                controlPrompt = "기준 프롬프트",
                variantPrompt = "후보 프롬프트",
                trafficPercent = 100
            )

            assertEquals(100, experiment.trafficPercent) {
                "trafficPercent 100은 허용되어야 한다"
            }
        }

        @Test
        fun `trafficPercent가 음수이면 IllegalArgumentException이 발생해야 한다`() {
            assertThrows(IllegalArgumentException::class.java) {
                PromptExperiment(
                    name = "잘못된-실험",
                    controlPrompt = "기준",
                    variantPrompt = "후보",
                    trafficPercent = -1
                )
            }
        }

        @Test
        fun `trafficPercent가 101이면 IllegalArgumentException이 발생해야 한다`() {
            assertThrows(IllegalArgumentException::class.java) {
                PromptExperiment(
                    name = "잘못된-실험",
                    controlPrompt = "기준",
                    variantPrompt = "후보",
                    trafficPercent = 101
                )
            }
        }

        @Test
        fun `기본 status는 DRAFT이어야 한다`() {
            val experiment = PromptExperiment(
                name = "상태-기본값-실험",
                controlPrompt = "기준 프롬프트",
                variantPrompt = "후보 프롬프트"
            )

            assertEquals(LiveExperimentStatus.DRAFT, experiment.status) {
                "기본 status는 DRAFT이어야 한다"
            }
        }

        @Test
        fun `기본 trafficPercent는 10이어야 한다`() {
            val experiment = PromptExperiment(
                name = "기본값-실험",
                controlPrompt = "기준 프롬프트",
                variantPrompt = "후보 프롬프트"
            )

            assertEquals(10, experiment.trafficPercent) {
                "기본 trafficPercent는 10이어야 한다"
            }
        }
    }
}
