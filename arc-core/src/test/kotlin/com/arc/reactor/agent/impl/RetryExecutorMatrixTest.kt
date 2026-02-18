package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.RetryProperties
import com.arc.reactor.resilience.CircuitBreaker
import com.arc.reactor.resilience.CircuitBreakerMetrics
import com.arc.reactor.resilience.CircuitBreakerState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Tag("matrix")
class RetryExecutorMatrixTest {

    private class CountingCircuitBreaker : CircuitBreaker {
        var executeCount: Int = 0

        override suspend fun <T> execute(block: suspend () -> T): T {
            executeCount++
            return block()
        }

        override fun state(): CircuitBreakerState = CircuitBreakerState.CLOSED

        override fun reset() = Unit

        override fun metrics(): CircuitBreakerMetrics {
            return CircuitBreakerMetrics(
                failureCount = 0,
                successCount = 0,
                state = CircuitBreakerState.CLOSED,
                lastFailureTime = null
            )
        }
    }

    @Test
    fun `retry should run inside circuit breaker and preserve attempt behavior`() = runTest {
        val circuitBreaker = CountingCircuitBreaker()
        var attempts = 0
        val delays = mutableListOf<Long>()
        val executor = RetryExecutor(
            retry = RetryProperties(maxAttempts = 4, initialDelayMs = 10, multiplier = 2.0, maxDelayMs = 100),
            circuitBreaker = circuitBreaker,
            isTransientError = { true },
            delayFn = { delays.add(it) },
            randomFn = { 0.5 }
        )

        val result = executor.execute {
            attempts++
            if (attempts < 4) throw IllegalStateException("temporary-$attempts")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(4, attempts)
        assertEquals(3, delays.size)
        assertEquals(1, circuitBreaker.executeCount)
    }

    @Test
    fun `retry delay should stay within jitter bounds across delay matrix`() = runTest {
        val randomValues = listOf(0.0, 0.25, 0.5, 0.75, 1.0)
        var randomIndex = 0
        val delays = mutableListOf<Long>()
        var attempts = 0
        val executor = RetryExecutor(
            retry = RetryProperties(maxAttempts = 6, initialDelayMs = 100, multiplier = 2.0, maxDelayMs = 1_000),
            circuitBreaker = null,
            isTransientError = { true },
            delayFn = { delays.add(it) },
            randomFn = {
                val value = randomValues[randomIndex % randomValues.size]
                randomIndex++
                value
            }
        )

        val result = executor.execute {
            attempts++
            if (attempts <= 5) throw IllegalStateException("temporary-$attempts")
            "done"
        }

        assertEquals("done", result)
        assertEquals(5, delays.size)

        delays.forEachIndexed { attempt, delay ->
            val base = minOf((100 * Math.pow(2.0, attempt.toDouble())).toLong(), 1_000L)
            val min = (base * 0.75).toLong()
            val max = (base * 1.25).toLong()
            assertTrue(delay in min..max, "attempt=$attempt delay=$delay expectedRange=$min..$max")
        }
    }

    @Test
    fun `retry delay should never be negative even with out-of-range random`() = runTest {
        val delays = mutableListOf<Long>()
        var attempts = 0
        val executor = RetryExecutor(
            retry = RetryProperties(maxAttempts = 3, initialDelayMs = 50, multiplier = 2.0, maxDelayMs = 500),
            circuitBreaker = null,
            isTransientError = { true },
            delayFn = { delays.add(it) },
            randomFn = { -10.0 }
        )

        val result = executor.execute {
            attempts++
            if (attempts < 3) throw IllegalStateException("transient")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf(0L, 0L), delays)
    }

    @Test
    fun `max attempts should be coerced to one when configured with zero`() = runTest {
        var attempts = 0
        val executor = RetryExecutor(
            retry = RetryProperties(maxAttempts = 0, initialDelayMs = 1, multiplier = 2.0, maxDelayMs = 10),
            circuitBreaker = null,
            isTransientError = { true },
            delayFn = {},
            randomFn = { 0.5 }
        )

        try {
            executor.execute {
                attempts++
                throw IllegalStateException("always fails")
            }
        } catch (_: IllegalStateException) {
            // expected
        }

        assertEquals(1, attempts)
    }
}
