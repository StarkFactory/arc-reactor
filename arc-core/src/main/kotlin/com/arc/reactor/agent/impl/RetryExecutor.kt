package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.RetryProperties
import com.arc.reactor.resilience.CircuitBreaker
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.delay
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

internal class RetryExecutor(
    private val retry: RetryProperties,
    private val circuitBreaker: CircuitBreaker?,
    private val isTransientError: (Exception) -> Boolean,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val randomFn: () -> Double = Math::random
) {

    suspend fun <T> execute(block: suspend () -> T): T {
        val retryBlock: suspend () -> T = {
            val maxAttempts = retry.maxAttempts.coerceAtLeast(1)
            var lastException: Exception? = null
            var result: T? = null
            var completed = false

            repeat(maxAttempts) { attempt ->
                if (completed) return@repeat
                try {
                    result = block()
                    completed = true
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    lastException = e
                    if (!isTransientError(e) || attempt == maxAttempts - 1) {
                        throw e
                    }
                    val baseDelay = minOf(
                        (retry.initialDelayMs * Math.pow(retry.multiplier, attempt.toDouble())).toLong(),
                        retry.maxDelayMs
                    )
                    // Add +/-25% jitter to prevent thundering herd
                    val jitter = (baseDelay * 0.25 * (randomFn() * 2 - 1)).toLong()
                    val delayMs = (baseDelay + jitter).coerceAtLeast(0)
                    logger.warn {
                        "Transient error (attempt ${attempt + 1}/$maxAttempts), " +
                            "retrying in ${delayMs}ms: ${e.message}"
                    }
                    delayFn(delayMs)
                }
            }

            if (completed) {
                @Suppress("UNCHECKED_CAST")
                result as T
            } else {
                throw lastException ?: IllegalStateException("Retry exhausted")
            }
        }

        return if (circuitBreaker != null) {
            circuitBreaker.execute(retryBlock)
        } else {
            retryBlock()
        }
    }
}
