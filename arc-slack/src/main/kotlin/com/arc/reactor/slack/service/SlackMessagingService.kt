package com.arc.reactor.slack.service

import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.slack.model.SlackApiResult
import com.arc.reactor.slack.metrics.NoOpSlackMetricsRecorder
import com.arc.reactor.slack.metrics.SlackMetricsRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingleOrNull
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Duration
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
    private val responseWebClient: WebClient = WebClient.builder().build(),
    private val allowedResponseHosts: Set<String> = DEFAULT_ALLOWED_RESPONSE_HOSTS
) {
    /** 채널별 마지막 요청 시각 (최대 1000 항목, 초과 시 오래된 항목 제거) */
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
            logger.error(e) { "메시지 전송 실패: channel=$channelId" }
            SlackApiResult(ok = false, error = "Slack API 호출 실패 (${e.javaClass.simpleName})")
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
            logger.error(e) { "리액션 추가 실패: emoji=$emoji, channel=$channelId" }
            SlackApiResult(ok = false, error = "Slack API 호출 실패 (${e.javaClass.simpleName})")
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
        if (!isAllowedResponseUrl(responseUrl)) {
            logger.warn { "비허용 호스트의 response_url 차단: $responseUrl" }
            return false
        }

        val body = mapOf(
            "response_type" to responseType,
            "text" to text
        )

        return try {
            responseWebClient.post()
                .uri(responseUrl)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(RESPONSE_URL_TIMEOUT_SECONDS))
                .awaitSingleOrNull()
            metricsRecorder.recordResponseUrl("success")
            true
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "response_url 콜백 전송 실패" }
            metricsRecorder.recordResponseUrl("failure")
            false
        }
    }

    /**
     * Slack Web API를 호출하고, 429/5xx 시 재시도한다.
     *
     * @param method 호출할 API 메서드 (예: "chat.postMessage")
     * @param body 요청 본문
     */
    private suspend fun callSlackApi(method: String, body: Map<String, Any>): SlackApiResult {
        val started = System.currentTimeMillis()
        var attempt = 0
        val maxAttempts = maxApiRetries.coerceAtLeast(0) + 1

        while (true) {
            attempt++
            try {
                return executeApiCall(method, body, started)
            } catch (e: WebClientResponseException.TooManyRequests) {
                handleRateLimitRetry(e, method, attempt, maxAttempts)
            } catch (e: WebClientResponseException) {
                handleServerErrorRetry(e, method, attempt, maxAttempts)
            } catch (e: Exception) {
                e.throwIfCancellation()
                recordApiException(method, started)
                throw e
            }
        }
    }

    /**
     * 단일 HTTP 요청을 실행하고 결과를 반환한다.
     */
    private suspend fun executeApiCall(
        method: String,
        body: Map<String, Any>,
        started: Long
    ): SlackApiResult {
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
    }

    /**
     * 429 Rate Limit 응답을 처리한다. Retry-After 헤더가 있으면 해당 시간만큼 대기한다.
     *
     * @throws WebClientResponseException.TooManyRequests 최대 재시도 횟수 초과 시
     */
    private suspend fun handleRateLimitRetry(
        e: WebClientResponseException.TooManyRequests,
        method: String,
        attempt: Int,
        maxAttempts: Int
    ) {
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
    }

    /**
     * 5xx 서버 오류 응답을 처리한다. 5xx가 아니거나 최대 재시도 초과 시 예외를 다시 던진다.
     *
     * @throws WebClientResponseException 5xx가 아닌 오류 또는 최대 재시도 초과 시
     */
    private suspend fun handleServerErrorRetry(
        e: WebClientResponseException,
        method: String,
        attempt: Int,
        maxAttempts: Int
    ) {
        val reason = if (e.statusCode.is5xxServerError) "server_error" else "client_error"
        metricsRecorder.recordApiRetry(method = method, reason = reason)
        if (!e.statusCode.is5xxServerError || attempt >= maxAttempts) throw e
        val backoffMillis = retryDefaultDelayMs.coerceAtLeast(1L) * attempt
        logger.warn {
            "Slack API 5xx for method=$method, retrying in ${backoffMillis}ms (attempt=$attempt/$maxAttempts)"
        }
        delay(backoffMillis)
    }

    /**
     * 예상치 못한 예외 발생 시 메트릭을 기록한다.
     */
    private fun recordApiException(method: String, started: Long) {
        metricsRecorder.recordApiCall(
            method = method,
            outcome = "exception",
            durationMs = System.currentTimeMillis() - started
        )
    }

    private suspend fun enforceRateLimit(channelId: String) {
        if (lastRequestTimeByChannel.size > MAX_RATE_LIMIT_ENTRIES) {
            val keysToRemove = lastRequestTimeByChannel.keys.take(lastRequestTimeByChannel.size / 4)
            for (key in keysToRemove) {
                lastRequestTimeByChannel.remove(key)
            }
        }
        val lastTime = lastRequestTimeByChannel.computeIfAbsent(channelId) { AtomicLong(0L) }
        val now = System.currentTimeMillis()
        val elapsed = now - lastTime.get()
        if (elapsed < RATE_LIMIT_DELAY_MS) {
            delay(RATE_LIMIT_DELAY_MS - elapsed)
        }
        lastTime.set(System.currentTimeMillis())
    }

    /**
     * Agents & AI Apps 스레드에 타이핑 상태를 표시한다.
     *
     * @param channelId 대화 채널 ID (DM 채널)
     * @param threadTs 스레드 타임스탬프
     * @param status 상태 메시지 (빈 문자열이면 상태 제거)
     */
    suspend fun setAssistantThreadStatus(
        channelId: String,
        threadTs: String,
        status: String = ""
    ): SlackApiResult {
        val body = buildMap<String, Any> {
            put("channel_id", channelId)
            put("thread_ts", threadTs)
            put("status", status)
        }
        return try {
            callSlackApi("assistant.threads.setStatus", body)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "assistant.threads.setStatus 실패: channel=$channelId" }
            SlackApiResult(ok = false, error = "assistant.threads.setStatus 실패")
        }
    }

    /**
     * Agents & AI Apps 스레드에 추천 프롬프트를 설정한다.
     *
     * @param channelId 대화 채널 ID (DM 채널)
     * @param threadTs 스레드 타임스탬프
     * @param prompts 추천 프롬프트 목록 (title + message 쌍)
     */
    suspend fun setAssistantSuggestedPrompts(
        channelId: String,
        threadTs: String,
        prompts: List<Map<String, String>>
    ): SlackApiResult {
        val body = buildMap<String, Any> {
            put("channel_id", channelId)
            put("thread_ts", threadTs)
            put("prompts", prompts)
        }
        return try {
            callSlackApi("assistant.threads.setSuggestedPrompts", body)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "assistant.threads.setSuggestedPrompts 실패: channel=$channelId" }
            SlackApiResult(ok = false, error = "assistant.threads.setSuggestedPrompts 실패")
        }
    }

    /**
     * Agents & AI Apps 스레드 제목을 설정한다.
     *
     * @param channelId 대화 채널 ID (DM 채널)
     * @param threadTs 스레드 타임스탬프
     * @param title 스레드 제목
     */
    suspend fun setAssistantThreadTitle(
        channelId: String,
        threadTs: String,
        title: String
    ): SlackApiResult {
        val body = buildMap<String, Any> {
            put("channel_id", channelId)
            put("thread_ts", threadTs)
            put("title", title)
        }
        return try {
            callSlackApi("assistant.threads.setTitle", body)
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.warn(e) { "assistant.threads.setTitle 실패: channel=$channelId" }
            SlackApiResult(ok = false, error = "assistant.threads.setTitle 실패")
        }
    }

    /** response_url이 허용된 호스트인지 확인한다 (SSRF 방지). */
    private fun isAllowedResponseUrl(url: String): Boolean {
        val uri = try {
            java.net.URI(url)
        } catch (e: Exception) {
            logger.warn(e) { "response_url 검증 실패: ${url.take(50)}" }
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        return allowedResponseHosts.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
    }

    companion object {
        private const val RATE_LIMIT_DELAY_MS = 1000L
        private const val RESPONSE_URL_TIMEOUT_SECONDS = 10L
        private const val MAX_RATE_LIMIT_ENTRIES = 1_000
        private val DEFAULT_ALLOWED_RESPONSE_HOSTS = setOf("hooks.slack.com", "slack.com")
    }
}
