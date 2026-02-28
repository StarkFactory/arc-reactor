package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.RetryProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class RetryExecutorTest {

    @Test
    fun `should retry transient failures and eventually succeed`() = runTest {
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
    fun `should not retry non transient failures`() = runTest {
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
            // expected
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `should rethrow cancellation without retry`() = runTest {
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
            // expected
        }

        assertEquals(1, attempts)
    }
}
