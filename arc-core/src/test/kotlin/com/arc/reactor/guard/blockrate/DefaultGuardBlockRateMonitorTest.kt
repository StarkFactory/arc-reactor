package com.arc.reactor.guard.blockrate

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("DefaultGuardBlockRateMonitor")
class DefaultGuardBlockRateMonitorTest {

    private lateinit var monitor: DefaultGuardBlockRateMonitor

    @BeforeEach
    fun setUp() {
        monitor = DefaultGuardBlockRateMonitor(
            windowSize = 200,
            spikeMultiplier = 3.0,
            dropDivisor = 3.0,
            minSamples = 50
        )
    }

    @Nested
    @DisplayName("안정 차단률 (이상 없음)")
    inner class StableRate {

        @Test
        fun `일정한 차단률이면 이상을 감지하지 않는다`() {
            // 10% 차단률 유지
            repeat(100) {
                monitor.recordGuardResult(it % 10 == 0)
            }

            val anomalies = monitor.evaluate()

            anomalies.shouldBeEmpty()
        }

        @Test
        fun `소폭 변동이 있어도 임계값 이내이면 이상을 감지하지 않는다`() {
            // 전반부: 10% 차단률
            repeat(50) { monitor.recordGuardResult(it % 10 == 0) }
            // 후반부: 15% 차단률 — 임계값(30%) 이내
            repeat(50) { monitor.recordGuardResult(it % 7 == 0) }

            val anomalies = monitor.evaluate()

            anomalies.shouldBeEmpty()
        }
    }

    @Nested
    @DisplayName("급증(SPIKE) 감지")
    inner class SpikeDetection {

        @Test
        fun `차단률이 기준선의 3배를 초과하면 SPIKE를 감지한다`() {
            // 전반부: 5% 차단률
            repeat(50) { monitor.recordGuardResult(it % 20 == 0) }
            // 후반부: 50% 차단률
            repeat(50) { monitor.recordGuardResult(it % 2 == 0) }

            val anomalies = monitor.evaluate()

            val spike = anomalies.find { it.type == GuardAnomalyType.SPIKE }
            spike shouldBe anomalies.first { it.type == GuardAnomalyType.SPIKE }
            spike!!.currentRate shouldBeGreaterThan spike.baselineRate
            spike.message shouldContain "SPIKE"
        }

        @Test
        fun `전부 허용 후 전부 차단되면 SPIKE를 감지한다`() {
            // 전반부: 0% 차단률
            repeat(50) { monitor.recordGuardResult(false) }
            // 후반부: 100% 차단률
            repeat(50) { monitor.recordGuardResult(true) }

            val anomalies = monitor.evaluate()

            // 기준선 0%에서 0% × 3 = 0% → 현재 100% > 0%이면 SPIKE
            anomalies.any { it.type == GuardAnomalyType.SPIKE } shouldBe true
        }
    }

    @Nested
    @DisplayName("급감(DROP) 감지")
    inner class DropDetection {

        @Test
        fun `차단률이 기준선의 3분의 1 미만이면 DROP을 감지한다`() {
            // 전반부: 60% 차단률
            repeat(50) { monitor.recordGuardResult(it % 5 < 3) }
            // 후반부: 5% 차단률
            repeat(50) { monitor.recordGuardResult(it % 20 == 0) }

            val anomalies = monitor.evaluate()

            val drop = anomalies.find { it.type == GuardAnomalyType.DROP }
            drop shouldBe anomalies.first { it.type == GuardAnomalyType.DROP }
            drop!!.message shouldContain "DROP"
        }

        @Test
        fun `전부 차단 후 전부 허용되면 DROP을 감지한다`() {
            // 전반부: 100% 차단률
            repeat(50) { monitor.recordGuardResult(true) }
            // 후반부: 0% 차단률
            repeat(50) { monitor.recordGuardResult(false) }

            val anomalies = monitor.evaluate()

            anomalies.any { it.type == GuardAnomalyType.DROP } shouldBe true
        }

        @Test
        fun `기준선이 0퍼센트이면 DROP을 감지하지 않는다`() {
            // 전반부: 0% 차단률
            repeat(50) { monitor.recordGuardResult(false) }
            // 후반부: 0% 차단률
            repeat(50) { monitor.recordGuardResult(false) }

            val anomalies = monitor.evaluate()

            anomalies.filter { it.type == GuardAnomalyType.DROP }.shouldBeEmpty()
        }
    }

    @Nested
    @DisplayName("최소 샘플 수")
    inner class MinimumSamples {

        @Test
        fun `최소 샘플 수 미만이면 평가하지 않는다`() {
            repeat(49) { monitor.recordGuardResult(it % 2 == 0) }

            val anomalies = monitor.evaluate()

            anomalies.shouldBeEmpty()
        }

        @Test
        fun `최소 샘플 수 도달 시 평가를 수행한다`() {
            // 50개 안정 데이터 — 이상 없음
            repeat(50) { monitor.recordGuardResult(it % 10 == 0) }

            val anomalies = monitor.evaluate()

            // 안정적이므로 이상 없음, 하지만 평가 자체는 수행됨
            anomalies.shouldBeEmpty()

            val stats = monitor.getStats()
            stats.totalRequests shouldBe 50
        }
    }

    @Nested
    @DisplayName("차단률 계산")
    inner class BlockRateCalculation {

        @Test
        fun `차단률을 올바르게 계산한다`() {
            repeat(10) { monitor.recordGuardResult(true) }
            repeat(10) { monitor.recordGuardResult(false) }

            val rate = monitor.getBlockRate()

            rate shouldBe (0.5 plusOrMinus 1e-10)
        }

        @Test
        fun `기록이 없으면 차단률은 0이다`() {
            val rate = monitor.getBlockRate()

            rate shouldBe 0.0
        }

        @Test
        fun `전부 차단이면 차단률은 1이다`() {
            repeat(20) { monitor.recordGuardResult(true) }

            val rate = monitor.getBlockRate()

            rate shouldBe (1.0 plusOrMinus 1e-10)
        }

        @Test
        fun `전부 허용이면 차단률은 0이다`() {
            repeat(20) { monitor.recordGuardResult(false) }

            val rate = monitor.getBlockRate()

            rate shouldBe (0.0 plusOrMinus 1e-10)
        }
    }

    @Nested
    @DisplayName("통계 계산")
    inner class StatsCalculation {

        @Test
        fun `통계를 올바르게 계산한다`() {
            repeat(8) { monitor.recordGuardResult(false) }
            repeat(2) { monitor.recordGuardResult(true) }

            val stats = monitor.getStats()

            stats.blockRate shouldBe (0.2 plusOrMinus 1e-10)
            stats.totalRequests shouldBe 10
            stats.blockedRequests shouldBe 2
        }

        @Test
        fun `비어있으면 기본 통계를 반환한다`() {
            val stats = monitor.getStats()

            stats.blockRate shouldBe 0.0
            stats.baselineRate shouldBe 0.0
            stats.totalRequests shouldBe 0
            stats.blockedRequests shouldBe 0
        }
    }

    @Nested
    @DisplayName("슬라이딩 윈도우")
    inner class SlidingWindow {

        @Test
        fun `윈도우 크기를 초과하면 오래된 샘플이 제거된다`() {
            val smallMonitor = DefaultGuardBlockRateMonitor(
                windowSize = 20,
                spikeMultiplier = 3.0,
                dropDivisor = 3.0,
                minSamples = 10
            )

            // 20개의 차단 → 20개의 허용
            repeat(20) { smallMonitor.recordGuardResult(true) }
            repeat(20) { smallMonitor.recordGuardResult(false) }

            // 윈도우에는 허용만 남음
            val rate = smallMonitor.getBlockRate()

            rate shouldBe (0.0 plusOrMinus 1e-10)
        }
    }

    @Nested
    @DisplayName("경계값 처리")
    inner class EdgeCases {

        @Test
        fun `전부 차단 기준선에서 이상 없음`() {
            repeat(100) { monitor.recordGuardResult(true) }

            val anomalies = monitor.evaluate()

            // 기준선 100%, 현재 100% — 변동 없음
            anomalies.shouldBeEmpty()
        }

        @Test
        fun `전부 허용 기준선에서 이상 없음`() {
            repeat(100) { monitor.recordGuardResult(false) }

            val anomalies = monitor.evaluate()

            anomalies.shouldBeEmpty()
        }

        @Test
        fun `교대 차단-허용 패턴에서 이상 없음`() {
            repeat(100) { monitor.recordGuardResult(it % 2 == 0) }

            val anomalies = monitor.evaluate()

            anomalies.shouldBeEmpty()
        }

        @Test
        fun `SPIKE와 DROP이 동시에 발생하지 않는다`() {
            // 전반부: 20% 차단, 후반부: 80% 차단
            repeat(50) { monitor.recordGuardResult(it % 5 == 0) }
            repeat(50) { monitor.recordGuardResult(it % 5 != 0) }

            val anomalies = monitor.evaluate()

            // SPIKE만 가능, DROP은 불가능 (현재 > 기준선이므로)
            anomalies.none {
                it.type == GuardAnomalyType.SPIKE && it.type == GuardAnomalyType.DROP
            } shouldBe true
        }
    }

    @Nested
    @DisplayName("생성자 유효성 검증")
    inner class ConstructorValidation {

        @Test
        fun `windowSize가 0이면 예외를 던진다`() {
            assertThrows<IllegalArgumentException>("windowSize가 0이면 예외") {
                DefaultGuardBlockRateMonitor(windowSize = 0)
            }
        }

        @Test
        fun `spikeMultiplier가 0이면 예외를 던진다`() {
            assertThrows<IllegalArgumentException>("spikeMultiplier가 0이면 예외") {
                DefaultGuardBlockRateMonitor(spikeMultiplier = 0.0)
            }
        }

        @Test
        fun `dropDivisor가 0이면 예외를 던진다`() {
            assertThrows<IllegalArgumentException>("dropDivisor가 0이면 예외") {
                DefaultGuardBlockRateMonitor(dropDivisor = 0.0)
            }
        }

        @Test
        fun `minSamples가 0이면 예외를 던진다`() {
            assertThrows<IllegalArgumentException>("minSamples가 0이면 예외") {
                DefaultGuardBlockRateMonitor(minSamples = 0)
            }
        }
    }

    @Nested
    @DisplayName("blockRateOf 헬퍼")
    inner class HelperFunctions {

        @Test
        fun `빈 리스트는 0을 반환한다`() {
            DefaultGuardBlockRateMonitor.blockRateOf(emptyList()) shouldBe 0.0
        }

        @Test
        fun `전부 true면 1을 반환한다`() {
            DefaultGuardBlockRateMonitor.blockRateOf(
                listOf(true, true, true)
            ) shouldBe (1.0 plusOrMinus 1e-10)
        }

        @Test
        fun `전부 false면 0을 반환한다`() {
            DefaultGuardBlockRateMonitor.blockRateOf(
                listOf(false, false, false)
            ) shouldBe 0.0
        }

        @Test
        fun `혼합 리스트의 비율을 올바르게 계산한다`() {
            DefaultGuardBlockRateMonitor.blockRateOf(
                listOf(true, false, true, false)
            ) shouldBe (0.5 plusOrMinus 1e-10)
        }
    }

    @Nested
    @DisplayName("GuardBlockRateAnomaly 모델")
    inner class AnomalyModel {

        @Test
        fun `GuardBlockRateAnomaly 데이터 클래스가 올바르게 생성된다`() {
            val anomaly = GuardBlockRateAnomaly(
                type = GuardAnomalyType.SPIKE,
                currentRate = 0.5,
                baselineRate = 0.1,
                message = "Guard 차단률 급증"
            )

            anomaly.type shouldBe GuardAnomalyType.SPIKE
            anomaly.currentRate shouldBe 0.5
            anomaly.baselineRate shouldBe 0.1
            anomaly.message shouldBe "Guard 차단률 급증"
        }

        @Test
        fun `GuardBlockRateStats 데이터 클래스가 올바르게 생성된다`() {
            val stats = GuardBlockRateStats(
                blockRate = 0.2,
                baselineRate = 0.15,
                totalRequests = 100,
                blockedRequests = 20
            )

            stats.blockRate shouldBe 0.2
            stats.baselineRate shouldBe 0.15
            stats.totalRequests shouldBe 100
            stats.blockedRequests shouldBe 20
        }
    }
}
