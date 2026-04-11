package com.arc.reactor.autoconfigure

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R290: [RedisProbeSupport] 단위 테스트.
 *
 * - 정상 probe 통과
 * - 예외 발생 시 false 반환
 * - 5초 timeout 동작
 * - 전용 executor cleanup (commonPool 미오염)
 */
class RedisProbeSupportTest {

    @Test
    fun `R290 정상 probe는 true를 반환한다`() {
        val invoked = AtomicBoolean(false)
        val available = RedisProbeSupport.isAvailable("test-probe") {
            invoked.set(true)
        }
        assertTrue(available) {
            "R290 fix: 예외 없이 완료된 probe는 true를 반환해야 한다"
        }
        assertTrue(invoked.get()) {
            "R290 fix: probe 람다가 실제로 실행되어야 한다"
        }
    }

    @Test
    fun `R290 예외 발생 시 false를 반환한다`() {
        val available = RedisProbeSupport.isAvailable("test-probe") {
            throw RuntimeException("simulated redis failure")
        }
        assertFalse(available) {
            "R290 fix: probe 중 예외 발생 시 false를 반환해야 한다 (silent fallback)"
        }
    }

    @Test
    fun `R290 timeout 초과 시 false를 반환한다`() {
        // probe 람다가 timeout보다 오래 걸리면 false 반환 (DEFAULT_PROBE_TIMEOUT_SECONDS = 5)
        // 테스트 안정성을 위해 6초보다 길게 sleep
        val started = System.currentTimeMillis()
        val available = RedisProbeSupport.isAvailable("test-probe") {
            // probe가 timeout보다 길게 hang
            TimeUnit.SECONDS.sleep(7)
        }
        val elapsed = System.currentTimeMillis() - started

        assertFalse(available) {
            "R290 fix: probe가 timeout보다 오래 걸리면 false를 반환해야 한다"
        }
        // timeout 후 약간의 여유를 주되 7초 sleep 전체는 기다리지 않아야 한다
        assertTrue(elapsed < 7_000) {
            "R290 fix: probe timeout(${RedisProbeSupport.DEFAULT_PROBE_TIMEOUT_SECONDS}초) 이후 " +
                "promptly 반환해야 하지만 ${elapsed}ms 소요 (probe sleep 7초 전체를 기다림)"
        }
    }

    @Test
    fun `R290 daemon thread 사용으로 application shutdown을 막지 않는다`() {
        // probe thread가 daemon인지 확인하기 위해 thread name과 daemon 플래그를 capture
        val threadName = java.util.concurrent.atomic.AtomicReference<String>()
        val isDaemon = AtomicBoolean(false)

        RedisProbeSupport.isAvailable("daemon-test") {
            val current = Thread.currentThread()
            threadName.set(current.name)
            isDaemon.set(current.isDaemon)
        }

        assertTrue(threadName.get()?.startsWith("arc-redis-probe-") == true) {
            "R290 fix: probe 스레드 이름은 'arc-redis-probe-' prefix여야 한다 (디버깅 용이성). " +
                "실제: ${threadName.get()}"
        }
        assertTrue(isDaemon.get()) {
            "R290 fix: probe 스레드는 daemon이어야 한다 (application shutdown 차단 방지)"
        }
    }

    @Test
    fun `R290 commonPool은 사용되지 않는다`() {
        // commonPool 워커 스레드는 'ForkJoinPool.commonPool-' prefix를 가진다.
        // probe가 commonPool을 사용하면 이 prefix가 capture된다.
        val threadName = java.util.concurrent.atomic.AtomicReference<String>()

        RedisProbeSupport.isAvailable("commonpool-isolation") {
            threadName.set(Thread.currentThread().name)
        }

        assertFalse(threadName.get()?.contains("commonPool") == true) {
            "R290 fix: probe는 ForkJoinPool.commonPool()을 사용하지 않아야 한다 " +
                "(공유 풀 contamination 방지). 실제 thread: ${threadName.get()}"
        }
    }
}
