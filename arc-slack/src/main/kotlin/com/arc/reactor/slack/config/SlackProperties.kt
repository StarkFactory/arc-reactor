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
    val requestTimeoutMs: Long = 30000,

    /** Max retry attempts for Slack Web API on retryable errors (429/5xx) */
    val apiMaxRetries: Int = 2,

    /** Default retry delay (ms) when Retry-After header is missing */
    val apiRetryDefaultDelayMs: Long = 1000,

    /** Enable in-memory deduplication for Slack Events API using event_id */
    val eventDedupEnabled: Boolean = true,

    /** Retention time (seconds) for deduplication event_id cache */
    val eventDedupTtlSeconds: Long = 600,

    /** Max in-memory event_id entries kept for deduplication */
    val eventDedupMaxEntries: Int = 10000
)
