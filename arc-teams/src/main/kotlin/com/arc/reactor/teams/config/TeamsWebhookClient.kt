package com.arc.reactor.teams.config

import com.arc.reactor.scheduler.TeamsMessageSender
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient

private val logger = KotlinLogging.logger {}

private const val MAX_MESSAGE_LENGTH = 3000

/**
 * Sends notifications to Microsoft Teams via Incoming Webhook using the MessageCard format.
 *
 * On HTTP errors, logs a warning and does not rethrow — consistent with SlackMessageSender error policy.
 */
class TeamsWebhookClient(
    private val properties: TeamsProperties
) : TeamsMessageSender {

    private val webClient = WebClient.builder().build()

    override fun sendMessage(webhookUrl: String, text: String) {
        val truncated = if (text.length > MAX_MESSAGE_LENGTH) text.take(MAX_MESSAGE_LENGTH) + "\n..." else text
        val payload = buildMessageCard(truncated)
        try {
            webClient.post()
                .uri(webhookUrl)
                .header("Content-Type", "application/json")
                .bodyValue(payload)
                .retrieve()
                .toBodilessEntity()
                .block()
            logger.debug { "Teams webhook message sent to $webhookUrl" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to send Teams webhook message to $webhookUrl" }
        }
    }

    private fun buildMessageCard(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """
            {
              "@type": "MessageCard",
              "@context": "http://schema.org/extensions",
              "summary": "Arc Reactor Briefing",
              "themeColor": "0078D4",
              "text": "$escaped"
            }
        """.trimIndent()
    }
}
