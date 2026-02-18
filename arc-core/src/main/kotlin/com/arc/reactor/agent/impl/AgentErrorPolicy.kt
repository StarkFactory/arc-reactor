package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.resilience.CircuitBreakerOpenException

private val httpStatusPattern = Regex("(status|http|error|code)[^a-z0-9]*(429|500|502|503|504)")

/**
 * Default transient error classifier.
 * Determines if an exception is a temporary error worth retrying.
 */
fun defaultTransientErrorClassifier(e: Exception): Boolean {
    val message = e.message?.lowercase() ?: return false
    return httpStatusPattern.containsMatchIn(message) ||
        message.contains("rate limit") ||
        message.contains("too many requests") ||
        message.contains("timeout") ||
        message.contains("timed out") ||
        message.contains("connection refused") ||
        message.contains("connection reset") ||
        message.contains("internal server error") ||
        message.contains("service unavailable") ||
        message.contains("bad gateway")
}

internal class AgentErrorPolicy(
    private val transientErrorClassifier: (Exception) -> Boolean = ::defaultTransientErrorClassifier
) {

    fun isTransient(e: Exception): Boolean = transientErrorClassifier(e)

    fun classify(e: Exception): AgentErrorCode {
        return when {
            e is CircuitBreakerOpenException -> AgentErrorCode.CIRCUIT_BREAKER_OPEN
            e.message?.contains("rate limit", ignoreCase = true) == true -> AgentErrorCode.RATE_LIMITED
            e.message?.contains("timeout", ignoreCase = true) == true -> AgentErrorCode.TIMEOUT
            e.message?.contains("context length", ignoreCase = true) == true -> AgentErrorCode.CONTEXT_TOO_LONG
            e.message?.contains("tool", ignoreCase = true) == true -> AgentErrorCode.TOOL_ERROR
            else -> AgentErrorCode.UNKNOWN
        }
    }
}
