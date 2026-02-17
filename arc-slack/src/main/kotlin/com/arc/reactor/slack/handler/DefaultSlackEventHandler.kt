package com.arc.reactor.slack.handler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.slack.model.SlackEventCommand
import com.arc.reactor.slack.service.SlackMessagingService
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Default Slack event handler that delegates to AgentExecutor.
 *
 * - Extracts clean text from @mention events (removes bot mention tag)
 * - Maps Slack threads to arc-reactor sessions via sessionId
 * - Sends agent response back to the Slack thread
 * - Guard pipeline is applied automatically via AgentExecutor.execute()
 */
class DefaultSlackEventHandler(
    private val agentExecutor: AgentExecutor,
    private val messagingService: SlackMessagingService,
    private val defaultProvider: String = "configured backend model"
) : SlackEventHandler {

    override suspend fun handleAppMention(command: SlackEventCommand) {
        // Remove bot mention tag (<@U12345>) from text
        val cleanText = command.text.replace(Regex("<@[A-Za-z0-9]+>"), "").trim()
        if (cleanText.isBlank()) {
            logger.debug { "Empty mention from user=${command.userId}, skipping" }
            return
        }

        val threadTs = command.threadTs ?: command.ts
        executeAndRespond(command.channelId, threadTs, command.userId, cleanText)
    }

    override suspend fun handleMessage(command: SlackEventCommand) {
        val text = command.text.trim()
        if (text.isBlank()) return

        val threadTs = command.threadTs ?: command.ts
        executeAndRespond(command.channelId, threadTs, command.userId, text)
    }

    private suspend fun executeAndRespond(
        channelId: String,
        threadTs: String,
        userId: String,
        userPrompt: String
    ) {
        try {
            // Map Slack thread to arc-reactor session
            val sessionId = "slack-$channelId-$threadTs"

            val result = agentExecutor.execute(
                AgentCommand(
                    systemPrompt = SlackSystemPromptFactory.build(defaultProvider),
                    userPrompt = userPrompt,
                    userId = userId,
                    metadata = mapOf(
                        "sessionId" to sessionId,
                        "source" to "slack",
                        "channel" to "slack"
                    )
                )
            )

            val responseText = if (result.success) {
                result.content ?: "I processed your request but have no response."
            } else {
                ":warning: ${result.errorMessage ?: "An error occurred while processing your request."}"
            }

            messagingService.sendMessage(
                channelId = channelId,
                text = responseText,
                threadTs = threadTs
            )
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Failed to process Slack event for channel=$channelId, thread=$threadTs" }
            try {
                messagingService.sendMessage(
                    channelId = channelId,
                    text = ":x: An internal error occurred. Please try again later.",
                    threadTs = threadTs
                )
            } catch (sendError: Exception) {
                sendError.throwIfCancellation()
                logger.error(sendError) { "Failed to send error message to Slack" }
            }
        }
    }
}
