package com.arc.reactor.slack.handler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.slack.model.SlackSlashCommand
import com.arc.reactor.slack.service.SlackMessagingService
import mu.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Default slash command handler.
 *
 * Flow:
 * 1. Post user question to the channel to create a thread
 * 2. Execute agent and reply in the created thread
 * 3. If channel post fails, fallback to response_url
 */
class DefaultSlackCommandHandler(
    private val agentExecutor: AgentExecutor,
    private val messagingService: SlackMessagingService,
    private val defaultProvider: String = "configured backend model"
) : SlackCommandHandler {

    override suspend fun handleSlashCommand(command: SlackSlashCommand) {
        val prompt = command.text.trim()
        if (prompt.isBlank()) {
            messagingService.sendResponseUrl(
                responseUrl = command.responseUrl,
                text = "Please enter a question. Example: /jarvis What are my tasks today?"
            )
            return
        }

        try {
            val threadTs = postQuestionToChannel(command)
            if (threadTs != null) {
                executeAndReplyInThread(command, prompt, threadTs)
            } else {
                executeAndReplyByResponseUrl(command, prompt)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to process slash command for channel=${command.channelId}" }
            messagingService.sendResponseUrl(
                responseUrl = command.responseUrl,
                text = ":x: An internal error occurred. Please try again later."
            )
        }
    }

    private suspend fun postQuestionToChannel(command: SlackSlashCommand): String? {
        val question = ":speech_balloon: *<@${command.userId}>'s question*\n>${command.text.trim()}"
        val result = messagingService.sendMessage(
            channelId = command.channelId,
            text = question
        )
        if (!result.ok) {
            logger.info { "Slash command channel post failed, fallback to response_url: error=${result.error}" }
            return null
        }
        return result.ts
    }

    private suspend fun executeAndReplyInThread(
        command: SlackSlashCommand,
        prompt: String,
        threadTs: String
    ) {
        val sessionId = "slack-${command.channelId}-$threadTs"
        val result = executeAgent(command, prompt, sessionId)
        val responseText = toResponseText(result)

        val sendResult = messagingService.sendMessage(
            channelId = command.channelId,
            text = responseText,
            threadTs = threadTs
        )

        if (!sendResult.ok) {
            logger.warn { "Thread reply failed, fallback to response_url: error=${sendResult.error}" }
            messagingService.sendResponseUrl(
                responseUrl = command.responseUrl,
                text = responseText
            )
        }
    }

    private suspend fun executeAndReplyByResponseUrl(command: SlackSlashCommand, prompt: String) {
        val sessionId = "slack-cmd-${command.channelId}-${command.userId}-${System.currentTimeMillis()}"
        val result = executeAgent(command, prompt, sessionId)
        val responseText = toResponseText(result)

        val sent = messagingService.sendResponseUrl(
            responseUrl = command.responseUrl,
            text = responseText
        )
        if (!sent) {
            logger.error { "Failed to send slash response via response_url" }
        }
    }

    private suspend fun executeAgent(
        command: SlackSlashCommand,
        prompt: String,
        sessionId: String
    ): com.arc.reactor.agent.model.AgentResult {
        return agentExecutor.execute(
            AgentCommand(
                systemPrompt = SlackSystemPromptFactory.build(defaultProvider),
                userPrompt = prompt,
                userId = command.userId,
                metadata = mapOf(
                    "sessionId" to sessionId,
                    "source" to "slack",
                    "entrypoint" to "slash",
                    "channelId" to command.channelId
                )
            )
        )
    }

    private fun toResponseText(result: com.arc.reactor.agent.model.AgentResult): String {
        return if (result.success) {
            result.content ?: "I processed your request but have no response."
        } else {
            ":warning: ${result.errorMessage ?: "An error occurred while processing your request."}"
        }
    }
}
