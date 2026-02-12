package com.arc.reactor.line.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the LINE integration module.
 *
 * Prefix: `arc.reactor.line`
 */
@ConfigurationProperties(prefix = "arc.reactor.line")
data class LineProperties(
    /** Enable LINE integration. Default: false (opt-in) */
    val enabled: Boolean = false,

    /** LINE Channel Access Token for Messaging API */
    val channelToken: String = "",

    /** LINE Channel Secret for signature verification */
    val channelSecret: String = "",

    /** Enable HMAC-SHA256 signature verification on incoming LINE requests */
    val signatureVerificationEnabled: Boolean = true,

    /** Maximum concurrent LINE event processing */
    val maxConcurrentRequests: Int = 5,

    /** Request timeout for agent execution (ms) */
    val requestTimeoutMs: Long = 30000
)
