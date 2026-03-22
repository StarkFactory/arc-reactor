package com.arc.reactor.agent.slo

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

@DisplayName("DefaultSloAlertEvaluator")
class DefaultSloAlertEvaluatorTest {

    private val clock = AtomicLong(1_000_000L)
    private lateinit var evaluator: DefaultSloAlertEvaluator

    @BeforeEach
    fun setUp() {
        evaluator = DefaultSloAlertEvaluator(
            latencyThresholdMs = 2000,
            errorRateThreshold = 0.05,
            windowSeconds = 300,
            cooldownSeconds = 600,
            nowMs = { clock.get() }
        )
    }

    @Nested
    @DisplayName("레이턴시 임계값")
    inner class LatencyThreshold {

        @Test
        fun `P95 레이턴시가 임계값을 초과하면 위반을 반환한다`() {
            // 20개 중 19개는 정상, 1개만 높은 레이턴시 (상위 5%)
            repeat(19) { evaluator.recordLatency(500) }
            evaluator.recordLatency(3000) // P95 = 3000ms > 2000ms

            val violations = evaluator.evaluate()

            violations shouldHaveSize 1
            violations[0].type shouldBe SloViolationType.LATENCY
            violations[0].currentValue shouldBeGreaterThan 2000.0
            violations[0].threshold shouldBe 2000.0
            violations[0].message shouldContain "P95"
        }

        @Test
        fun `P95 레이턴시가 임계값 이내이면 위반을 반환하지 않는다`() {
            repeat(20) { evaluator.recordLatency(1000) }

            val violations = evaluator.evaluate()

            violations.shouldBeEmpty()
        }

        @Test
        fun `샘플이 최소 수 미만이면 평가하지 않는다`() {
            repeat(4) { evaluator.recordLatency(5000) }

            val violations = evaluator.evaluate()

            violations.shouldBeEmpty()
        }
    }

    @Nested
    @DisplayName("에러율 임계값")
    inner class ErrorRateThreshold {

        @Test
        fun `에러율이 임계값을 초과하면 위반을 반환한다`() {
            repeat(9) { evaluator.recordResult(true) }
            evaluator.recordResult(false) // 에러율 10% > 5%

            val violations = evaluator.evaluate()

            val errorViolation = violations.find { it.type == SloViolationType.ERROR_RATE }
            errorViolation shouldBe violations.first { it.type == SloViolationType.ERROR_RATE }
            errorViolation!!.currentValue shouldBe 0.1
            errorViolation.threshold shouldBe 0.05
            errorViolation.message shouldContain "에러율"
        }

        @Test
        fun `에러율이 임계값 이내이면 위반을 반환하지 않는다`() {
            repeat(100) { evaluator.recordResult(true) }

            val violations = evaluator.evaluate()

            violations.filter { it.type == SloViolationType.ERROR_RATE }.shouldBeEmpty()
        }

        @Test
        fun `결과 샘플이 최소 수 미만이면 평가하지 않는다`() {
            repeat(4) { evaluator.recordResult(false) }

            val violations = evaluator.evaluate()

            violations.shouldBeEmpty()
        }
    }

    @Nested
    @DisplayName("슬라이딩 윈도우")
    inner class SlidingWindow {

        @Test
        fun `윈도우 밖의 오래된 샘플은 제거된다`() {
            // 초기에 높은 레이턴시 샘플 추가
            repeat(10) { evaluator.recordLatency(5000) }
            repeat(10) { evaluator.recordResult(false) }

            // 시간을 윈도우(300초) 이상 경과시킴
            clock.addAndGet(301_000)

            // 새로운 정상 샘플 추가
            repeat(10) { evaluator.recordLatency(500) }
            repeat(10) { evaluator.recordResult(true) }

            val violations = evaluator.evaluate()

            violations.shouldBeEmpty()
        }
    }

    @Nested
    @DisplayName("쿨다운")
    inner class Cooldown {

        @Test
        fun `쿨다운 기간 내에는 동일 유형 위반을 반복하지 않는다`() {
            repeat(20) { evaluator.recordLatency(5000) }

            val first = evaluator.evaluate()
            first shouldHaveSize 1

            // 쿨다운(600초) 이내에 다시 평가
            clock.addAndGet(100_000) // 100초 경과
            repeat(20) { evaluator.recordLatency(5000) }
            val second = evaluator.evaluate()

            second.filter { it.type == SloViolationType.LATENCY }.shouldBeEmpty()
        }

        @Test
        fun `쿨다운 기간 경과 후에는 다시 위반을 반환한다`() {
            repeat(20) { evaluator.recordLatency(5000) }

            val first = evaluator.evaluate()
            first shouldHaveSize 1

            // 쿨다운(600초) 경과
            clock.addAndGet(601_000)
            repeat(20) { evaluator.recordLatency(5000) }
            val second = evaluator.evaluate()

            second.filter { it.type == SloViolationType.LATENCY } shouldHaveSize 1
        }
    }

    @Nested
    @DisplayName("복합 위반")
    inner class CombinedViolations {

        @Test
        fun `레이턴시와 에러율 동시 위반 시 두 위반을 모두 반환한다`() {
            repeat(20) { evaluator.recordLatency(5000) }
            repeat(5) { evaluator.recordResult(true) }
            repeat(5) { evaluator.recordResult(false) } // 에러율 50%

            val violations = evaluator.evaluate()

            violations shouldHaveSize 2
            violations.map { it.type }.toSet() shouldBe
                setOf(SloViolationType.LATENCY, SloViolationType.ERROR_RATE)
        }
    }

    @Nested
    @DisplayName("SloViolation")
    inner class ViolationModel {

        @Test
        fun `SloViolation 데이터 클래스가 올바르게 생성된다`() {
            val violation = SloViolation(
                type = SloViolationType.LATENCY,
                currentValue = 2500.0,
                threshold = 2000.0,
                message = "P95 레이턴시 초과"
            )

            violation.type shouldBe SloViolationType.LATENCY
            violation.currentValue shouldBe 2500.0
            violation.threshold shouldBe 2000.0
            violation.message shouldBe "P95 레이턴시 초과"
        }
    }
}
