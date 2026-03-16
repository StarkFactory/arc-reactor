package com.arc.reactor.support

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CancellationSupport에 대한 테스트.
 *
 * 코루틴 취소 지원 유틸리티를 검증합니다.
 */
class CancellationSupportTest {

    @Test
    fun `throwIfCancellation은(는) rethrow cancellation exception해야 한다`() {
        assertThrows(CancellationException::class.java) {
            CancellationException("cancelled").throwIfCancellation()
        }
    }

    @Test
    fun `throwIfCancellation은(는) ignore non-cancellation exceptions해야 한다`() {
        assertDoesNotThrow {
            IllegalStateException("boom").throwIfCancellation()
        }
    }

    @Test
    fun `runSuspendCatchingNonCancellation은(는) return success해야 한다`() = runTest {
        val result = runSuspendCatchingNonCancellation { "ok" }

        assertTrue(result.isSuccess, "Non-throwing suspend block should return Success result")
        assertEquals("ok", result.getOrNull())
    }

    @Test
    fun `runSuspendCatchingNonCancellation은(는) capture non-cancellation exception해야 한다`() = runTest {
        val result = runSuspendCatchingNonCancellation {
            error("boom")
        }

        assertTrue(result.isFailure, "Block throwing non-cancellation exception should return Failure result")
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `runSuspendCatchingNonCancellation은(는) rethrow cancellation해야 한다`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                runSuspendCatchingNonCancellation {
                    throw CancellationException("cancelled")
                }
            }
        }
    }
}
