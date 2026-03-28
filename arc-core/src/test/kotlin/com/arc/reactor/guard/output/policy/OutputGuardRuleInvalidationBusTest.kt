package com.arc.reactor.guard.output.policy

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * [OutputGuardRuleInvalidationBus] 종합 테스트.
 *
 * 다음을 검증한다:
 * - 초기 revision 값
 * - touch() 후 revision 단조 증가
 * - 연속 호출 시 올바른 반환값
 * - 다중 스레드 환경에서 원자성 보장
 */
class OutputGuardRuleInvalidationBusTest {

    private lateinit var bus: OutputGuardRuleInvalidationBus

    @BeforeEach
    fun setUp() {
        bus = OutputGuardRuleInvalidationBus()
    }

    // ────────────────────────────────────────────
    // 초기 상태
    // ────────────────────────────────────────────

    @Nested
    inner class 초기상태 {

        @Test
        fun `초기 revision은 0이다`() {
            assertEquals(0L, bus.currentRevision()) {
                "새로 생성된 버스의 revision은 0이어야 한다"
            }
        }

        @Test
        fun `touch 호출 전 currentRevision은 0으로 안정적이다`() {
            val r1 = bus.currentRevision()
            val r2 = bus.currentRevision()
            assertEquals(0L, r1) { "첫 번째 currentRevision()은 0이어야 한다" }
            assertEquals(r1, r2) { "touch 없이 currentRevision()은 변하지 않아야 한다" }
        }
    }

    // ────────────────────────────────────────────
    // touch() 단조 증가
    // ────────────────────────────────────────────

    @Nested
    inner class Touch단조증가 {

        @Test
        fun `첫 번째 touch는 revision을 1로 만든다`() {
            val returned = bus.touch()
            assertEquals(1L, returned) { "첫 touch()의 반환값은 1이어야 한다" }
            assertEquals(1L, bus.currentRevision()) { "touch() 후 currentRevision()은 1이어야 한다" }
        }

        @Test
        fun `touch를 세 번 호출하면 revision은 3이 된다`() {
            bus.touch()
            bus.touch()
            val returned = bus.touch()
            assertEquals(3L, returned) { "세 번째 touch()의 반환값은 3이어야 한다" }
            assertEquals(3L, bus.currentRevision()) { "세 번 touch 후 currentRevision()은 3이어야 한다" }
        }

        @Test
        fun `touch 반환값과 currentRevision은 항상 일치한다`() {
            for (i in 1..10) {
                val returned = bus.touch()
                val current = bus.currentRevision()
                assertEquals(i.toLong(), returned) { "${i}번째 touch() 반환값은 ${i}이어야 한다" }
                assertEquals(returned, current) { "touch() 반환값과 currentRevision()은 일치해야 한다" }
            }
        }

        @Test
        fun `revision은 단조 증가한다 — 이전 값보다 항상 크다`() {
            var prev = bus.currentRevision()
            for (i in 1..5) {
                val next = bus.touch()
                assertTrue(next > prev) {
                    "revision은 단조 증가해야 한다: $prev -> $next (반복 $i)"
                }
                prev = next
            }
        }
    }

    // ────────────────────────────────────────────
    // 독립된 인스턴스
    // ────────────────────────────────────────────

    @Nested
    inner class 독립인스턴스 {

        @Test
        fun `두 인스턴스는 서로 독립적으로 동작한다`() {
            val bus1 = OutputGuardRuleInvalidationBus()
            val bus2 = OutputGuardRuleInvalidationBus()

            bus1.touch()
            bus1.touch()
            bus1.touch()

            assertEquals(3L, bus1.currentRevision()) { "bus1 revision은 3이어야 한다" }
            assertEquals(0L, bus2.currentRevision()) { "bus2는 bus1에 영향을 받지 않아야 한다" }
        }

        @Test
        fun `새 인스턴스는 이전 인스턴스와 독립적으로 0에서 시작한다`() {
            repeat(5) { bus.touch() }
            val newBus = OutputGuardRuleInvalidationBus()
            assertEquals(0L, newBus.currentRevision()) {
                "새 인스턴스는 이전 인스턴스의 상태와 무관하게 0에서 시작해야 한다"
            }
        }
    }

    // ────────────────────────────────────────────
    // 동시성 — 원자성 보장
    // ────────────────────────────────────────────

    @Nested
    inner class 동시성원자성 {

        @Test
        fun `다중 스레드에서 touch를 동시 호출해도 revision은 정확히 스레드 수만큼 증가한다`() {
            val threadCount = 50
            val latch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(threadCount)
            val completed = AtomicInteger(0)

            repeat(threadCount) {
                executor.submit {
                    try {
                        latch.await()
                        bus.touch()
                    } finally {
                        completed.incrementAndGet()
                    }
                }
            }

            latch.countDown()
            executor.shutdown()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)

            assertEquals(threadCount, completed.get()) {
                "모든 $threadCount 개의 스레드가 완료되어야 한다"
            }
            assertEquals(threadCount.toLong(), bus.currentRevision()) {
                "동시 touch $threadCount 회 후 revision은 정확히 $threadCount 이어야 한다"
            }
        }

        @Test
        fun `동시 touch의 반환값은 모두 서로 다르다`() {
            val threadCount = 20
            val latch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(threadCount)
            val returnedValues = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

            repeat(threadCount) {
                executor.submit {
                    latch.await()
                    val value = bus.touch()
                    returnedValues.add(value)
                }
            }

            latch.countDown()
            executor.shutdown()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)

            assertEquals(threadCount, returnedValues.size) {
                "각 touch() 호출의 반환값은 모두 고유해야 한다 (AtomicLong 원자성 보장)"
            }
        }
    }
}
