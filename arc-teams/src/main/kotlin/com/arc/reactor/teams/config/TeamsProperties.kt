package com.arc.reactor.teams.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the Microsoft Teams integration module.
 *
 * @property enabled Whether the Teams integration is active.
 * @property defaultWebhookUrl Default Teams Incoming Webhook URL used when a job does not specify one.
 */
@ConfigurationProperties(prefix = "arc.reactor.teams")
data class TeamsProperties(
    val enabled: Boolean = false,
    val defaultWebhookUrl: String = ""
)
