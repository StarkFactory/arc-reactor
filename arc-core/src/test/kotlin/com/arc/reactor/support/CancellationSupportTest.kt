package com.arc.reactor.support

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
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
}
