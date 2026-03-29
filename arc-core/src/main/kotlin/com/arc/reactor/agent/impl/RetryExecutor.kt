package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.RetryProperties
import com.arc.reactor.resilience.CircuitBreaker
import com.arc.reactor.support.throwIfCancellation
import kotlinx.coroutines.delay
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * LLM 호출 등 일시적 에러가 발생할 수 있는 작업에 대한 재시도 로직을 실행하는 executor.
 *
 * 재시도 전략:
 * - **지수 백오프**: initialDelayMs * multiplier^attempt (maxDelayMs 상한)
 * - **Jitter**: +/-25% 무작위 변동으로 thundering herd 방지
 * - **일시적 에러만 재시도**: [isTransientError]로 판별
 * - **Circuit Breaker 통합**: 설정 시 재시도 블록을 circuit breaker로 감싼다
 *
 * CancellationException은 항상 재던져 구조적 동시성을 보존한다.
 *
 * @see com.arc.reactor.agent.config.RetryProperties 재시도 설정 (maxAttempts, delay 등)
 * @see com.arc.reactor.resilience.CircuitBreaker 서킷 브레이커 (선택 사항)
 * @see SpringAiAgentExecutor LLM 호출 시 이 executor를 통해 재시도
 */
internal class RetryExecutor(
    private val retry: RetryProperties,
    private val circuitBreaker: CircuitBreaker?,
    private val isTransientError: (Exception) -> Boolean,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val randomFn: () -> Double = Math::random
) {

    /**
     * 재시도 정책을 적용하여 블록을 실행한다.
     *
     * @param block 실행할 suspend 블록
     * @return 블록의 실행 결과
     * @throws Exception 최대 재시도 횟수 초과 또는 비일시적 에러 발생 시
     */
    suspend fun <T> execute(block: suspend () -> T): T {
        val retryBlock: suspend () -> T = {
            val maxAttempts = retry.maxAttempts.coerceAtLeast(1)
            var lastException: Exception? = null
            var result: T? = null
            var completed = false

            repeat(maxAttempts) { attempt ->
                if (completed) return@repeat
                try {
                    result = block()
                    completed = true
                } catch (e: Exception) {
                    e.throwIfCancellation()
                    lastException = e

                    // 비일시적 에러이거나 마지막 시도이면 즉시 던짐
                    if (!isTransientError(e) || attempt == maxAttempts - 1) {
                        throw e
                    }

                    // ── 지수 백오프 + jitter 계산 ──
                    val baseDelay = minOf(
                        (retry.initialDelayMs * Math.pow(retry.multiplier, attempt.toDouble())).toLong(),
                        retry.maxDelayMs
                    )
                    // +/-25% jitter로 thundering herd 방지
                    val jitter = (baseDelay * 0.25 * (randomFn() * 2 - 1)).toLong()
                    val delayMs = (baseDelay + jitter).coerceAtLeast(0)
                    logger.warn {
                        "Transient error (attempt ${attempt + 1}/$maxAttempts), " +
                            "retrying in ${delayMs}ms: ${e.message}"
                    }
                    delayFn(delayMs)
                }
            }

            if (completed) {
                @Suppress("UNCHECKED_CAST")
                checkNotNull(result) { "재시도 완료되었으나 결과가 null" } as T
            } else {
                throw lastException ?: IllegalStateException("재시도 횟수 소진")
            }
        }

        // Circuit breaker가 설정되어 있으면 재시도 블록을 감싼다
        return if (circuitBreaker != null) {
            circuitBreaker.execute(retryBlock)
        } else {
            retryBlock()
        }
    }
}
