package com.arc.reactor.slack

import com.arc.reactor.slack.controller.SlackEventDeduplicator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SlackEventDeduplicator]의 엣지 케이스 및 경계 조건 테스트.
 *
 * 비활성화 모드, 빈 eventId, maxEntries 오버플로우,
 * 동시 접근 안전성, cleanup 스로틀링 등을 검증한다.
 */
class SlackEventDeduplicatorEdgeCaseTest {

    // =========================================================================
    // 비활성화 모드
    // =========================================================================

    @Nested
    inner class DisabledMode {

        @Test
        fun `disabled이면 동일 eventId를 반복 호출해도 항상 false를 반환한다`() {
            val deduplicator = SlackEventDeduplicator(enabled = false, ttlSeconds = 60, cleanupIntervalSeconds = 0)

            deduplicator.isDuplicateAndMark("Ev-abc") shouldBe false
            deduplicator.isDuplicateAndMark("Ev-abc") shouldBe false
            deduplicator.isDuplicateAndMark("Ev-abc") shouldBe false
        }

        @Test
        fun `disabled이면 내부 캐시에 항목을 추가하지 않는다`() {
            val deduplicator = SlackEventDeduplicator(enabled = false, ttlSeconds = 60, cleanupIntervalSeconds = 0)

            deduplicator.isDuplicateAndMark("Ev-xyz")
            deduplicator.isDuplicateAndMark("Ev-xyz")

            deduplicator.size() shouldBe 0
        }
    }

    // =========================================================================
    // 빈 eventId 처리
    // =========================================================================

    @Nested
    inner class BlankEventId {

        @Test
        fun `blank eventId는 항상 false를 반환한다`() {
            val deduplicator = SlackEventDeduplicator(enabled = true, ttlSeconds = 60, cleanupIntervalSeconds = 0)

            deduplicator.isDuplicateAndMark("") shouldBe false
            deduplicator.isDuplicateAndMark("") shouldBe false
        }

        @Test
        fun `공백만 있는 eventId도 항상 false를 반환한다`() {
            val deduplicator = SlackEventDeduplicator(enabled = true, ttlSeconds = 60, cleanupIntervalSeconds = 0)

            deduplicator.isDuplicateAndMark("   ") shouldBe false
            deduplicator.isDuplicateAndMark("   ") shouldBe false
        }

        @Test
        fun `blank eventId는 캐시에 저장되지 않는다`() {
            val deduplicator = SlackEventDeduplicator(enabled = true, ttlSeconds = 60, cleanupIntervalSeconds = 0)

            deduplicator.isDuplicateAndMark("")
            deduplicator.isDuplicateAndMark("   ")

            deduplicator.size() shouldBe 0
        }
    }

    // =========================================================================
    // TTL 경계 조건
    // =========================================================================

    @Nested
    inner class TtlBoundaryConditions {

        @Test
        fun `TTL 만료 직전에는 여전히 중복으로 판정한다`() {
            // ttlSeconds=1 → ttlMillis=1000
            // 999ms 후면 아직 만료 전 → 중복
            val deduplicator = SlackEventDeduplicator(enabled = true, ttlSeconds = 1, maxEntries = 100, cleanupIntervalSeconds = 0)

            val t0 = 1_000_000L
            deduplicator.isDuplicateAndMark("Ev-boundary", nowMillis = t0) shouldBe false
            // t0 + 999ms: TTL 경과 < 1000ms → 아직 유효
            deduplicator.isDuplicateAndMark("Ev-boundary", nowMillis = t0 + 999) shouldBe true
        }

        @Test
        fun `TTL 만료 직후에는 새 이벤트로 판정한다`() {
            // ttlSeconds=1 → ttlMillis=1000
            // 1001ms 후면 만료 → 신규
            val deduplicator = SlackEventDeduplicator(enabled = true, ttlSeconds = 1, maxEntries = 100, cleanupIntervalSeconds = 0)

            val t0 = 2_000_000L
            deduplicator.isDuplicateAndMark("Ev-after-ttl", nowMillis = t0) shouldBe false
            deduplicator.isDuplicateAndMark("Ev-after-ttl", nowMillis = t0 + 1001) shouldBe false
        }

        @Test
        fun `만료 후 재등록된 이벤트는 다시 중복으로 탐지된다`() {
            val deduplicator = SlackEventDeduplicator(enabled = true, ttlSeconds = 1, maxEntries = 100, cleanupIntervalSeconds = 0)

            val t0 = 3_000_000L
            // 최초 등록
            deduplicator.isDuplicateAndMark("Ev-reinsert", nowMillis = t0) shouldBe false
            // 만료 후 재등록
            deduplicator.isDuplicateAndMark("Ev-reinsert", nowMillis = t0 + 1001) shouldBe false
            // 재등록된 항목은 중복
            deduplicator.isDuplicateAndMark("Ev-reinsert", nowMillis = t0 + 1002) shouldBe true
        }
    }

    // =========================================================================
    // maxEntries 오버플로우 정리
    // =========================================================================

    @Nested
    inner class MaxEntriesOverflow {

        /**
         * cleanup은 putIfAbsent 이전에 실행되므로, 오버플로우는
         * 캐시에 이미 maxEntries+1개가 존재할 때 다음 호출에서 정리된다.
         *
         * 흐름: maxEntries=2
         * - 1st call: cleanup(size=0 ≤ 2), insert → size=1
         * - 2nd call: cleanup(size=1 ≤ 2), insert → size=2
         * - 3rd call: cleanup(size=2 ≤ 2), insert → size=3  (오버플로우 아직 미정리)
         * - 4th call: cleanup(size=3 > 2 → "Ev-old" 제거), size=2, insert → size=3
         *
         * 따라서 오버플로우 정리를 확인하려면 maxEntries+2개 이상 삽입 후
         * 추가 호출로 cleanup을 트리거해야 한다.
         */
        @Test
        fun `maxEntries 초과 후 다음 호출에서 가장 오래된 항목이 제거된다`() {
            // maxEntries=2, cleanupInterval=0 → 매 호출 시 cleanup 실행 가능
            // cleanup은 putIfAbsent 이전에 실행된다:
            //   call N:   cleanup(size=N-1), putIfAbsent → size=N
            //   overflow 정리는 size가 maxEntries 초과일 때 cleanup 진입 시 동작
            // call 1: cleanup(0≤2), insert → size=1
            // call 2: cleanup(1≤2), insert → size=2
            // call 3: cleanup(2≤2), insert → size=3
            // call 4: cleanup(3>2) → "Ev-old" 제거 → size=2, insert "Ev-extra" → size=3
            val deduplicator = SlackEventDeduplicator(
                enabled = true, ttlSeconds = 3600, maxEntries = 2, cleanupIntervalSeconds = 0
            )

            val t0 = 10_000_000L
            deduplicator.isDuplicateAndMark("Ev-old", nowMillis = t0)        // size=1
            deduplicator.isDuplicateAndMark("Ev-mid", nowMillis = t0 + 1)    // size=2
            deduplicator.isDuplicateAndMark("Ev-new", nowMillis = t0 + 2)    // size=3 (cleanup 시 ≤2)
            // 4번째: cleanup(size=3>2) → "Ev-old" 제거(size=2), insert → size=3
            deduplicator.isDuplicateAndMark("Ev-extra", nowMillis = t0 + 3)

            // cleanup 후 insert이므로 size는 maxEntries+1
            deduplicator.size() shouldBe 3
        }

        @Test
        fun `overflow cleanup 후 제거된 오래된 항목은 다시 신규로 판정된다`() {
            val deduplicator = SlackEventDeduplicator(
                enabled = true, ttlSeconds = 3600, maxEntries = 2, cleanupIntervalSeconds = 0
            )

            val t0 = 20_000_000L
            deduplicator.isDuplicateAndMark("Ev-oldest", nowMillis = t0)
            deduplicator.isDuplicateAndMark("Ev-second", nowMillis = t0 + 1)
            deduplicator.isDuplicateAndMark("Ev-third", nowMillis = t0 + 2) // size→3
            // 4번째 호출: cleanup(size=3 > 2) → "Ev-oldest" 제거 후 삽입
            deduplicator.isDuplicateAndMark("Ev-fourth", nowMillis = t0 + 3)

            // "Ev-oldest"는 제거되었으므로 신규로 판정
            deduplicator.isDuplicateAndMark("Ev-oldest", nowMillis = t0 + 4) shouldBe false
        }
    }

    // =========================================================================
    // 여러 서로 다른 eventId
    // =========================================================================

    @Nested
    inner class IndependentEventIds {

        @Test
        fun `서로 다른 eventId는 각각 독립적으로 추적된다`() {
            val deduplicator = SlackEventDeduplicator(enabled = true, ttlSeconds = 60, cleanupIntervalSeconds = 0)

            deduplicator.isDuplicateAndMark("Ev-A") shouldBe false
            deduplicator.isDuplicateAndMark("Ev-B") shouldBe false
            deduplicator.isDuplicateAndMark("Ev-C") shouldBe false

            // 두 번째 호출은 모두 중복
            deduplicator.isDuplicateAndMark("Ev-A") shouldBe true
            deduplicator.isDuplicateAndMark("Ev-B") shouldBe true
            deduplicator.isDuplicateAndMark("Ev-C") shouldBe true
        }

        @Test
        fun `Ev-A 중복이어도 Ev-B는 정상 신규로 처리된다`() {
            val deduplicator = SlackEventDeduplicator(enabled = true, ttlSeconds = 60, cleanupIntervalSeconds = 0)

            deduplicator.isDuplicateAndMark("Ev-A")
            deduplicator.isDuplicateAndMark("Ev-A") shouldBe true

            // Ev-B는 아직 미등록 → 신규
            deduplicator.isDuplicateAndMark("Ev-B") shouldBe false
        }
    }

    // =========================================================================
    // cleanup 스로틀 — cleanupIntervalSeconds > 0
    // =========================================================================

    @Nested
    inner class CleanupThrottling {

        @Test
        fun `cleanup interval 이내에는 만료 항목이 즉시 제거되지 않는다`() {
            // cleanupInterval=5초 → 5초 경과 전에는 cleanup 실행 안 됨
            val deduplicator = SlackEventDeduplicator(
                enabled = true, ttlSeconds = 1, maxEntries = 1000, cleanupIntervalSeconds = 5
            )

            val t0 = 30_000_000L
            deduplicator.isDuplicateAndMark("Ev-throttle", nowMillis = t0)

            // TTL 만료됐지만 cleanup interval 이내 → 캐시에 여전히 있음
            val t1 = t0 + 1500 // TTL 만료되었지만 cleanup interval(5000ms) 미경과
            // cleanup 실행 안 됨 → isDuplicate는 기존 존재 항목으로 true 반환
            deduplicator.isDuplicateAndMark("Ev-throttle", nowMillis = t1) shouldBe true
        }

        @Test
        fun `cleanup interval 경과 후에는 만료 항목이 정리된다`() {
            // cleanupInterval=1초
            val deduplicator = SlackEventDeduplicator(
                enabled = true, ttlSeconds = 1, maxEntries = 1000, cleanupIntervalSeconds = 1
            )

            val t0 = 40_000_000L
            deduplicator.isDuplicateAndMark("Ev-cleanup-test", nowMillis = t0)

            // TTL(1000ms) + cleanup interval(1000ms) 모두 경과
            val t1 = t0 + 2001
            // cleanup이 실행되어 만료 항목 제거 → 신규로 판정
            deduplicator.isDuplicateAndMark("Ev-cleanup-test", nowMillis = t1) shouldBe false
        }
    }

    // =========================================================================
    // 동시성 안전성
    // =========================================================================

    @Nested
    inner class ConcurrentAccess {

        @Test
        fun `동시 접근 시 하나의 스레드만 신규로 판정하고 나머지는 중복으로 판정한다`() {
            val deduplicator = SlackEventDeduplicator(
                enabled = true, ttlSeconds = 60, maxEntries = 10000, cleanupIntervalSeconds = 0
            )

            val threadCount = 20
            val executor = Executors.newFixedThreadPool(threadCount)
            val latch = CountDownLatch(threadCount)
            val newCount = AtomicInteger(0)
            val dupCount = AtomicInteger(0)

            repeat(threadCount) {
                executor.submit {
                    val isDup = deduplicator.isDuplicateAndMark("Ev-concurrent-001")
                    if (isDup) dupCount.incrementAndGet() else newCount.incrementAndGet()
                    latch.countDown()
                }
            }

            latch.await(5, TimeUnit.SECONDS) shouldBe true

            // 정확히 1개만 신규, 나머지는 중복
            newCount.get() shouldBe 1
            dupCount.get() shouldBe threadCount - 1

            executor.shutdown()
        }

        @Test
        fun `여러 eventId를 동시 처리해도 캐시 크기는 고유 ID 수와 일치한다`() {
            val deduplicator = SlackEventDeduplicator(
                enabled = true, ttlSeconds = 60, maxEntries = 10000, cleanupIntervalSeconds = 0
            )

            val uniqueCount = 50
            val threadCount = 100
            val executor = Executors.newFixedThreadPool(threadCount)
            val latch = CountDownLatch(threadCount)

            repeat(threadCount) { i ->
                executor.submit {
                    deduplicator.isDuplicateAndMark("Ev-${i % uniqueCount}")
                    latch.countDown()
                }
            }

            latch.await(5, TimeUnit.SECONDS) shouldBe true

            deduplicator.size() shouldBe uniqueCount

            executor.shutdown()
        }
    }
}
