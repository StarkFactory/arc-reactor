package com.arc.reactor.support

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CancellationSupportTest {

    @Test
    fun `throwIfCancellation should rethrow cancellation exception`() {
        assertThrows(CancellationException::class.java) {
            CancellationException("cancelled").throwIfCancellation()
        }
    }

    @Test
    fun `throwIfCancellation should ignore non-cancellation exceptions`() {
        assertDoesNotThrow {
            IllegalStateException("boom").throwIfCancellation()
        }
    }

    @Test
    fun `runSuspendCatchingNonCancellation should return success`() = runTest {
        val result = runSuspendCatchingNonCancellation { "ok" }

        assertTrue(result.isSuccess)
        assertEquals("ok", result.getOrNull())
    }

    @Test
    fun `runSuspendCatchingNonCancellation should capture non-cancellation exception`() = runTest {
        val result = runSuspendCatchingNonCancellation {
            error("boom")
        }

        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `runSuspendCatchingNonCancellation should rethrow cancellation`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                runSuspendCatchingNonCancellation {
                    throw CancellationException("cancelled")
                }
            }
        }
    }
}
