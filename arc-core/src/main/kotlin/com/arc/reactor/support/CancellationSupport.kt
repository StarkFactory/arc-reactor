package com.arc.reactor.support

import kotlinx.coroutines.CancellationException

/**
 * Rethrow coroutine cancellation signals from broad catch blocks.
 */
fun Throwable.throwIfCancellation() {
    if (this is CancellationException) {
        throw this
    }
}
