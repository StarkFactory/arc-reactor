package com.arc.reactor.resilience.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.resilience.FallbackStrategy
import mu.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Fallback strategy that retries the request with alternative LLM models.
 *
 * Models are tried in order. The first successful response is returned.
 * If all models fail, returns `null` to indicate fallback exhaustion.
 *
 * Fallback calls are simple LLM calls (no tools, no ReAct loop) to
 * maximize the chance of success in degraded conditions.
 *
 * ## Example
 * ```yaml
 * arc:
 *   reactor:
 *     fallback:
 *       enabled: true
 *       models:
 *         - openai
 *         - anthropic
 * ```
 */
class ModelFallbackStrategy(
    private val fallbackModels: List<String>,
    private val chatModelProvider: ChatModelProvider
) : FallbackStrategy {

    override suspend fun execute(command: AgentCommand, originalError: Exception): AgentResult? {
        if (fallbackModels.isEmpty()) return null

        for (model in fallbackModels) {
            try {
                logger.info { "Attempting fallback to model: $model" }
                val chatClient = chatModelProvider.getChatClient(model)
                val response = kotlinx.coroutines.runInterruptible {
                    chatClient.prompt()
                        .system(command.systemPrompt)
                        .user(command.userPrompt)
                        .call()
                        .chatResponse()
                }
                val content = response?.results?.firstOrNull()?.output?.text
                if (!content.isNullOrBlank()) {
                    logger.info { "Fallback to model '$model' succeeded" }
                    return AgentResult.success(content = content)
                }
                logger.warn { "Fallback model '$model' returned empty response" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Fallback to model '$model' failed" }
            }
        }

        logger.warn { "All fallback models exhausted: $fallbackModels" }
        return null
    }
}
