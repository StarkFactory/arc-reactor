package com.arc.reactor.resilience

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult

/**
 * Strategy for handling agent execution failures by attempting
 * alternative approaches (e.g., fallback to a different LLM model).
 *
 * Implementations decide which errors to handle and how to recover.
 * Returning `null` indicates the strategy cannot handle the failure.
 *
 * ## Usage
 * ```kotlin
 * val strategy = ModelFallbackStrategy(
 *     fallbackModels = listOf("openai", "anthropic"),
 *     chatModelProvider = provider
 * )
 * val result = strategy.execute(command, originalError)
 * ```
 *
 * @see com.arc.reactor.resilience.impl.ModelFallbackStrategy
 * @see com.arc.reactor.resilience.impl.NoOpFallbackStrategy
 */
interface FallbackStrategy {

    /**
     * Attempt to recover from a failed agent execution.
     *
     * @param command The original agent command that failed
     * @param originalError The exception that caused the failure
     * @return A successful [AgentResult] if recovery succeeded, `null` if not recoverable
     */
    suspend fun execute(command: AgentCommand, originalError: Exception): AgentResult?
}
