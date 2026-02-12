package com.arc.reactor.slack.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the Slack integration module.
 *
 * Prefix: `arc.reactor.slack`
 */
@ConfigurationProperties(prefix = "arc.reactor.slack")
data class SlackProperties(
    /** Enable Slack integration. Default: false (opt-in) */
    val enabled: Boolean = false,

    /** Slack Bot User OAuth Token (xoxb-...) */
    val botToken: String = "",

    /** Slack signing secret for request signature verification */
    val signingSecret: String = "",

    /** Enable HMAC-SHA256 signature verification on incoming Slack requests */
    val signatureVerificationEnabled: Boolean = true,

    /** Maximum allowed clock skew for signature verification (seconds) */
    val timestampToleranceSeconds: Long = 300,

    /** Maximum concurrent Slack event processing */
    val maxConcurrentRequests: Int = 5,

    /** Request timeout for agent execution (ms) */
    val requestTimeoutMs: Long = 30000
)
