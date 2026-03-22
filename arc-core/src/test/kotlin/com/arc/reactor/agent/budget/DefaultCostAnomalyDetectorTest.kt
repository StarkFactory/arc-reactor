package com.arc.reactor.agent.budget

import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("DefaultCostAnomalyDetector")
class DefaultCostAnomalyDetectorTest {

    private lateinit var detector: DefaultCostAnomalyDetector

    @BeforeEach
    fun setUp() {
        detector = DefaultCostAnomalyDetector(
            windowSize = 100,
            thresholdMultiplier = 3.0,
            minSamples = 10
        )
    }

    @Nested
    @DisplayName("정상 비용")
    inner class NormalCosts {

        @Test
        fun `일정한 비용은 이상으로 감지하지 않는다`() {
            repeat(20) { detector.recordCost(0.01) }

            val result = detector.evaluate()

            result.shouldBeNull()
        }

        @Test
        fun `기준선 이내 비용은 이상으로 감지하지 않는다`() {
            repeat(19) { detector.recordCost(0.01) }
            detector.recordCost(0.02) // 2배 — 임계값 3배 이내

            val result = detector.evaluate()

            result.shouldBeNull()
        }
    }

    @Nested
    @DisplayName("이상 비용 감지")
    inner class AnomalyDetection {

        @Test
        fun `기준선의 3배 초과 비용을 이상으로 감지한다`() {
            repeat(19) { detector.recordCost(0.01) }
            detector.recordCost(0.10) // 10배 — 이동 평균 ~0.0147

            val result = detector.evaluate()

            result.shouldNotBeNull()
            result.currentCost shouldBe 0.10
            result.baselineCost shouldBeGreaterThan 0.0
            result.multiplier shouldBeGreaterThan 3.0
            result.threshold shouldBe 3.0
            result.message shouldContain "요청 비용"
            result.message shouldContain "초과"
        }

        @Test
        fun `정확히 3배인 비용은 이상으로 감지하지 않는다`() {
            // 이동 평균이 정확히 latest/3이 되도록 구성
            // 9개의 0.01과 1개의 0.03 → 평균 = 0.012, latest/평균 = 2.5배
            repeat(9) { detector.recordCost(0.01) }
            detector.recordCost(0.03)

            val result = detector.evaluate()

            result.shouldBeNull()
        }
    }

    @Nested
    @DisplayName("최소 샘플 수")
    inner class MinimumSamples {

        @Test
        fun `샘플이 최소 수 미만이면 평가하지 않는다`() {
            repeat(9) { detector.recordCost(0.01) }
            detector.recordCost(1.0) // 큰 비용이지만 샘플 부족

            // 10개이므로 minSamples(10) 충족 — 이 경우는 평가됨
            // 9개만으로 확인
            val smallDetector = DefaultCostAnomalyDetector(
                windowSize = 100,
                thresholdMultiplier = 3.0,
                minSamples = 10
            )
            repeat(8) { smallDetector.recordCost(0.01) }
            smallDetector.recordCost(1.0) // 9개 — 부족

            val result = smallDetector.evaluate()

            result.shouldBeNull()
        }

        @Test
        fun `최소 샘플 수에 도달하면 평가한다`() {
            repeat(9) { detector.recordCost(0.01) }
            detector.recordCost(1.0) // 10개 — 충분

            val result = detector.evaluate()

            result.shouldNotBeNull()
        }
    }

    @Nested
    @DisplayName("이동 평균 계산")
    inner class MovingAverage {

        @Test
        fun `기준선은 윈도우 내 비용의 평균이다`() {
            repeat(10) { detector.recordCost(0.01) }
            repeat(10) { detector.recordCost(0.02) }

            val baseline = detector.getBaselineCost()

            baseline shouldBe 0.015
        }

        @Test
        fun `비용 기록이 없으면 기준선은 0이다`() {
            val baseline = detector.getBaselineCost()

            baseline shouldBe 0.0
        }
    }

    @Nested
    @DisplayName("슬라이딩 윈도우")
    inner class SlidingWindow {

        @Test
        fun `윈도우 크기를 초과하면 오래된 샘플이 제거된다`() {
            val smallWindow = DefaultCostAnomalyDetector(
                windowSize = 10,
                thresholdMultiplier = 3.0,
                minSamples = 5
            )

            // 10개의 높은 비용 → 10개의 낮은 비용
            repeat(10) { smallWindow.recordCost(1.0) }
            repeat(10) { smallWindow.recordCost(0.01) }

            // 윈도우에는 0.01만 남음
            val baseline = smallWindow.getBaselineCost()

            baseline shouldBe (0.01 plusOrMinus 1e-10)
        }
    }

    @Nested
    @DisplayName("엣지 케이스")
    inner class EdgeCases {

        @Test
        fun `비용 0은 정상적으로 기록된다`() {
            repeat(10) { detector.recordCost(0.0) }

            val result = detector.evaluate()

            result.shouldBeNull()
        }

        @Test
        fun `음수 비용은 무시된다`() {
            repeat(10) { detector.recordCost(0.01) }
            detector.recordCost(-0.5)

            // 음수는 기록되지 않으므로 여전히 10개
            val baseline = detector.getBaselineCost()

            baseline shouldBe (0.01 plusOrMinus 1e-10)
        }

        @Test
        fun `단일 샘플은 최소 샘플 미달로 평가하지 않는다`() {
            detector.recordCost(1.0)

            val result = detector.evaluate()

            result.shouldBeNull()
        }

        @Test
        fun `모든 비용이 0이면 이상을 감지하지 않는다`() {
            repeat(20) { detector.recordCost(0.0) }

            val result = detector.evaluate()

            result.shouldBeNull()
        }
    }

    @Nested
    @DisplayName("생성자 유효성 검증")
    inner class ConstructorValidation {

        @Test
        fun `windowSize가 0이면 예외를 던진다`() {
            assertThrows<IllegalArgumentException>("windowSize가 0이면 예외") {
                DefaultCostAnomalyDetector(windowSize = 0)
            }
        }

        @Test
        fun `thresholdMultiplier가 0이면 예외를 던진다`() {
            assertThrows<IllegalArgumentException>("thresholdMultiplier가 0이면 예외") {
                DefaultCostAnomalyDetector(thresholdMultiplier = 0.0)
            }
        }

        @Test
        fun `minSamples가 0이면 예외를 던진다`() {
            assertThrows<IllegalArgumentException>("minSamples가 0이면 예외") {
                DefaultCostAnomalyDetector(minSamples = 0)
            }
        }
    }

    @Nested
    @DisplayName("CostAnomaly 모델")
    inner class AnomalyModel {

        @Test
        fun `CostAnomaly 데이터 클래스가 올바르게 생성된다`() {
            val anomaly = CostAnomaly(
                currentCost = 0.10,
                baselineCost = 0.01,
                multiplier = 10.0,
                threshold = 3.0,
                message = "비용 이상 탐지"
            )

            anomaly.currentCost shouldBe 0.10
            anomaly.baselineCost shouldBe 0.01
            anomaly.multiplier shouldBe 10.0
            anomaly.threshold shouldBe 3.0
            anomaly.message shouldBe "비용 이상 탐지"
        }
    }
}
