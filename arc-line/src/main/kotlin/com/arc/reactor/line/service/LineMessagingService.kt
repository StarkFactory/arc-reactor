package com.arc.reactor.line.service

import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

private val logger = KotlinLogging.logger {}

/**
 * Sends messages to LINE via the Messaging API using WebClient (WebFlux-native).
 *
 * Features:
 * - Reply message with replyToken (preferred, free of charge)
 * - Push message fallback (when replyToken expires)
 */
class LineMessagingService(
    channelToken: String,
    private val webClient: WebClient = WebClient.builder()
        .baseUrl("https://api.line.me/v2/bot/message")
        .defaultHeader("Authorization", "Bearer $channelToken")
        .defaultHeader("Content-Type", "application/json")
        .build()
) {

    /**
     * Sends a reply message using a replyToken.
     *
     * @param replyToken Token from the webhook event (valid for ~30 seconds)
     * @param text Message text to send
     * @return true if reply succeeded, false otherwise
     */
    suspend fun replyMessage(replyToken: String, text: String): Boolean {
        val body = mapOf(
            "replyToken" to replyToken,
            "messages" to listOf(mapOf("type" to "text", "text" to text))
        )

        return try {
            val response = webClient.post()
                .uri("/reply")
                .bodyValue(body)
                .retrieve()
                .awaitBody<Map<String, Any?>>()
            logger.debug { "LINE reply response: $response" }
            true
        } catch (e: Exception) {
            logger.warn(e) { "LINE replyMessage failed (token may be expired)" }
            false
        }
    }

    /**
     * Sends a push message to a specific user/group/room.
     *
     * @param to Target userId, groupId, or roomId
     * @param text Message text to send
     */
    suspend fun pushMessage(to: String, text: String) {
        val body = mapOf(
            "to" to to,
            "messages" to listOf(mapOf("type" to "text", "text" to text))
        )

        try {
            val response = webClient.post()
                .uri("/push")
                .bodyValue(body)
                .retrieve()
                .awaitBody<Map<String, Any?>>()
            logger.debug { "LINE push response: $response" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to push message to=$to" }
        }
    }
}
