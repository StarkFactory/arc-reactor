package com.arc.reactor.slack.service

import com.arc.reactor.support.throwIfCancellation
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
 * WebClient(WebFlux 네이티브)를 사용하여 Slack Web API로 메시지를 전송하는 서비스.
 *
 * 주요 기능:
 * - 채널별 속도 제한 (채널당 초당 1회)
 * - threadTs 파라미터를 통한 스레드 답장 지원
 * - 이모지 리액션 지원
 * - 429/5xx 응답에 대한 재시도 (지수 백오프)
 * - response_url을 통한 비동기 슬래시 명령 응답
 *
 * @param botToken Slack Bot User OAuth 토큰 (xoxb-...)
 * @param maxApiRetries 최대 재시도 횟수 (기본 2)
 * @param retryDefaultDelayMs Retry-After 헤더 없을 때 기본 재시도 지연 (밀리초)
 * @param metricsRecorder 메트릭 기록기
 * @see SlackAutoConfiguration
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
     * Slack 채널에 메시지를 전송한다.
     *
     * @param channelId 대상 채널 ID
     * @param text 메시지 텍스트 (mrkdwn 지원)
     * @param threadTs 스레드 답장용 타임스탬프 (선택)
     * @return 성공 상태 및 메시지 타임스탬프가 포함된 결과
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
            e.throwIfCancellation()
            logger.error(e) { "Failed to send message to channel=$channelId" }
            SlackApiResult(ok = false, error = e.message ?: "Unknown error")
        }
    }

    /**
     * 메시지에 이모지 리액션을 추가한다.
     */
    suspend fun addReaction(channelId: String, timestamp: String, emoji: String): SlackApiResult {
        val body = mapOf("channel" to channelId, "timestamp" to timestamp, "name" to emoji)
        return try {
            callSlackApi("reactions.add", body)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "Failed to add reaction $emoji to channel=$channelId" }
            SlackApiResult(ok = false, error = e.message ?: "Unknown error")
        }
    }

    /**
     * response_url을 통해 Slack에 비동기 응답을 전송한다 (슬래시 명령 콜백).
     *
     * @return HTTP 요청 성공 시 true, 실패 시 false
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
            e.throwIfCancellation()
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
                    "Slack API rate-limited for method=$method, " +
                        "retrying in ${retryAfterMillis}ms (attempt=$attempt/$maxAttempts)"
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
                e.throwIfCancellation()
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
