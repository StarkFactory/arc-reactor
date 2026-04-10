package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.RetryProperties
import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.ExecutionStage
import com.arc.reactor.agent.metrics.MicrometerEvaluationMetricsCollector
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * RetryExecutor에 대한 테스트.
 *
 * 재시도 실행기의 기본 동작을 검증합니다.
 */
class RetryExecutorTest {

    @Test
    fun `retry transient failures and eventually succeed해야 한다`() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0
        val executor = RetryExecutor(
            retry = RetryProperties(
                maxAttempts = 3,
                initialDelayMs = 100,
                multiplier = 2.0,
                maxDelayMs = 500
            ),
            circuitBreaker = null,
            isTransientError = { it is IllegalStateException },
            delayFn = { delays.add(it) },
            randomFn = { 0.5 }
        )

        val result = executor.execute {
            attempts++
            if (attempts < 3) throw IllegalStateException("temporary")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
        assertEquals(2, delays.size)
        assertTrue(delays.all { it in 75..625 }, "All retry delays should be within exponential backoff range [75ms, 625ms]")
    }

    @Test
    fun `not retry non transient failures해야 한다`() = runTest {
        var attempts = 0
        val executor = RetryExecutor(
            retry = RetryProperties(maxAttempts = 5),
            circuitBreaker = null,
            isTransientError = { false },
            delayFn = {},
            randomFn = { 0.5 }
        )

        try {
            executor.execute {
                attempts++
                throw IllegalArgumentException("bad request")
            }
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 예상 결과
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `rethrow cancellation without retry해야 한다`() = runTest {
        var attempts = 0
        val executor = RetryExecutor(
            retry = RetryProperties(maxAttempts = 5),
            circuitBreaker = null,
            isTransientError = { true },
            delayFn = {},
            randomFn = { 0.5 }
        )

        try {
            executor.execute {
                attempts++
                throw CancellationException("cancelled")
            }
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // 예상 결과
        }

        assertEquals(1, attempts)
    }

    // ========================================================================
    // R248: LLM_CALL 예외 자동 기록 테스트
    // ========================================================================

    @Test
    fun `R248 비일시적 에러가 즉시 throw되면 LLM_CALL stage로 기록되어야 한다`() = runTest {
        val registry = SimpleMeterRegistry()
        val collector: EvaluationMetricsCollector = MicrometerEvaluationMetricsCollector(registry)
        val executor = RetryExecutor(
            retry = RetryProperties(maxAttempts = 5),
            circuitBreaker = null,
            isTransientError = { false },
            delayFn = {},
            randomFn = { 0.5 },
            evaluationMetricsCollector = collector
        )

        try {
            executor.execute {
                throw IllegalArgumentException("permanent")
            }
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 예상
        }

        val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "llm_call")
            .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "IllegalArgumentException")
            .counter()
        assertNotNull(counter) {
            "비일시적 에러는 즉시 LLM_CALL stage로 기록되어야 한다"
        }
        assertEquals(1.0, counter!!.count())
    }

    @Test
    fun `R248 재시도 소진 후 최종 throw 시 기록되어야 한다`() = runTest {
        val registry = SimpleMeterRegistry()
        val collector: EvaluationMetricsCollector = MicrometerEvaluationMetricsCollector(registry)
        val executor = RetryExecutor(
            retry = RetryProperties(maxAttempts = 3, initialDelayMs = 1, maxDelayMs = 1),
            circuitBreaker = null,
            isTransientError = { true },
            delayFn = {},
            randomFn = { 0.5 },
            evaluationMetricsCollector = collector
        )

        var attempts = 0
        try {
            executor.execute {
                attempts++
                throw IllegalStateException("always fails")
            }
            fail("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // 예상
        }

        assertEquals(3, attempts) { "3회 재시도되어야 한다" }

        val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "llm_call")
            .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "IllegalStateException")
            .counter()
        assertNotNull(counter) { "재시도 소진 후 기록되어야 한다" }
        assertEquals(1.0, counter!!.count()) {
            "중간 재시도는 기록하지 않고 최종 실패만 1회 기록"
        }
    }

    @Test
    fun `R248 중간 재시도 성공 시 기록하지 않아야 한다`() = runTest {
        val registry = SimpleMeterRegistry()
        val collector: EvaluationMetricsCollector = MicrometerEvaluationMetricsCollector(registry)
        val executor = RetryExecutor(
            retry = RetryProperties(maxAttempts = 3, initialDelayMs = 1, maxDelayMs = 1),
            circuitBreaker = null,
            isTransientError = { true },
            delayFn = {},
            randomFn = { 0.5 },
            evaluationMetricsCollector = collector
        )

        var attempts = 0
        val result = executor.execute {
            attempts++
            if (attempts < 2) throw IllegalStateException("temporary")
            "success"
        }

        assertEquals("success", result)
        assertEquals(2, attempts)

        val meter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .counter()
        assertNull(meter) {
            "중간 재시도로 복구되면 execution.error는 기록되지 않아야 한다"
        }
    }

    @Test
    fun `R248 기본 NoOp collector도 정상 동작해야 한다 (backward compat)`() = runTest {
        val executor = RetryExecutor(
            retry = RetryProperties(maxAttempts = 1),
            circuitBreaker = null,
            isTransientError = { false },
            delayFn = {},
            randomFn = { 0.5 }
        )

        try {
            executor.execute {
                throw RuntimeException("boom")
            }
            fail("Expected RuntimeException")
        } catch (_: RuntimeException) {
            // 예상
        }
    }

    @Test
    fun `R248 errorStage override로 다른 stage를 사용할 수 있어야 한다`() = runTest {
        val registry = SimpleMeterRegistry()
        val collector: EvaluationMetricsCollector = MicrometerEvaluationMetricsCollector(registry)
        val executor = RetryExecutor(
            retry = RetryProperties(maxAttempts = 1),
            circuitBreaker = null,
            isTransientError = { false },
            delayFn = {},
            randomFn = { 0.5 },
            evaluationMetricsCollector = collector,
            errorStage = ExecutionStage.PARSING
        )

        try {
            executor.execute {
                throw IllegalArgumentException("bad json")
            }
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 예상
        }

        val counter = registry.find(MicrometerEvaluationMetricsCollector.METRIC_EXECUTION_ERROR)
            .tag(MicrometerEvaluationMetricsCollector.TAG_STAGE, "parsing")
            .tag(MicrometerEvaluationMetricsCollector.TAG_EXCEPTION, "IllegalArgumentException")
            .counter()
        assertNotNull(counter) {
            "errorStage override가 적용되어 parsing stage로 기록되어야 한다"
        }
        assertEquals(1.0, counter!!.count())
    }

    @Test
    fun `R248 NoOp 수집기 명시 주입도 backward compat 유지해야 한다`() = runTest {
        val executor = RetryExecutor(
            retry = RetryProperties(maxAttempts = 1),
            circuitBreaker = null,
            isTransientError = { false },
            delayFn = {},
            randomFn = { 0.5 },
            evaluationMetricsCollector = NoOpEvaluationMetricsCollector
        )

        try {
            executor.execute {
                throw IllegalStateException("test")
            }
            fail("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // 예상
        }
    }
}
