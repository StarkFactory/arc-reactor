package com.arc.reactor.resilience.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.resilience.CircuitBreaker
import com.arc.reactor.resilience.CircuitBreakerMetrics
import com.arc.reactor.resilience.CircuitBreakerOpenException
import com.arc.reactor.resilience.CircuitBreakerState
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Kotlin-native circuit breaker implementation (no external dependencies).
 *
 * Thread-safe via atomic operations. Does not use locks.
 *
 * @param failureThreshold Number of consecutive failures before opening the circuit
 * @param resetTimeoutMs Time in ms to wait before transitioning from OPEN to HALF_OPEN
 * @param halfOpenMaxCalls Number of trial calls allowed in HALF_OPEN state
 * @param name Identifier for logging and exception messages
 * @param clock Time source for testability (default: System.currentTimeMillis)
 * @param agentMetrics Metrics recorder for state transitions
 */
class DefaultCircuitBreaker(
    private val failureThreshold: Int = 5,
    private val resetTimeoutMs: Long = 30_000,
    private val halfOpenMaxCalls: Int = 1,
    private val name: String = "default",
    private val clock: () -> Long = System::currentTimeMillis,
    private val agentMetrics: AgentMetrics = NoOpAgentMetrics()
) : CircuitBreaker {

    private val state = AtomicReference(CircuitBreakerState.CLOSED)
    private val failureCount = AtomicInteger(0)
    private val successCount = AtomicInteger(0)
    private val halfOpenCallCount = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0)
    private val openedAt = AtomicLong(0)

    override suspend fun <T> execute(block: suspend () -> T): T {
        val currentState = evaluateState()

        when (currentState) {
            CircuitBreakerState.OPEN -> {
                logger.debug { "Circuit breaker '$name' is OPEN, rejecting call" }
                throw CircuitBreakerOpenException(name)
            }
            CircuitBreakerState.HALF_OPEN -> {
                if (halfOpenCallCount.incrementAndGet() > halfOpenMaxCalls) {
                    // Exceeded trial call limit — still open
                    halfOpenCallCount.decrementAndGet()
                    throw CircuitBreakerOpenException(name)
                }
            }
            CircuitBreakerState.CLOSED -> { /* proceed normally */ }
        }

        return try {
            val result = block()
            onSuccess()
            result
        } catch (e: Exception) {
            // Cancellation is a control signal, not a circuit-breaker failure.
            e.throwIfCancellation()
            onFailure()
            throw e
        }
    }

    override fun state(): CircuitBreakerState = evaluateState()

    override fun reset() {
        state.set(CircuitBreakerState.CLOSED)
        failureCount.set(0)
        successCount.set(0)
        halfOpenCallCount.set(0)
        lastFailureTime.set(0)
        openedAt.set(0)
        logger.info { "Circuit breaker '$name' reset to CLOSED" }
    }

    override fun metrics(): CircuitBreakerMetrics = CircuitBreakerMetrics(
        failureCount = failureCount.get(),
        successCount = successCount.get(),
        state = evaluateState(),
        lastFailureTime = lastFailureTime.get().takeIf { it > 0 }
    )

    /**
     * Evaluate the effective state, handling OPEN → HALF_OPEN timeout transition.
     */
    private fun evaluateState(): CircuitBreakerState {
        val current = state.get()
        if (current == CircuitBreakerState.OPEN) {
            val elapsed = clock() - openedAt.get()
            if (elapsed >= resetTimeoutMs) {
                if (state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN)) {
                    halfOpenCallCount.set(0)
                    logger.info { "Circuit breaker '$name' transitioned OPEN → HALF_OPEN after ${elapsed}ms" }
                    agentMetrics.recordCircuitBreakerStateChange(
                        name, CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN
                    )
                }
                return state.get()
            }
        }
        return current
    }

    private fun onSuccess() {
        val current = state.get()
        if (current == CircuitBreakerState.HALF_OPEN) {
            // Success in HALF_OPEN → close the circuit
            state.set(CircuitBreakerState.CLOSED)
            failureCount.set(0)
            successCount.incrementAndGet()
            halfOpenCallCount.set(0)
            logger.info { "Circuit breaker '$name' transitioned HALF_OPEN → CLOSED (recovery confirmed)" }
            agentMetrics.recordCircuitBreakerStateChange(
                name, CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED
            )
        } else {
            successCount.incrementAndGet()
            failureCount.set(0) // Reset consecutive failure count on success
        }
    }

    private fun onFailure() {
        lastFailureTime.set(clock())
        val current = state.get()

        if (current == CircuitBreakerState.HALF_OPEN) {
            // Failure in HALF_OPEN → reopen
            state.set(CircuitBreakerState.OPEN)
            openedAt.set(clock())
            halfOpenCallCount.set(0)
            logger.warn { "Circuit breaker '$name' transitioned HALF_OPEN → OPEN (trial call failed)" }
            agentMetrics.recordCircuitBreakerStateChange(
                name, CircuitBreakerState.HALF_OPEN, CircuitBreakerState.OPEN
            )
        } else if (current == CircuitBreakerState.CLOSED) {
            val failures = failureCount.incrementAndGet()
            if (failures >= failureThreshold) {
                state.set(CircuitBreakerState.OPEN)
                openedAt.set(clock())
                logger.warn {
                    "Circuit breaker '$name' transitioned CLOSED → OPEN " +
                        "(failures=$failures, threshold=$failureThreshold)"
                }
                agentMetrics.recordCircuitBreakerStateChange(
                    name, CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN
                )
            }
        }
    }
}
