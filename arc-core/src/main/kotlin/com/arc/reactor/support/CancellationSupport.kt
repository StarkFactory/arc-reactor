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

/**
 * [runCatching] variant for suspend blocks that preserves coroutine cancellation semantics.
 */
suspend inline fun <T> runSuspendCatchingNonCancellation(
    block: suspend () -> T
): Result<T> {
    return try {
        Result.success(block())
    } catch (e: Exception) {
        e.throwIfCancellation()
        Result.failure(e)
    }
}
