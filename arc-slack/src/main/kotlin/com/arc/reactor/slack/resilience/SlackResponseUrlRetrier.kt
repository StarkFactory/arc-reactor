package com.arc.reactor.slack.resilience

import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.io.IOException

private val logger = KotlinLogging.logger {}

/**
 * response_url 전송 실패 시 지수 백오프로 재시도하는 유틸리티.
 *
 * 5xx 서버 에러 또는 네트워크(IO) 에러에만 재시도하며,
 * 4xx 클라이언트 에러는 즉시 전파한다.
 *
 * @param maxRetries 최대 재시도 횟수 (기본 3)
 * @param initialDelayMs 첫 재시도 지연 (밀리초, 기본 500)
 * @param maxDelayMs 최대 재시도 지연 (밀리초, 기본 8000)
 */
class SlackResponseUrlRetrier(
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 500,
    private val maxDelayMs: Long = 8000
) {

    /**
     * 지수 백오프 재시도로 [block]을 실행한다.
     *
     * 5xx 또는 네트워크 에러 발생 시 최대 [maxRetries]회 재시도하며,
     * 4xx 에러는 재시도 없이 즉시 전파한다.
     */
    suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastException: Exception? = null

        for (attempt in 0..maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                e.throwIfCancellation()

                if (!isRetryable(e)) throw e
                if (attempt >= maxRetries) {
                    lastException = e
                    break
                }

                val delayMs = calculateDelay(attempt)
                logger.warn {
                    "response_url 전송 실패, 재시도 예정: " +
                        "attempt=${attempt + 1}/$maxRetries, " +
                        "delayMs=$delayMs, error=${e.message}"
                }
                delay(delayMs)
            }
        }

        throw lastException ?: IllegalStateException("재시도 루프 완료 후 예외 없음")
    }

    /** 재시도 가능한 에러인지 판별한다 (5xx 또는 네트워크 IO 에러). */
    private fun isRetryable(e: Exception): Boolean = when (e) {
        is WebClientResponseException -> e.statusCode.is5xxServerError
        is IOException -> true
        else -> false
    }

    /** 지수 백오프 지연을 계산한다: initialDelay * 2^attempt, maxDelay로 상한. */
    private fun calculateDelay(attempt: Int): Long {
        val delay = initialDelayMs * (1L shl attempt)
        return delay.coerceAtMost(maxDelayMs)
    }
}
