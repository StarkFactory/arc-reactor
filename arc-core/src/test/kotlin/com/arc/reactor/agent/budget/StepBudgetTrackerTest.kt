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
}
