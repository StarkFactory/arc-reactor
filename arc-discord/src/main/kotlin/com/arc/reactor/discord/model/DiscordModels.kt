package com.arc.reactor.discord.model

/**
 * Internal command for Discord event processing.
 */
data class DiscordEventCommand(
    val channelId: String,
    val userId: String,
    val username: String,
    val content: String,
    val messageId: String,
    val guildId: String? = null
)
