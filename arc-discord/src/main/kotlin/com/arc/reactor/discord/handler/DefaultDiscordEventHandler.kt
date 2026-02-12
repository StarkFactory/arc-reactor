package com.arc.reactor.discord.handler

import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.discord.model.DiscordEventCommand
import com.arc.reactor.discord.service.DiscordMessagingService
import mu.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Default Discord event handler that delegates to AgentExecutor.
 *
 * - Extracts clean text from messages (removes bot mention tag)
 * - Maps Discord channels to arc-reactor sessions via sessionId
 * - Sends agent response back to the Discord channel
 * - Guard pipeline is applied automatically via AgentExecutor.execute()
 */
class DefaultDiscordEventHandler(
    private val agentExecutor: AgentExecutor,
    private val messagingService: DiscordMessagingService,
    private val selfId: String = ""
) : DiscordEventHandler {

    override suspend fun handleMessage(command: DiscordEventCommand) {
        val cleanText = stripMention(command.content, selfId)
        if (cleanText.isBlank()) {
            logger.debug { "Empty message from user=${command.userId}, skipping" }
            return
        }

        executeAndRespond(
            channelId = command.channelId,
            userId = command.userId,
            userPrompt = cleanText
        )
    }

    private suspend fun executeAndRespond(
        channelId: String,
        userId: String,
        userPrompt: String
    ) {
        try {
            val sessionId = "discord-$channelId"

            val result = agentExecutor.execute(
                AgentCommand(
                    systemPrompt = "You are a helpful AI assistant responding in a Discord channel. " +
                        "Keep responses concise and well-formatted for Discord.",
                    userPrompt = userPrompt,
                    userId = userId,
                    metadata = mapOf("sessionId" to sessionId, "source" to "discord")
                )
            )

            val responseText = if (result.success) {
                result.content
                    ?: "I processed your request but have no response."
            } else {
                ":warning: ${result.errorMessage ?: "An error occurred while processing your request."}"
            }

            messagingService.sendMessage(channelId, responseText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to process Discord event for channel=$channelId" }
            try {
                messagingService.sendMessage(
                    channelId,
                    ":x: An internal error occurred. Please try again later."
                )
            } catch (sendError: CancellationException) {
                throw sendError
            } catch (sendError: Exception) {
                logger.error(sendError) { "Failed to send error message to Discord" }
            }
        }
    }

    companion object {
        private val MENTION_REGEX = Regex("<@!?\\d+>")

        /**
         * Strips bot mention tags from the message content.
         * Discord mention format: `<@userId>` or `<@!userId>`
         */
        fun stripMention(text: String, botId: String): String {
            return text.replace(MENTION_REGEX, "").trim()
        }
    }
}
