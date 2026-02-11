package com.arc.reactor.resilience.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.resilience.FallbackStrategy

/**
 * No-op fallback strategy that never recovers from failures.
 *
 * Used as the default when no fallback is configured.
 */
class NoOpFallbackStrategy : FallbackStrategy {

    override suspend fun execute(command: AgentCommand, originalError: Exception): AgentResult? = null
}
