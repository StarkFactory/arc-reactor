package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.ErrorMessageResolver
import com.arc.reactor.agent.model.ResponseFormat
import com.arc.reactor.agent.model.TokenUsage
import com.arc.reactor.support.runSuspendCatchingNonCancellation
import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient

private val logger = KotlinLogging.logger {}

internal class StructuredResponseRepairer(
    private val errorMessageResolver: ErrorMessageResolver,
    private val resolveChatClient: (AgentCommand) -> ChatClient,
    private val structuredOutputValidator: StructuredOutputValidator = StructuredOutputValidator()
) {

    suspend fun validateAndRepair(
        rawContent: String,
        format: ResponseFormat,
        command: AgentCommand,
        tokenUsage: TokenUsage?,
        toolsUsed: List<String>
    ): AgentResult {
        if (format == ResponseFormat.TEXT) {
            return AgentResult.success(content = rawContent, toolsUsed = toolsUsed, tokenUsage = tokenUsage)
        }

        val stripped = structuredOutputValidator.stripMarkdownCodeFence(rawContent)

        if (structuredOutputValidator.isValidFormat(stripped, format)) {
            return AgentResult.success(content = stripped, toolsUsed = toolsUsed, tokenUsage = tokenUsage)
        }

        logger.warn { "Invalid $format response detected, attempting repair" }
        val repaired = attemptRepair(stripped, format, command)
        if (repaired != null && structuredOutputValidator.isValidFormat(repaired, format)) {
            return AgentResult.success(content = repaired, toolsUsed = toolsUsed, tokenUsage = tokenUsage)
        }

        logger.error { "Structured output validation failed after repair attempt" }
        return AgentResult.failure(
            errorMessage = errorMessageResolver.resolve(AgentErrorCode.INVALID_RESPONSE, null),
            errorCode = AgentErrorCode.INVALID_RESPONSE
        )
    }

    private suspend fun attemptRepair(
        invalidContent: String,
        format: ResponseFormat,
        command: AgentCommand
    ): String? {
        return runSuspendCatchingNonCancellation {
            val formatName = format.name
            val repairPrompt = "The following $formatName is invalid. " +
                "Fix it and return ONLY valid $formatName with no explanation or code fences:\n\n$invalidContent"

            val activeChatClient = resolveChatClient(command)
            val response = kotlinx.coroutines.runInterruptible {
                activeChatClient
                    .prompt()
                    .user(repairPrompt)
                    .call()
                    .chatResponse()
            }
            val repairedContent = response?.results?.firstOrNull()?.output?.text
            if (repairedContent != null) structuredOutputValidator.stripMarkdownCodeFence(repairedContent) else null
        }.getOrElse { e ->
            logger.warn(e) { "Repair attempt failed" }
            null
        }
    }
}
