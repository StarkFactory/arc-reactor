package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.RetryProperties
import com.arc.reactor.agent.metrics.EvaluationMetricsCollector
import com.arc.reactor.agent.metrics.ExecutionStage
import com.arc.reactor.agent.metrics.NoOpEvaluationMetricsCollector
import com.arc.reactor.agent.metrics.recordError
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
 * ## R248: Evaluation 메트릭 자동 기록
 *
 * [evaluationMetricsCollector]가 주입되면 재시도 소진 후 최종 throw 직전에
 * [EvaluationMetricsCollector.recordError]를 호출하여 `execution.error{stage="llm_call"}` 또는
 * 호출자가 지정한 stage로 자동 기록한다. 중간 재시도 성공 시에는 기록하지 않는다 (일시적
 * 오류는 관측 가치가 낮고 로그로 충분).
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
    private val randomFn: () -> Double = Math::random,
    /**
     * R248: 최종 실패 시 `execution.error` 메트릭을 기록할 수집기. 기본값 NoOp으로 backward compat.
     */
    private val evaluationMetricsCollector: EvaluationMetricsCollector = NoOpEvaluationMetricsCollector,
    /**
     * R248: [recordError]가 사용할 stage. 현재 RetryExecutor의 유일한 호출자인
     * `SpringAiAgentExecutor`는 LLM 호출을 래핑하므로 `LLM_CALL`이 기본값. 다른 경로에서
     * RetryExecutor를 사용하면 적절한 stage로 override한다.
     */
    private val errorStage: ExecutionStage = ExecutionStage.LLM_CALL
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
                        // R248: 최종 실패 기록 (중간 재시도는 기록하지 않음 — 로그로 충분)
                        evaluationMetricsCollector.recordError(errorStage, e)
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
                    // R323 fix: `e.message`를 로그에 그대로 쓰면 Google GenAI ClientException 등
                    // 외부 SDK가 전달하는 메시지에 API key fragment/내부 endpoint URL/quota account ID가
                    // 포함될 수 있다 → log shipper(Datadog, Slack alert)로 전달되면 정보 노출.
                    // CLAUDE.md Gotcha #9(HTTP 응답 노출 금지)의 log shipper 확장 — message 대신
                    // 클래스명만 기록하여 debuggability 유지. 원본 예외는 아래 `throw e` 경로에서 이미
                    // 서버 로그로 캡처됨.
                    logger.warn {
                        "Transient error (attempt ${attempt + 1}/$maxAttempts), " +
                            "retrying in ${delayMs}ms: ${e.javaClass.simpleName}"
                    }
                    delayFn(delayMs)
                }
            }

            if (completed) {
                @Suppress("UNCHECKED_CAST")
                checkNotNull(result) { "재시도 완료되었으나 결과가 null" } as T
            } else {
                // R248: 이 경로는 repeat 블록이 throw 없이 빠져나간 극단 케이스 (이론상 도달 불가)
                // 이미 위의 catch 블록에서 기록되었으므로 여기서는 throw만.
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
