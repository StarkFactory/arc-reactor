package com.arc.reactor.discord.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the Discord integration module.
 *
 * Prefix: `arc.reactor.discord`
 */
@ConfigurationProperties(prefix = "arc.reactor.discord")
data class DiscordProperties(
    /** Enable Discord integration. Default: false (opt-in) */
    val enabled: Boolean = false,

    /** Discord bot token */
    val token: String = "",

    /** Maximum concurrent Discord event processing */
    val maxConcurrentRequests: Int = 5,

    /** Request timeout for agent execution (ms) */
    val requestTimeoutMs: Long = 30000,

    /** Only respond when the bot is mentioned. Default: true */
    val respondToMentionsOnly: Boolean = true
)
