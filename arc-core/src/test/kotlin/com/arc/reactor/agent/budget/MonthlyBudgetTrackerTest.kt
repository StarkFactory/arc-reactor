package com.arc.reactor.agent.budget

import com.arc.reactor.agent.budget.MonthlyBudgetTracker.MonthlyBudgetStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [MonthlyBudgetTracker] 단위 테스트.
 */
class MonthlyBudgetTrackerTest {

    @Nested
    inner class 무제한_모드 {

        @Test
        fun `monthlyLimitUsd가 0이면 항상 OK를 반환한다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 0.0)

            val status = tracker.recordCost("tenant-1", 9999.0)

            assertEquals(MonthlyBudgetStatus.OK, status) {
                "한도가 0(무제한)이면 어떤 비용을 기록해도 OK여야 한다"
            }
        }

        @Test
        fun `monthlyLimitUsd가 음수여도 OK를 반환한다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = -1.0)

            val status = tracker.recordCost("tenant-1", 100.0)

            assertEquals(MonthlyBudgetStatus.OK, status) {
                "한도가 0 이하이면 무제한으로 처리하여 OK여야 한다"
            }
        }
    }

    @Nested
    inner class OK_상태 {

        @Test
        fun `비용이 한도의 경고 임계치 미만이면 OK를 반환한다`() {
            // 한도 100, 경고 80% → 경고 기준 80
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 100.0, warningPercent = 80)

            val status = tracker.recordCost("tenant-1", 50.0)

            assertEquals(MonthlyBudgetStatus.OK, status) {
                "누적 비용 50이 경고 기준 80 미만이므로 OK여야 한다"
            }
        }

        @Test
        fun `처음 기록하는 테넌트는 OK를 반환한다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 100.0)

            val status = tracker.recordCost("new-tenant", 1.0)

            assertEquals(MonthlyBudgetStatus.OK, status) {
                "신규 테넌트 첫 기록은 OK여야 한다"
            }
        }
    }

    @Nested
    inner class WARNING_상태 {

        @Test
        fun `누적 비용이 경고 임계치에 정확히 도달하면 WARNING을 반환한다`() {
            // 한도 100, 경고 80% → 80에서 WARNING
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 100.0, warningPercent = 80)
            tracker.recordCost("tenant-1", 79.0)

            val status = tracker.recordCost("tenant-1", 1.0) // 합계 = 80.0

            assertEquals(MonthlyBudgetStatus.WARNING, status) {
                "누적 비용 80.0이 경고 기준 80%에 도달하면 WARNING이어야 한다"
            }
        }

        @Test
        fun `누적 비용이 경고 임계치를 초과하고 한도 미만이면 WARNING을 반환한다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 100.0, warningPercent = 80)
            tracker.recordCost("tenant-1", 90.0) // 90% → WARNING

            val status = tracker.recordCost("tenant-1", 0.0) // 추가 0 → 합계 90

            assertEquals(MonthlyBudgetStatus.WARNING, status) {
                "누적 비용 90이 경고(80%) 초과이고 한도(100) 미만이면 WARNING이어야 한다"
            }
        }

        @Test
        fun `커스텀 경고 임계치가 올바르게 적용된다`() {
            // 한도 200, 경고 50% → 100에서 WARNING
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 200.0, warningPercent = 50)
            tracker.recordCost("tenant-1", 99.0)

            val status = tracker.recordCost("tenant-1", 1.0) // 합계 = 100.0 (50%)

            assertEquals(MonthlyBudgetStatus.WARNING, status) {
                "경고 기준 50%에 도달하면 WARNING이어야 한다"
            }
        }
    }

    @Nested
    inner class EXCEEDED_상태 {

        @Test
        fun `누적 비용이 한도에 정확히 도달하면 EXCEEDED를 반환한다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 100.0)
            tracker.recordCost("tenant-1", 99.0)

            val status = tracker.recordCost("tenant-1", 1.0) // 합계 = 100.0

            assertEquals(MonthlyBudgetStatus.EXCEEDED, status) {
                "누적 비용이 한도에 정확히 도달하면 EXCEEDED여야 한다"
            }
        }

        @Test
        fun `누적 비용이 한도를 초과하면 EXCEEDED를 반환한다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 100.0)
            tracker.recordCost("tenant-1", 150.0)

            val status = tracker.recordCost("tenant-1", 0.0) // 이미 초과

            assertEquals(MonthlyBudgetStatus.EXCEEDED, status) {
                "누적 비용이 한도를 초과하면 EXCEEDED여야 한다"
            }
        }

        @Test
        fun `단일 요청으로 한도를 즉시 초과해도 EXCEEDED를 반환한다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 50.0)

            val status = tracker.recordCost("tenant-1", 100.0)

            assertEquals(MonthlyBudgetStatus.EXCEEDED, status) {
                "첫 요청으로 한도를 즉시 초과하면 EXCEEDED여야 한다"
            }
        }
    }

    @Nested
    inner class 멀티테넌트_격리 {

        @Test
        fun `서로 다른 테넌트의 비용이 독립적으로 추적된다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 100.0)
            tracker.recordCost("tenant-A", 90.0)

            val statusB = tracker.recordCost("tenant-B", 5.0)

            assertEquals(MonthlyBudgetStatus.OK, statusB) {
                "tenant-A의 비용이 tenant-B의 상태에 영향을 주어서는 안 된다"
            }
        }

        @Test
        fun `한 테넌트가 EXCEEDED여도 다른 테넌트는 OK일 수 있다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 100.0)
            tracker.recordCost("tenant-A", 200.0) // EXCEEDED

            val statusB = tracker.recordCost("tenant-B", 1.0)

            assertEquals(MonthlyBudgetStatus.OK, statusB) {
                "tenant-A EXCEEDED 상태가 tenant-B의 OK 상태에 영향을 주어서는 안 된다"
            }
        }

        @Test
        fun `각 테넌트의 누적 비용을 별도로 조회할 수 있다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 100.0)
            tracker.recordCost("tenant-A", 30.0)
            tracker.recordCost("tenant-A", 20.0)
            tracker.recordCost("tenant-B", 10.0)

            val costA = tracker.getCurrentCost("tenant-A")
            val costB = tracker.getCurrentCost("tenant-B")

            assertEquals(50.0, costA, 0.001) { "tenant-A 누적 비용이 50.0이어야 한다" }
            assertEquals(10.0, costB, 0.001) { "tenant-B 누적 비용이 10.0이어야 한다" }
        }
    }

    @Nested
    inner class getCurrentCost {

        @Test
        fun `기록이 없는 테넌트의 현재 비용은 0이다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 100.0)

            val cost = tracker.getCurrentCost("unknown-tenant")

            assertEquals(0.0, cost, 0.001) { "기록 없는 테넌트의 비용은 0.0이어야 한다" }
        }

        @Test
        fun `여러 번 기록 후 누적 비용이 합산된다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 1000.0)
            tracker.recordCost("tenant-1", 10.0)
            tracker.recordCost("tenant-1", 20.0)
            tracker.recordCost("tenant-1", 30.0)

            val cost = tracker.getCurrentCost("tenant-1")

            assertEquals(60.0, cost, 0.001) { "세 번 기록된 비용의 합이 60.0이어야 한다" }
        }
    }

    @Nested
    inner class 경계값테스트 {

        @Test
        fun `비용 0을 기록해도 상태가 정상적으로 반환된다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 100.0)

            val status = tracker.recordCost("tenant-1", 0.0)

            assertEquals(MonthlyBudgetStatus.OK, status) { "비용 0 기록은 OK여야 한다" }
        }

        @Test
        fun `비용이 경고 기준 직전이면 OK를 반환한다`() {
            // 한도 100, 경고 80% → 기준 80 미만인 79.9999는 OK
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 100.0, warningPercent = 80)

            val status = tracker.recordCost("tenant-1", 79.9999)

            assertEquals(MonthlyBudgetStatus.OK, status) {
                "경고 기준(80.0) 직전 79.9999는 OK여야 한다"
            }
        }

        @Test
        fun `비용이 한도 직전이면 WARNING을 반환한다`() {
            // 한도 100, 경고 80% → 99.9999는 WARNING(80% 이상, 100% 미만)
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 100.0, warningPercent = 80)

            val status = tracker.recordCost("tenant-1", 99.9999)

            assertEquals(MonthlyBudgetStatus.WARNING, status) {
                "한도 직전 99.9999는 WARNING이어야 한다"
            }
        }

        @Test
        fun `소수 비용도 정확히 누적된다`() {
            val tracker = MonthlyBudgetTracker(monthlyLimitUsd = 1.0)
            repeat(10) { tracker.recordCost("tenant-1", 0.1) }

            val cost = tracker.getCurrentCost("tenant-1")

            // DoubleAdder 누적 오차 허용
            assertTrue(cost >= 0.99 && cost <= 1.01) {
                "0.1씩 10회 누적한 비용이 ~1.0이어야 한다, 실제: $cost"
            }
        }
    }
}
