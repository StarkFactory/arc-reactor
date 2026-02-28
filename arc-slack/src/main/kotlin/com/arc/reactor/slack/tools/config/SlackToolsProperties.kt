package com.arc.reactor.slack.tools.config

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "arc.reactor.slack.tools")
data class SlackToolsProperties(
    val enabled: Boolean = false,
    val botToken: String = "",
    val toolExposure: ToolExposureProperties = ToolExposureProperties(),
    val writeIdempotency: WriteIdempotencyProperties = WriteIdempotencyProperties(),
    val resilience: ResilienceProperties = ResilienceProperties()
) {
    @PostConstruct
    fun validate() {
        if (!enabled) return
        require(botToken.isNotBlank()) {
            "SLACK_BOT_TOKEN is required. Set arc.reactor.slack.tools.bot-token."
        }
        require(writeIdempotency.ttlSeconds > 0) {
            "arc.reactor.slack.tools.write-idempotency.ttl-seconds must be greater than 0."
        }
        require(writeIdempotency.maxEntries > 0) {
            "arc.reactor.slack.tools.write-idempotency.max-entries must be greater than 0."
        }
        require(resilience.timeoutMs > 0) {
            "arc.reactor.slack.tools.resilience.timeout-ms must be greater than 0."
        }
        require(resilience.circuitBreaker.failureThreshold > 0) {
            "arc.reactor.slack.tools.resilience.circuit-breaker.failure-threshold must be greater than 0."
        }
        require(resilience.circuitBreaker.openStateDurationMs > 0) {
            "arc.reactor.slack.tools.resilience.circuit-breaker.open-state-duration-ms must be greater than 0."
        }
    }
}

data class ToolExposureProperties(
    val scopeAwareEnabled: Boolean = false,
    val failOpenOnScopeResolutionError: Boolean = true
)

data class WriteIdempotencyProperties(
    val enabled: Boolean = true,
    val ttlSeconds: Long = 30,
    val maxEntries: Int = 5000
)

data class ResilienceProperties(
    val timeoutMs: Long = 5_000,
    val circuitBreaker: CircuitBreakerProperties = CircuitBreakerProperties()
)

data class CircuitBreakerProperties(
    val enabled: Boolean = true,
    val failureThreshold: Int = 3,
    val openStateDurationMs: Long = 30_000
)
