package com.arc.reactor.agent

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * AI Agent Executor Interface
 *
 * Executes AI agents using the ReAct (Reasoning + Acting) pattern.
 * The agent autonomously reasons about the task, selects and executes tools,
 * observes results, and iterates until completing the task.
 *
 * ## ReAct Loop
 * ```
 * Goal → [Thought] → [Action] → [Observation] → ... → Final Answer
 * ```
 *
 * ## Execution Flow
 * 1. Guard Pipeline - Security checks (rate limit, injection detection, etc.)
 * 2. BeforeAgentStart Hook - Pre-processing and validation
 * 3. Agent Loop - LLM reasoning with tool execution
 * 4. AfterAgentComplete Hook - Post-processing and audit
 *
 * ## Example Usage
 * ```kotlin
 * @Service
 * class MyService(private val agentExecutor: AgentExecutor) {
 *
 *     suspend fun chat(message: String): String {
 *         val result = agentExecutor.execute(
 *             AgentCommand(
 *                 systemPrompt = "You are a helpful assistant.",
 *                 userPrompt = message,
 *                 userId = "user-123"
 *             )
 *         )
 *         return result.content ?: "Error: ${result.errorMessage}"
 *     }
 * }
 * ```
 *
 * @see AgentCommand for input parameters
 * @see AgentResult for output structure
 * @see com.arc.reactor.agent.impl.SpringAiAgentExecutor for default implementation
 */
interface AgentExecutor {

    /**
     * Executes the agent with the given command.
     *
     * This method orchestrates the full agent execution pipeline including:
     * - Guard validation (if userId is provided)
     * - Hook execution (before/after)
     * - LLM interaction with tool calling
     * - Conversation memory management
     *
     * @param command The agent command containing prompt, mode, and configuration
     * @return AgentResult with success status, response content, and metadata
     *
     * @throws Nothing - All exceptions are caught and returned in AgentResult.errorMessage
     */
    suspend fun execute(command: AgentCommand): AgentResult

    /**
     * Simplified execution with just system and user prompts.
     *
     * Convenience method for simple use cases without advanced configuration.
     *
     * @param systemPrompt The system prompt defining agent behavior
     * @param userPrompt The user's input message
     * @return AgentResult with the agent's response
     */
    suspend fun execute(
        systemPrompt: String,
        userPrompt: String
    ): AgentResult = execute(
        AgentCommand(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt
        )
    )

    /**
     * Executes the agent in streaming mode, returning chunks as a Flow.
     *
     * Runs the same guard and hook pipeline as [execute], but streams
     * the LLM response token-by-token via Kotlin Flow.
     *
     * If a guard rejects or a hook blocks, a single error chunk is emitted.
     *
     * @param command The agent command
     * @return Flow of response text chunks
     */
    fun executeStream(command: AgentCommand): Flow<String> = flowOf()
}
