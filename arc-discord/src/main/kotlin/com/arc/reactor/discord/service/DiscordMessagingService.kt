package com.arc.reactor.discord.service

import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.channel.MessageChannel
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Sends messages to Discord channels via Discord4J GatewayDiscordClient.
 *
 * Uses the gateway client to resolve channels and send messages.
 * Discord has a 2000-character message limit; messages exceeding this are truncated.
 */
class DiscordMessagingService(
    private val client: GatewayDiscordClient
) {
    /**
     * Sends a text message to a Discord channel.
     *
     * @param channelId Target channel ID (Snowflake string)
     * @param content Message content (truncated to 2000 chars if needed)
     */
    suspend fun sendMessage(channelId: String, content: String) {
        val truncated = if (content.length > MAX_MESSAGE_LENGTH) {
            content.take(MAX_MESSAGE_LENGTH - TRUNCATION_SUFFIX.length) + TRUNCATION_SUFFIX
        } else {
            content
        }

        try {
            val channel = client.getChannelById(Snowflake.of(channelId))
                .ofType(MessageChannel::class.java)
                .awaitSingle()

            channel.createMessage(truncated).awaitSingle()
            logger.debug { "Sent message to channel=$channelId (${truncated.length} chars)" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to send message to channel=$channelId" }
            throw e
        }
    }

    companion object {
        private const val MAX_MESSAGE_LENGTH = 2000
        private const val TRUNCATION_SUFFIX = "... (truncated)"
    }
}
