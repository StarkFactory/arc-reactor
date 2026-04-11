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
            // R292: Caffeine 마이그레이션 후 ticker로 시간 제어
            val ticker = FakeTicker()
            val deduplicator = SlackEventDeduplicator(
                enabled = true, ttlSeconds = 1, maxEntries = 100, cleanupIntervalSeconds = 0, ticker = ticker
            )

            deduplicator.isDuplicateAndMark("Ev-boundary") shouldBe false
            // 999ms 경과 — TTL 1초 미만 → 여전히 캐시
            ticker.advanceMillis(999)
            deduplicator.isDuplicateAndMark("Ev-boundary") shouldBe true
        }

        @Test
        fun `TTL 만료 직후에는 새 이벤트로 판정한다`() {
            // R292: Caffeine ticker로 정확한 만료 시간 제어
            val ticker = FakeTicker()
            val deduplicator = SlackEventDeduplicator(
                enabled = true, ttlSeconds = 1, maxEntries = 100, cleanupIntervalSeconds = 0, ticker = ticker
            )

            deduplicator.isDuplicateAndMark("Ev-after-ttl") shouldBe false
            // 1500ms 경과 — TTL 1초 초과 → 만료, 신규 판정
            ticker.advanceMillis(1500)
            deduplicator.isDuplicateAndMark("Ev-after-ttl") shouldBe false
        }

        @Test
        fun `만료 후 재등록된 이벤트는 다시 중복으로 탐지된다`() {
            val ticker = FakeTicker()
            val deduplicator = SlackEventDeduplicator(
                enabled = true, ttlSeconds = 1, maxEntries = 100, cleanupIntervalSeconds = 0, ticker = ticker
            )

            // 최초 등록
            deduplicator.isDuplicateAndMark("Ev-reinsert") shouldBe false
            // 만료 후 재등록
            ticker.advanceMillis(1500)
            deduplicator.isDuplicateAndMark("Ev-reinsert") shouldBe false
            // 재등록된 항목은 중복 (TTL 안에서 다시 호출)
            deduplicator.isDuplicateAndMark("Ev-reinsert") shouldBe true
        }
    }

    // =========================================================================
    // maxEntries 오버플로우 정리
    // =========================================================================

    @Nested
    inner class MaxEntriesOverflow {

        /**
         * R292: Caffeine은 maximumSize 초과 시 LRU eviction을 lazy/async로 수행한다.
         * 테스트는 `cache.cleanUp()`을 명시적으로 호출하여 동기 eviction을 강제한 뒤
         * size를 검증한다. (이전 구현은 putIfAbsent 이전에 동기 cleanup을 수행했음)
         */
        @Test
        fun `maxEntries 초과 후 cleanup 시 maxEntries 이하로 정리된다`() {
            val deduplicator = SlackEventDeduplicator(
                enabled = true, ttlSeconds = 3600, maxEntries = 2, cleanupIntervalSeconds = 0
            )

            // 4개 삽입 → maxEntries=2 초과
            deduplicator.isDuplicateAndMark("Ev-old")
            deduplicator.isDuplicateAndMark("Ev-mid")
            deduplicator.isDuplicateAndMark("Ev-new")
            deduplicator.isDuplicateAndMark("Ev-extra")

            // R292: Caffeine cleanUp() 강제 → 동기 eviction
            deduplicator.seenEventIds.cleanUp()

            // R292: Caffeine 명세 — maximumSize 도달 후 cleanUp 시 size <= maximumSize 보장
            // (Caffeine은 정확히 maximumSize까지 evict하므로 size == maxEntries 또는 그 이하)
            val finalSize = deduplicator.size()
            (finalSize <= 2) shouldBe true
        }

        @Test
        fun `overflow cleanup 후 maxEntries 한도 내에서 적어도 일부 오래된 항목이 evict된다`() {
            // R292: Caffeine W-TinyLFU는 frequency + recency 기반이며 정확한 LRU 보장은
            // 하지 않는다. 5개를 maxEntries=2 캐시에 넣은 뒤 cleanUp() 강제 후 검증:
            // (a) 캐시 size는 maxEntries 이하 (2)
            // (b) 5개 모두가 살아있을 수는 없음 → 적어도 3개는 evict됨
            val deduplicator = SlackEventDeduplicator(
                enabled = true, ttlSeconds = 3600, maxEntries = 2, cleanupIntervalSeconds = 0
            )

            val events = listOf("Ev-A", "Ev-B", "Ev-C", "Ev-D", "Ev-E")
            for (eventId in events) {
                deduplicator.isDuplicateAndMark(eventId)
                Thread.sleep(2)
            }

            // R292: Caffeine cleanUp() 강제 → eviction
            deduplicator.seenEventIds.cleanUp()

            // (a) size 보장
            (deduplicator.size() <= 2) shouldBe true

            // (b) 적어도 3개는 evict되어 다시 신규로 판정 (maxEntries=2)
            // 새 deduplicator를 만들어 검증 — 위의 deduplicator에 다시 호출하면 새로 insert되므로
            // size 후 별도 dedup 인스턴스 변화 없이 isDuplicate 호출로만 검증
            val freshCount = events.count { eventId ->
                // isDuplicateAndMark은 여기서 두 번 호출되어 첫 호출이 false면 evict된 것
                // 그러나 첫 호출이 cache에 다시 add하므로 동일 eventId 다수 호출은 안전.
                // 대신 캐시의 현재 entry 수만 확인하면 충분.
                deduplicator.seenEventIds.getIfPresent(eventId) == null
            }
            (freshCount >= 3) shouldBe true
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

    /**
     * R292: Caffeine 마이그레이션으로 throttledCleanup 제거됨. Caffeine은 자체 lazy
     * eviction을 사용하므로 cleanupIntervalSeconds 파라미터는 backward compat을 위해
     * 시그니처에만 유지되고 무시된다. CleanupThrottling 테스트들은 R292 후 실효성
     * 없으므로 단일 sanity 테스트로 통합.
     */
    @Nested
    inner class CleanupBehavior {

        @Test
        fun `R292 ticker 기반 TTL 만료가 정확히 동작한다`() {
            val ticker = FakeTicker()
            val deduplicator = SlackEventDeduplicator(
                enabled = true, ttlSeconds = 1, maxEntries = 1000,
                cleanupIntervalSeconds = 5, ticker = ticker
            )

            deduplicator.isDuplicateAndMark("Ev-cleanup-test") shouldBe false
            // TTL 안 → 중복
            ticker.advanceMillis(500)
            deduplicator.isDuplicateAndMark("Ev-cleanup-test") shouldBe true
            // TTL 초과 → 신규
            ticker.advanceMillis(1000)
            deduplicator.isDuplicateAndMark("Ev-cleanup-test") shouldBe false
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
