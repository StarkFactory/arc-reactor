package com.arc.reactor.resilience

/**
 * Circuit breaker that prevents cascading failures by tracking errors
 * and short-circuiting calls when the failure rate is too high.
 *
 * ## State Transitions
 * ```
 * CLOSED ──(failures >= threshold)──> OPEN
 * OPEN ──(resetTimeout elapsed)──> HALF_OPEN
 * HALF_OPEN ──(success)──> CLOSED
 * HALF_OPEN ──(failure)──> OPEN
 * ```
 *
 * ## Usage
 * ```kotlin
 * val cb = DefaultCircuitBreaker(properties)
 * val result = cb.execute { riskyCall() }
 * ```
 */
interface CircuitBreaker {

    /**
     * Execute a block within the circuit breaker.
     *
     * @throws CircuitBreakerOpenException if the circuit is OPEN and resetTimeout has not elapsed
     */
    suspend fun <T> execute(block: suspend () -> T): T

    /** Current circuit breaker state. */
    fun state(): CircuitBreakerState

    /** Reset the circuit breaker to CLOSED state with zeroed counters. */
    fun reset()

    /** Snapshot of current metrics. */
    fun metrics(): CircuitBreakerMetrics
}

/**
 * Circuit breaker states.
 *
 * - [CLOSED]: Normal operation. Failures are counted.
 * - [OPEN]: Calls are rejected immediately. Waiting for resetTimeout.
 * - [HALF_OPEN]: Trial calls are allowed to test recovery.
 */
enum class CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}

/**
 * Snapshot of circuit breaker metrics for observability.
 */
data class CircuitBreakerMetrics(
    val failureCount: Int,
    val successCount: Int,
    val state: CircuitBreakerState,
    val lastFailureTime: Long?
)

/**
 * Thrown when a call is rejected because the circuit breaker is [CircuitBreakerState.OPEN].
 */
class CircuitBreakerOpenException(
    val breakerName: String = "unknown"
) : RuntimeException("Circuit breaker '$breakerName' is OPEN — call rejected")
