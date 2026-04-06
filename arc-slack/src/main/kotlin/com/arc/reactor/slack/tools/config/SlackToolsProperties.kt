package com.arc.reactor.slack.tools.config

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Slack 도구 모듈 설정 프로퍼티.
 *
 * 프리픽스: `arc.reactor.slack.tools`
 *
 * 봇 토큰, 도구 노출(scope-aware), Canvas, 쓰기 멱등성,
 * 복원력(타임아웃/Circuit Breaker) 설정을 관리한다.
 */
@ConfigurationProperties(prefix = "arc.reactor.slack.tools")
data class SlackToolsProperties(
    val enabled: Boolean = false,
    val botToken: String = "",
    val toolExposure: ToolExposureProperties = ToolExposureProperties(),
    val canvas: CanvasToolsProperties = CanvasToolsProperties(),
    val writeIdempotency: WriteIdempotencyProperties = WriteIdempotencyProperties(),
    val resilience: ResilienceProperties = ResilienceProperties()
) {
    @PostConstruct
    fun validate() {
        if (!enabled) return
        if (botToken.isBlank()) {
            println("⚠️ [SlackToolsProperties] SLACK_BOT_TOKEN이 비어있습니다. Slack 도구(find_user, find_channel 등)가 비활성화됩니다.")
            return
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
        require(canvas.maxOwnedCanvasIds > 0) {
            "arc.reactor.slack.tools.canvas.max-owned-canvas-ids must be greater than 0."
        }
    }
}

/** 도구 노출 정책 설정. 스코프 기반 자동 필터링을 제어한다. */
data class ToolExposureProperties(
    val scopeAwareEnabled: Boolean = false,
    val failOpenOnScopeResolutionError: Boolean = true,
    val conversationScopeMode: ConversationScopeMode = ConversationScopeMode.PUBLIC_ONLY
)

/** 대화 스코프 모드. 공개 채널만 또는 비공개/DM 포함 여부를 결정한다. */
enum class ConversationScopeMode {
    PUBLIC_ONLY,
    INCLUDE_PRIVATE_AND_DM
}

/** Canvas 도구 설정. */
data class CanvasToolsProperties(
    val enabled: Boolean = false,
    val allowlistEnforced: Boolean = true,
    val maxOwnedCanvasIds: Int = 5000
)

/** 쓰기 작업 멱등성 설정. */
data class WriteIdempotencyProperties(
    val enabled: Boolean = true,
    val ttlSeconds: Long = 30,
    val maxEntries: Int = 5000
)

/** 복원력(Resilience) 설정. 타임아웃 및 Circuit Breaker를 제어한다. */
data class ResilienceProperties(
    val timeoutMs: Long = 5_000,
    val circuitBreaker: CircuitBreakerProperties = CircuitBreakerProperties()
)

/** Circuit Breaker 설정. */
data class CircuitBreakerProperties(
    val enabled: Boolean = true,
    val failureThreshold: Int = 3,
    val openStateDurationMs: Long = 30_000
)
