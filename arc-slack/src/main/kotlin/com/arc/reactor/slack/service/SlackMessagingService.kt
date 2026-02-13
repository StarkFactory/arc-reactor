package com.arc.reactor.slack.service

import com.arc.reactor.slack.model.SlackApiResult
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.reactive.function.client.awaitBodilessEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Sends messages to Slack via the Web API using WebClient (WebFlux-native).
 *
 * Features:
 * - Per-channel rate limiting (1 request/second per channel)
 * - Thread reply support via threadTs parameter
 * - Emoji reaction support
 */
class SlackMessagingService(
    botToken: String,
    private val webClient: WebClient = WebClient.builder()
        .baseUrl("https://slack.com/api")
        .defaultHeader("Authorization", "Bearer $botToken")
        .defaultHeader("Content-Type", "application/json; charset=utf-8")
        .build(),
    private val responseWebClient: WebClient = WebClient.builder().build()
) {
    private val lastRequestTimeByChannel = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Sends a message to a Slack channel.
     *
     * @param channelId Target channel ID
     * @param text Message text (supports mrkdwn)
     * @param threadTs Optional thread timestamp for thread replies
     * @return SlackApiResult with success status and message timestamp
     */
    suspend fun sendMessage(
        channelId: String,
        text: String,
        threadTs: String? = null
    ): SlackApiResult {
        enforceRateLimit(channelId)

        val body = buildMap<String, Any> {
            put("channel", channelId)
            put("text", text)
            if (threadTs != null) put("thread_ts", threadTs)
        }

        return try {
            callSlackApi("chat.postMessage", body)
        } catch (e: Exception) {
            logger.error(e) { "Failed to send message to channel=$channelId" }
            SlackApiResult(ok = false, error = e.message ?: "Unknown error")
        }
    }

    /**
     * Adds an emoji reaction to a message.
     */
    suspend fun addReaction(channelId: String, timestamp: String, emoji: String): SlackApiResult {
        val body = mapOf("channel" to channelId, "timestamp" to timestamp, "name" to emoji)
        return try {
            callSlackApi("reactions.add", body)
        } catch (e: Exception) {
            logger.error(e) { "Failed to add reaction $emoji to channel=$channelId" }
            SlackApiResult(ok = false, error = e.message ?: "Unknown error")
        }
    }

    /**
     * Sends an asynchronous response to Slack via response_url (slash command callback).
     *
     * @return true if HTTP request succeeds, false otherwise.
     */
    suspend fun sendResponseUrl(
        responseUrl: String,
        text: String,
        responseType: String = "ephemeral"
    ): Boolean {
        if (responseUrl.isBlank()) return false

        val body = mapOf(
            "response_type" to responseType,
            "text" to text
        )

        return try {
            responseWebClient.post()
                .uri(responseUrl)
                .bodyValue(body)
                .retrieve()
                .awaitBodilessEntity()
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to send response_url callback" }
            false
        }
    }

    private suspend fun callSlackApi(method: String, body: Map<String, Any>): SlackApiResult {
        return webClient.post()
            .uri("/$method")
            .bodyValue(body)
            .retrieve()
            .awaitBody<SlackApiResult>()
    }

    private suspend fun enforceRateLimit(channelId: String) {
        val lastTime = lastRequestTimeByChannel.computeIfAbsent(channelId) { AtomicLong(0L) }
        val now = System.currentTimeMillis()
        val elapsed = now - lastTime.get()
        if (elapsed < RATE_LIMIT_DELAY_MS) {
            delay(RATE_LIMIT_DELAY_MS - elapsed)
        }
        lastTime.set(System.currentTimeMillis())
    }

    companion object {
        private const val RATE_LIMIT_DELAY_MS = 1000L
    }
}
