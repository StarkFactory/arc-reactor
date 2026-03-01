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

    /**
     * Slack transport mode.
     * - EVENTS_API: receive payloads over HTTP endpoints
     * - SOCKET_MODE: receive payloads over WebSocket (no public callback URL required)
     */
    val transportMode: SlackTransportMode = SlackTransportMode.SOCKET_MODE,

    /** Slack Bot User OAuth Token (xoxb-...) */
    val botToken: String = "",

    /** Slack App-Level Token for Socket Mode (xapp-...) */
    val appToken: String = "",

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

    /**
     * If true, reject immediately when the processing semaphore is saturated.
     * Prevents coroutine queue buildup under burst traffic.
     */
    val failFastOnSaturation: Boolean = true,

    /**
     * If true, send busy notifications to Slack when events/commands are dropped.
     * Keep false in high-load environments to avoid amplifying outbound traffic.
     */
    val notifyOnDrop: Boolean = false,

    /** Max retry attempts for Slack Web API on retryable errors (429/5xx) */
    val apiMaxRetries: Int = 2,

    /** Default retry delay (ms) when Retry-After header is missing */
    val apiRetryDefaultDelayMs: Long = 1000,

    /** Enable in-memory deduplication for Slack Events API using event_id */
    val eventDedupEnabled: Boolean = true,

    /** Retention time (seconds) for deduplication event_id cache */
    val eventDedupTtlSeconds: Long = 600,

    /** Max in-memory event_id entries kept for deduplication */
    val eventDedupMaxEntries: Int = 10000,

    /** Track Slack threads initiated by Arc Reactor to avoid unrelated thread side effects */
    val threadTrackingEnabled: Boolean = true,

    /** Retention time (seconds) for tracked Slack threads */
    val threadTrackingTtlSeconds: Long = 86400,

    /** Max in-memory tracked thread entries */
    val threadTrackingMaxEntries: Int = 20000,

    /** Socket Mode WebSocket backend implementation */
    val socketBackend: SlackSocketBackend = SlackSocketBackend.JAVA_WEBSOCKET,

    /** Initial retry delay for Socket Mode startup connection failures (ms) */
    val socketConnectRetryInitialDelayMs: Long = 1000,

    /** Max retry delay for Socket Mode startup connection failures (ms) */
    val socketConnectRetryMaxDelayMs: Long = 30000
)

enum class SlackTransportMode {
    EVENTS_API,
    SOCKET_MODE
}

enum class SlackSocketBackend {
    JAVA_WEBSOCKET,
    TYRUS
}
