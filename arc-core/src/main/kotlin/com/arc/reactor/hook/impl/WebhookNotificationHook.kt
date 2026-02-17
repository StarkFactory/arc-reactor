package com.arc.reactor.hook.impl

import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Webhook Notification Hook
 *
 * Sends HTTP POST notifications to an external URL after agent execution completes.
 * Useful for integrating with monitoring systems, Slack, or custom dashboards.
 *
 * ## Configuration
 * ```yaml
 * arc:
 *   reactor:
 *     webhook:
 *       enabled: true
 *       url: https://example.com/webhook
 *       timeout-ms: 5000
 *       include-conversation: false
 * ```
 *
 * ## Behavior
 * - Fires asynchronously after agent completes (success or failure)
 * - Never blocks or fails the agent response (fail-open, order 200)
 * - Timeout prevents hanging on slow endpoints
 */
class WebhookNotificationHook(
    private val webhookProperties: WebhookProperties,
    private val webClient: WebClient = WebClient.create()
) : AfterAgentCompleteHook {

    // 200+: Late hooks (notifications, cleanup)
    override val order: Int = 200

    // Webhook failure should never block the agent
    override val failOnError: Boolean = false

    override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
        val url = webhookProperties.url
        if (url.isBlank()) {
            logger.warn { "Webhook URL is blank, skipping notification" }
            return
        }

        val payload = buildPayload(context, response)

        try {
            webClient.post()
                .uri(url)
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofMillis(webhookProperties.timeoutMs))
                .onErrorResume { e ->
                    e.throwIfCancellation()
                    logger.warn { "Webhook POST to $url failed: ${e.message}" }
                    Mono.empty()
                }
                .awaitSingleOrNull()

            logger.debug { "Webhook notification sent to $url for runId=${context.runId}" }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn { "Webhook notification failed for runId=${context.runId}: ${e.message}" }
        }
    }

    private fun buildPayload(context: HookContext, response: AgentResponse): Map<String, Any?> {
        val payload = mutableMapOf<String, Any?>(
            "event" to "AGENT_COMPLETE",
            "timestamp" to Instant.now().toString(),
            "runId" to context.runId,
            "userId" to context.userId,
            "success" to response.success,
            "toolsUsed" to response.toolsUsed,
            "durationMs" to response.totalDurationMs
        )

        if (response.success) {
            payload["contentPreview"] = response.response?.take(200)
        } else {
            payload["errorMessage"] = response.errorMessage
        }

        if (webhookProperties.includeConversation) {
            payload["userPrompt"] = context.userPrompt
            payload["fullResponse"] = response.response
        }

        return payload
    }
}

/**
 * Webhook configuration properties.
 *
 * Configured under `arc.reactor.webhook.*` prefix.
 */
data class WebhookProperties(
    /** Webhook enabled */
    val enabled: Boolean = false,

    /** POST target URL */
    val url: String = "",

    /** HTTP timeout in milliseconds */
    val timeoutMs: Long = 5000,

    /** Whether to include full conversation in the payload */
    val includeConversation: Boolean = false
)
