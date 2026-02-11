package com.arc.reactor.resilience

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.resilience.impl.DefaultCircuitBreaker
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry that manages named [CircuitBreaker] instances.
 *
 * Each name gets its own isolated circuit breaker (e.g., "llm", "mcp:server-name").
 * Breakers are created lazily on first access.
 *
 * @param failureThreshold Default failure threshold for new breakers
 * @param resetTimeoutMs Default reset timeout for new breakers
 * @param halfOpenMaxCalls Default half-open trial calls for new breakers
 * @param agentMetrics Metrics recorder for circuit breaker state transitions
 */
class CircuitBreakerRegistry(
    private val failureThreshold: Int = 5,
    private val resetTimeoutMs: Long = 30_000,
    private val halfOpenMaxCalls: Int = 1,
    private val agentMetrics: AgentMetrics = NoOpAgentMetrics()
) {

    private val breakers = ConcurrentHashMap<String, CircuitBreaker>()

    /**
     * Get or create a circuit breaker for the given name.
     */
    fun get(name: String): CircuitBreaker = breakers.computeIfAbsent(name) {
        DefaultCircuitBreaker(
            failureThreshold = failureThreshold,
            resetTimeoutMs = resetTimeoutMs,
            halfOpenMaxCalls = halfOpenMaxCalls,
            name = name,
            agentMetrics = agentMetrics
        )
    }

    /** Get a breaker only if it already exists. */
    fun getIfExists(name: String): CircuitBreaker? = breakers[name]

    /** All registered breaker names. */
    fun names(): Set<String> = breakers.keys.toSet()

    /** Reset all breakers to CLOSED state. */
    fun resetAll() {
        for ((_, breaker) in breakers) {
            breaker.reset()
        }
    }
}
