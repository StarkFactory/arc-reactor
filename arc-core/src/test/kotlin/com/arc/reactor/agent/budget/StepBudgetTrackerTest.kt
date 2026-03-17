package com.arc.reactor.agent.budget

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * StepBudgetTracker 단위 테스트.
 *
 * 토큰 예산 추적의 핵심 동작을 검증한다.
 */
class StepBudgetTrackerTest {

    // --- 기본 동작 ---

    @Test
    fun `초기 상태에서 소비 토큰은 0이어야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 1000)

        assertEquals(0, tracker.totalConsumed(), "초기 소비 토큰은 0이어야 한다")
        assertEquals(1000, tracker.remaining(), "초기 잔여 토큰은 maxTokens와 같아야 한다")
        assertFalse(tracker.isExhausted(), "초기 상태에서 예산이 소진되면 안 된다")
    }

    @Test
    fun `토큰을 기록하면 누적되어야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 1000)

        tracker.record("step-1", inputTokens = 100, outputTokens = 50)
        tracker.record("step-2", inputTokens = 200, outputTokens = 100)

        assertEquals(450, tracker.totalConsumed(), "누적 토큰이 정확해야 한다")
        assertEquals(550, tracker.remaining(), "잔여 토큰이 정확해야 한다")
    }

    // --- OK 상태 ---

    @Test
    fun `소프트 리밋 미만이면 OK를 반환해야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 1000, softLimitPercent = 80)

        val status = tracker.record("llm-call", inputTokens = 300, outputTokens = 100)

        assertEquals(BudgetStatus.OK, status, "소프트 리밋(800) 미만이면 OK여야 한다")
    }

    // --- SOFT_LIMIT 상태 ---

    @Test
    fun `소프트 리밋에 도달하면 SOFT_LIMIT을 반환해야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 1000, softLimitPercent = 80)

        // 800 토큰 = 80% → 소프트 리밋
        val status = tracker.record("llm-call", inputTokens = 500, outputTokens = 300)

        assertEquals(BudgetStatus.SOFT_LIMIT, status, "80% 도달 시 SOFT_LIMIT이어야 한다")
        assertFalse(tracker.isExhausted(), "소프트 리밋에서는 소진되면 안 된다")
    }

    @Test
    fun `소프트 리밋 초과 후 하드 리밋 미만이면 SOFT_LIMIT을 유지해야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 1000, softLimitPercent = 80)

        tracker.record("step-1", inputTokens = 400, outputTokens = 200) // 600 → OK
        val status = tracker.record("step-2", inputTokens = 200, outputTokens = 100) // 900 → SOFT_LIMIT

        assertEquals(BudgetStatus.SOFT_LIMIT, status, "하드 리밋 미만이면 SOFT_LIMIT이어야 한다")
    }

    // --- EXHAUSTED 상태 ---

    @Test
    fun `하드 리밋에 도달하면 EXHAUSTED를 반환해야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 1000, softLimitPercent = 80)

        val status = tracker.record("llm-call", inputTokens = 600, outputTokens = 400)

        assertEquals(BudgetStatus.EXHAUSTED, status, "100% 도달 시 EXHAUSTED여야 한다")
        assertTrue(tracker.isExhausted(), "하드 리밋 도달 시 isExhausted()=true여야 한다")
        assertEquals(0, tracker.remaining(), "잔여 토큰은 0이어야 한다")
    }

    @Test
    fun `초과 소비해도 remaining은 0 이상이어야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 100, softLimitPercent = 80)

        tracker.record("overflow", inputTokens = 500, outputTokens = 500)

        assertEquals(0, tracker.remaining(), "초과 소비 시에도 remaining은 0이어야 한다")
        assertEquals(1000, tracker.totalConsumed(), "실제 소비량은 정확해야 한다")
    }

    // --- 히스토리 ---

    @Test
    fun `히스토리에 모든 단계가 기록되어야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 1000, softLimitPercent = 80)

        tracker.record("llm-1", inputTokens = 100, outputTokens = 50)
        tracker.record("tool-search", inputTokens = 0, outputTokens = 200)
        tracker.record("llm-2", inputTokens = 300, outputTokens = 150)

        val history = tracker.history()
        assertEquals(3, history.size, "3개의 단계가 기록되어야 한다")
        assertEquals("llm-1", history[0].step, "첫 번째 단계 이름이 정확해야 한다")
        assertEquals(150, history[0].cumulativeTokens, "첫 번째 단계의 누적 토큰이 정확해야 한다")
        assertEquals(350, history[1].cumulativeTokens, "두 번째 단계의 누적 토큰이 정확해야 한다")
        assertEquals(800, history[2].cumulativeTokens, "세 번째 단계의 누적 토큰이 정확해야 한다")
    }

    @Test
    fun `히스토리는 불변 복사본이어야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 1000)
        tracker.record("step-1", inputTokens = 100, outputTokens = 50)

        val history1 = tracker.history()
        tracker.record("step-2", inputTokens = 200, outputTokens = 100)
        val history2 = tracker.history()

        assertEquals(1, history1.size, "첫 번째 스냅샷은 변경되지 않아야 한다")
        assertEquals(2, history2.size, "두 번째 스냅샷은 새 단계를 포함해야 한다")
    }

    // --- 입력 검증 ---

    @Test
    fun `maxTokens가 0 이하이면 예외를 던져야 한다`() {
        val ex = assertThrows<IllegalArgumentException>("maxTokens=0은 예외여야 한다") {
            StepBudgetTracker(maxTokens = 0)
        }
        assertTrue(ex.message!!.contains("양수"), "에러 메시지에 '양수'가 포함되어야 한다")
    }

    @Test
    fun `softLimitPercent가 범위 밖이면 예외를 던져야 한다`() {
        assertThrows<IllegalArgumentException>("softLimitPercent=0은 예외여야 한다") {
            StepBudgetTracker(maxTokens = 1000, softLimitPercent = 0)
        }
        assertThrows<IllegalArgumentException>("softLimitPercent=100은 예외여야 한다") {
            StepBudgetTracker(maxTokens = 1000, softLimitPercent = 100)
        }
    }

    @Test
    fun `음수 토큰은 예외를 던져야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 1000)

        assertThrows<IllegalArgumentException>("음수 inputTokens는 예외여야 한다") {
            tracker.record("bad", inputTokens = -1, outputTokens = 0)
        }
        assertThrows<IllegalArgumentException>("음수 outputTokens는 예외여야 한다") {
            tracker.record("bad", inputTokens = 0, outputTokens = -1)
        }
    }

    // --- 경계값 ---

    @Test
    fun `소프트 리밋 경계값에서 정확히 SOFT_LIMIT을 반환해야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 100, softLimitPercent = 80)

        // 정확히 80 토큰 = 80%
        val status = tracker.record("exact", inputTokens = 80, outputTokens = 0)

        assertEquals(BudgetStatus.SOFT_LIMIT, status, "정확히 80%일 때 SOFT_LIMIT이어야 한다")
    }

    @Test
    fun `하드 리밋 경계값에서 정확히 EXHAUSTED를 반환해야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 100, softLimitPercent = 80)

        // 정확히 100 토큰 = 100%
        val status = tracker.record("exact", inputTokens = 100, outputTokens = 0)

        assertEquals(BudgetStatus.EXHAUSTED, status, "정확히 100%일 때 EXHAUSTED여야 한다")
    }

    // --- 엣지 케이스: 소프트 리밋 정확한 경계 ---

    @Test
    fun `소프트 리밋 바로 한 토큰 미만이면 OK를 반환해야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 100, softLimitPercent = 80)

        // 79 토큰 = 79% → OK
        val status = tracker.record("just-below", inputTokens = 79, outputTokens = 0)

        assertEquals(BudgetStatus.OK, status, "소프트 리밋(80) 미만 79 토큰이면 OK여야 한다")
    }

    @Test
    fun `소프트 리밋 바로 한 토큰 초과이면 SOFT_LIMIT을 반환해야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 100, softLimitPercent = 80)

        // 81 토큰 = 81% → SOFT_LIMIT
        val status = tracker.record("just-above", inputTokens = 81, outputTokens = 0)

        assertEquals(BudgetStatus.SOFT_LIMIT, status, "소프트 리밋(80) 초과 81 토큰이면 SOFT_LIMIT이어야 한다")
    }

    // --- 엣지 케이스: 동시 접근 (스레드 안전하지 않지만 단일 스레드에서 누적 정확성) ---

    @Test
    fun `0 토큰 기록은 상태에 영향을 주지 않아야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 100, softLimitPercent = 80)

        val status = tracker.record("zero-cost-tool", inputTokens = 0, outputTokens = 0)

        assertEquals(BudgetStatus.OK, status, "0 토큰 기록은 OK 상태여야 한다")
        assertEquals(0, tracker.totalConsumed(), "0 토큰 기록 후 소비량은 0이어야 한다")
    }

    @Test
    fun `여러 번 EXHAUSTED 이후에도 상태가 EXHAUSTED로 유지되어야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 100, softLimitPercent = 80)

        tracker.record("step-1", inputTokens = 100, outputTokens = 0) // EXHAUSTED
        val status = tracker.record("step-2", inputTokens = 10, outputTokens = 0) // 여전히 EXHAUSTED

        assertEquals(BudgetStatus.EXHAUSTED, status, "예산 소진 이후 추가 기록도 EXHAUSTED여야 한다")
        assertTrue(tracker.isExhausted(), "isExhausted()는 계속 true여야 한다")
    }

    @Test
    fun `단계 이름이 빈 문자열이어도 기록되어야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 1000)

        val status = tracker.record("", inputTokens = 100, outputTokens = 50)

        assertEquals(BudgetStatus.OK, status, "빈 단계 이름도 기록되어야 한다")
        val history = tracker.history()
        assertEquals(1, history.size, "빈 이름이어도 히스토리에 1개 기록되어야 한다")
        assertEquals("", history[0].step, "단계 이름이 빈 문자열로 저장되어야 한다")
    }

    @Test
    fun `히스토리의 StepRecord에 상태가 정확히 기록되어야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 100, softLimitPercent = 80)

        tracker.record("ok-step", inputTokens = 50, outputTokens = 20)       // 70 → OK
        tracker.record("soft-step", inputTokens = 10, outputTokens = 5)      // 85 → SOFT_LIMIT
        tracker.record("exhausted-step", inputTokens = 10, outputTokens = 10) // 105 → EXHAUSTED

        val history = tracker.history()
        assertEquals(3, history.size, "3개 단계가 기록되어야 한다")
        assertEquals(BudgetStatus.OK, history[0].status, "첫 번째 단계는 OK여야 한다")
        assertEquals(BudgetStatus.SOFT_LIMIT, history[1].status, "두 번째 단계는 SOFT_LIMIT이어야 한다")
        assertEquals(BudgetStatus.EXHAUSTED, history[2].status, "세 번째 단계는 EXHAUSTED여야 한다")
    }

    @Test
    fun `maxTokens가 음수이면 예외를 던져야 한다`() {
        val ex = assertThrows<IllegalArgumentException>("maxTokens=-1은 예외여야 한다") {
            StepBudgetTracker(maxTokens = -1)
        }
        assertTrue(ex.message!!.contains("양수"), "에러 메시지에 '양수'가 포함되어야 한다")
    }

    @Test
    fun `softLimitPercent=1은 허용되어야 한다`() {
        // maxTokens=1000, softLimitPercent=1 → softLimitTokens = 10
        val trackerBelow = StepBudgetTracker(maxTokens = 1000, softLimitPercent = 1)
        val trackerAt = StepBudgetTracker(maxTokens = 1000, softLimitPercent = 1)

        val statusBelow = trackerBelow.record("below", inputTokens = 9, outputTokens = 0)
        val statusAt = trackerAt.record("at", inputTokens = 10, outputTokens = 0)

        assertEquals(BudgetStatus.OK, statusBelow, "softLimitPercent=1에서 9토큰(임계값 미만)이면 OK여야 한다")
        assertEquals(BudgetStatus.SOFT_LIMIT, statusAt, "softLimitPercent=1에서 10토큰(임계값 정확)이면 SOFT_LIMIT이어야 한다")
    }

    @Test
    fun `softLimitPercent=99는 허용되어야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 100, softLimitPercent = 99)

        val status = tracker.record("step", inputTokens = 98, outputTokens = 0)

        assertEquals(BudgetStatus.OK, status, "softLimitPercent=99에서 98토큰이면 OK여야 한다")
    }

    // --- recordToolOutput ---

    @Test
    fun `recordToolOutput은 입력 토큰으로 기록해야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 1000, softLimitPercent = 80)

        val status = tracker.recordToolOutput("tool-output-search", toolOutputTokens = 300)

        assertEquals(BudgetStatus.OK, status, "300 토큰은 소프트 리밋(800) 미만이므로 OK여야 한다")
        assertEquals(300, tracker.totalConsumed(), "도구 출력 토큰이 누적되어야 한다")

        val history = tracker.history()
        assertEquals(1, history.size, "히스토리에 1개 기록되어야 한다")
        assertEquals(300, history[0].inputTokens, "도구 출력 토큰은 inputTokens로 기록되어야 한다")
        assertEquals(0, history[0].outputTokens, "outputTokens는 0이어야 한다")
    }

    @Test
    fun `recordToolOutput과 record가 함께 누적되어야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 1000, softLimitPercent = 80)

        tracker.record("llm-call-1", inputTokens = 200, outputTokens = 100) // 300
        tracker.recordToolOutput("tool-output-search", toolOutputTokens = 400) // 700
        val status = tracker.record("llm-call-2", inputTokens = 200, outputTokens = 50) // 950

        assertEquals(BudgetStatus.SOFT_LIMIT, status, "950 토큰은 소프트 리밋(800) 이상이므로 SOFT_LIMIT이어야 한다")
        assertEquals(950, tracker.totalConsumed(), "모든 토큰이 누적되어야 한다")
    }

    @Test
    fun `recordToolOutput으로 예산 소진 시 EXHAUSTED를 반환해야 한다`() {
        val tracker = StepBudgetTracker(maxTokens = 500, softLimitPercent = 80)

        tracker.record("llm-call-1", inputTokens = 200, outputTokens = 100) // 300
        val status = tracker.recordToolOutput("tool-output-large", toolOutputTokens = 300) // 600

        assertEquals(BudgetStatus.EXHAUSTED, status, "600 > 500이므로 EXHAUSTED여야 한다")
        assertTrue(tracker.isExhausted(), "예산이 소진되어야 한다")
    }
}
