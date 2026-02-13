package com.arc.reactor.slack.service

import com.arc.reactor.slack.model.SlackApiResult
import com.arc.reactor.slack.metrics.NoOpSlackMetricsRecorder
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
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
    private val maxApiRetries: Int = 2,
    private val retryDefaultDelayMs: Long = 1000L,
    private val metricsRecorder: SlackMetricsRecorder = NoOpSlackMetricsRecorder(),
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
            metricsRecorder.recordResponseUrl("success")
            true
        } catch (e: Exception) {
            logger.error(e) { "Failed to send response_url callback" }
            metricsRecorder.recordResponseUrl("failure")
            false
        }
    }

    private suspend fun callSlackApi(method: String, body: Map<String, Any>): SlackApiResult {
        val started = System.currentTimeMillis()
        var attempt = 0
        val maxAttempts = maxApiRetries.coerceAtLeast(0) + 1

        while (true) {
            attempt++
            try {
                val result = webClient.post()
                    .uri("/$method")
                    .bodyValue(body)
                    .retrieve()
                    .awaitBody<SlackApiResult>()
                metricsRecorder.recordApiCall(
                    method = method,
                    outcome = if (result.ok) "success" else "api_error",
                    durationMs = System.currentTimeMillis() - started
                )
                return result
            } catch (e: WebClientResponseException.TooManyRequests) {
                metricsRecorder.recordApiRetry(method = method, reason = "rate_limit")
                if (attempt >= maxAttempts) throw e
                val retryAfterMillis = e.headers.getFirst("Retry-After")
                    ?.toLongOrNull()
                    ?.coerceAtLeast(1L)
                    ?.times(1000)
                    ?: retryDefaultDelayMs.coerceAtLeast(1L)

                logger.warn {
                    "Slack API rate-limited for method=$method, retrying in ${retryAfterMillis}ms (attempt=$attempt/$maxAttempts)"
                }
                delay(retryAfterMillis)
            } catch (e: WebClientResponseException) {
                val reason = if (e.statusCode.is5xxServerError) "server_error" else "client_error"
                metricsRecorder.recordApiRetry(method = method, reason = reason)
                if (!e.statusCode.is5xxServerError || attempt >= maxAttempts) throw e

                val backoffMillis = retryDefaultDelayMs.coerceAtLeast(1L) * attempt
                logger.warn {
                    "Slack API 5xx for method=$method, retrying in ${backoffMillis}ms (attempt=$attempt/$maxAttempts)"
                }
                delay(backoffMillis)
            } catch (e: Exception) {
                metricsRecorder.recordApiCall(
                    method = method,
                    outcome = "exception",
                    durationMs = System.currentTimeMillis() - started
                )
                throw e
            }
        }
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
