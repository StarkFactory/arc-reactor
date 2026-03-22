package com.arc.reactor.agent.drift

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DefaultPromptDriftDetector")
class DefaultPromptDriftDetectorTest {

    private lateinit var detector: DefaultPromptDriftDetector

    @BeforeEach
    fun setUp() {
        detector = DefaultPromptDriftDetector(
            windowSize = 200,
            deviationThreshold = 2.0,
            minSamples = 20
        )
    }

    @Nested
    @DisplayName("안정 분포 (드리프트 없음)")
    inner class StableDistribution {

        @Test
        fun `입출력 길이가 안정적이면 드리프트를 감지하지 않는다`() {
            repeat(100) {
                detector.recordInput(500)
                detector.recordOutput(1000)
            }

            val anomalies = detector.evaluate()

            anomalies.shouldBeEmpty()
        }

        @Test
        fun `소폭 변동이 있어도 임계값 이내이면 드리프트를 감지하지 않는다`() {
            repeat(50) {
                detector.recordInput(490 + it % 20)
                detector.recordOutput(990 + it % 20)
            }

            val anomalies = detector.evaluate()

            anomalies.shouldBeEmpty()
        }
    }

    @Nested
    @DisplayName("입력 길이 드리프트")
    inner class InputLengthDrift {

        @Test
        fun `입력 길이가 급격히 증가하면 드리프트를 감지한다`() {
            // 전반부: 정상 입력
            repeat(50) { detector.recordInput(100) }
            // 후반부: 급격히 긴 입력
            repeat(50) { detector.recordInput(5000) }

            val anomalies = detector.evaluate()

            val inputDrift = anomalies.find { it.type == DriftType.INPUT_LENGTH }
            inputDrift shouldBe anomalies.first { it.type == DriftType.INPUT_LENGTH }
            inputDrift!!.deviationFactor shouldBeGreaterThan 2.0
            inputDrift.message shouldContain "입력"
        }

        @Test
        fun `입력 길이가 급격히 감소해도 드리프트를 감지한다`() {
            repeat(50) { detector.recordInput(5000) }
            repeat(50) { detector.recordInput(100) }

            val anomalies = detector.evaluate()

            anomalies.any { it.type == DriftType.INPUT_LENGTH } shouldBe true
        }
    }

    @Nested
    @DisplayName("출력 길이 드리프트")
    inner class OutputLengthDrift {

        @Test
        fun `출력 길이가 급격히 감소하면 드리프트를 감지한다`() {
            repeat(50) { detector.recordOutput(2000) }
            repeat(50) { detector.recordOutput(50) }

            val anomalies = detector.evaluate()

            val outputDrift = anomalies.find { it.type == DriftType.OUTPUT_LENGTH }
            outputDrift shouldBe anomalies.first { it.type == DriftType.OUTPUT_LENGTH }
            outputDrift!!.deviationFactor shouldBeGreaterThan 2.0
            outputDrift.message shouldContain "출력"
        }

        @Test
        fun `출력 길이가 급격히 증가해도 드리프트를 감지한다`() {
            repeat(50) { detector.recordOutput(100) }
            repeat(50) { detector.recordOutput(10000) }

            val anomalies = detector.evaluate()

            anomalies.any { it.type == DriftType.OUTPUT_LENGTH } shouldBe true
        }
    }

    @Nested
    @DisplayName("최소 샘플 수")
    inner class MinimumSamples {

        @Test
        fun `최소 샘플 수 미만이면 드리프트를 평가하지 않는다`() {
            repeat(19) {
                detector.recordInput(100)
                detector.recordOutput(1000)
            }

            val anomalies = detector.evaluate()

            anomalies.shouldBeEmpty()
        }

        @Test
        fun `최소 샘플 수 도달 시 평가를 수행한다`() {
            repeat(10) { detector.recordInput(100) }
            repeat(10) { detector.recordInput(100) }

            // 안정적이므로 드리프트 없음, 하지만 평가 자체는 수행됨을 확인
            val anomalies = detector.evaluate()
            anomalies.shouldBeEmpty()

            val stats = detector.getStats()
            stats.sampleCount shouldBe 20
        }
    }

    @Nested
    @DisplayName("통계 계산")
    inner class StatsCalculation {

        @Test
        fun `입출력 길이 통계를 올바르게 계산한다`() {
            repeat(100) { detector.recordInput(500) }
            repeat(100) { detector.recordOutput(1000) }

            val stats = detector.getStats()

            stats.inputMean shouldBe 500.0
            stats.inputStdDev shouldBe 0.0
            stats.outputMean shouldBe 1000.0
            stats.outputStdDev shouldBe 0.0
            stats.sampleCount shouldBe 100
        }

        @Test
        fun `비어있으면 기본 통계를 반환한다`() {
            val stats = detector.getStats()

            stats.inputMean shouldBe 0.0
            stats.inputStdDev shouldBe 0.0
            stats.outputMean shouldBe 0.0
            stats.outputStdDev shouldBe 0.0
            stats.sampleCount shouldBe 0
        }

        @Test
        fun `표준편차가 올바르게 계산된다`() {
            // 입력: [100, 200] → 평균 150, 표준편차 50
            detector.recordInput(100)
            detector.recordInput(200)

            val stats = detector.getStats()
            stats.inputMean shouldBe 150.0
            stats.inputStdDev shouldBe 50.0
        }
    }

    @Nested
    @DisplayName("경계값 처리")
    inner class EdgeCases {

        @Test
        fun `길이 0인 입력을 올바르게 처리한다`() {
            repeat(50) { detector.recordInput(0) }
            repeat(50) { detector.recordInput(0) }

            val anomalies = detector.evaluate()

            anomalies.filter { it.type == DriftType.INPUT_LENGTH }.shouldBeEmpty()
        }

        @Test
        fun `길이 1인 입력을 올바르게 처리한다`() {
            repeat(50) { detector.recordInput(1) }
            repeat(50) { detector.recordOutput(1) }

            val anomalies = detector.evaluate()

            anomalies.shouldBeEmpty()
        }

        @Test
        fun `매우 긴 입력을 올바르게 처리한다`() {
            repeat(50) { detector.recordInput(100) }
            repeat(50) { detector.recordInput(1_000_000) }

            val anomalies = detector.evaluate()

            anomalies.any { it.type == DriftType.INPUT_LENGTH } shouldBe true
        }

        @Test
        fun `음수 길이는 무시된다`() {
            detector.recordInput(-1)
            detector.recordOutput(-5)

            val stats = detector.getStats()

            stats.sampleCount shouldBe 0
        }
    }

    @Nested
    @DisplayName("윈도우 크기 제한")
    inner class WindowSizeLimit {

        @Test
        fun `윈도우 크기를 초과하면 오래된 샘플이 제거된다`() {
            val smallDetector = DefaultPromptDriftDetector(
                windowSize = 50,
                deviationThreshold = 2.0,
                minSamples = 10
            )

            // 60개 입력 추가 (윈도우 50 초과)
            repeat(60) { smallDetector.recordInput(500) }

            val stats = smallDetector.getStats()

            stats.sampleCount shouldBe 50
        }
    }

    @Nested
    @DisplayName("DriftAnomaly 모델")
    inner class AnomalyModel {

        @Test
        fun `DriftAnomaly 데이터 클래스가 올바르게 생성된다`() {
            val anomaly = DriftAnomaly(
                type = DriftType.INPUT_LENGTH,
                currentMean = 5000.0,
                baselineMean = 500.0,
                standardDeviation = 50.0,
                deviationFactor = 90.0,
                message = "입력 길이 드리프트"
            )

            anomaly.type shouldBe DriftType.INPUT_LENGTH
            anomaly.currentMean shouldBe 5000.0
            anomaly.baselineMean shouldBe 500.0
            anomaly.standardDeviation shouldBe 50.0
            anomaly.deviationFactor shouldBe 90.0
            anomaly.message shouldBe "입력 길이 드리프트"
        }
    }

    @Nested
    @DisplayName("입출력 동시 드리프트")
    inner class CombinedDrift {

        @Test
        fun `입력과 출력 모두 드리프트되면 두 이상을 모두 반환한다`() {
            repeat(50) {
                detector.recordInput(100)
                detector.recordOutput(2000)
            }
            repeat(50) {
                detector.recordInput(5000)
                detector.recordOutput(50)
            }

            val anomalies = detector.evaluate()

            anomalies shouldHaveSize 2
            anomalies.map { it.type }.toSet() shouldBe
                setOf(DriftType.INPUT_LENGTH, DriftType.OUTPUT_LENGTH)
        }
    }

    @Nested
    @DisplayName("meanOf / stdDevOf")
    inner class HelperFunctions {

        @Test
        fun `meanOf - 빈 리스트는 0을 반환한다`() {
            DefaultPromptDriftDetector.meanOf(emptyList()) shouldBe 0.0
        }

        @Test
        fun `stdDevOf - 단일 원소는 0을 반환한다`() {
            DefaultPromptDriftDetector.stdDevOf(listOf(42.0)) shouldBe 0.0
        }

        @Test
        fun `stdDevOf - 동일 값은 0을 반환한다`() {
            DefaultPromptDriftDetector.stdDevOf(
                listOf(5.0, 5.0, 5.0)
            ) shouldBe 0.0
        }

        @Test
        fun `stdDevOf - 올바른 표준편차를 계산한다`() {
            // [2, 4, 4, 4, 5, 5, 7, 9] → 평균 5, 분산 4, 표준편차 2
            val values = listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)
            val stdDev = DefaultPromptDriftDetector.stdDevOf(values)
            stdDev shouldBeGreaterThan 1.9
            stdDev shouldBeLessThan 2.1
        }
    }
}
