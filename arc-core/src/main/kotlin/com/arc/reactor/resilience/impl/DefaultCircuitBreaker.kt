package com.arc.reactor.resilience.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.resilience.CircuitBreaker
import com.arc.reactor.resilience.CircuitBreakerMetrics
import com.arc.reactor.resilience.CircuitBreakerOpenException
import com.arc.reactor.resilience.CircuitBreakerState
import com.arc.reactor.support.throwIfCancellation
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Kotlin 네이티브 Circuit Breaker 구현체 (외부 라이브러리 의존성 없음).
 *
 * Atomic 연산으로 스레드 안전성을 보장한다. 락(lock)을 사용하지 않으므로
 * 높은 동시성 환경에서도 성능 병목이 발생하지 않는다.
 *
 * ## 상태 전이 다이어그램
 * ```
 * CLOSED ──(연속 실패 >= threshold)──→ OPEN
 *     ↑                                  │
 *     │                                  │ (resetTimeoutMs 경과)
 *     │                                  ↓
 *     └───────(성공)───── HALF_OPEN ──(실패)──→ OPEN
 * ```
 *
 * ## 왜 3단계 상태 전이인가?
 * - **CLOSED**: 정상 상태. 모든 호출 허용, 실패 횟수만 추적
 * - **OPEN**: 장애 상태. 모든 호출 즉시 거부하여 다운스트림 부하를 차단
 *   → 왜 즉시 거부하는가: 이미 실패 중인 서비스에 계속 요청하면
 *     타임아웃으로 리소스가 소진되어 연쇄 장애(cascading failure) 발생
 * - **HALF_OPEN**: 복구 탐색 상태. 제한된 수의 시험 호출만 허용
 *   → 왜 바로 CLOSED로 가지 않는가: 서비스가 불완전하게 복구된 경우
 *     대량 트래픽이 한꺼번에 몰리면 다시 장애가 발생할 수 있다
 *
 * @param failureThreshold 회로를 열기 위한 연속 실패 횟수 (기본값 5)
 * @param resetTimeoutMs OPEN에서 HALF_OPEN으로 전환하기 위한 대기 시간(ms) (기본값 30초)
 * @param halfOpenMaxCalls HALF_OPEN 상태에서 허용되는 시험 호출 수 (기본값 1)
 * @param name 로깅 및 예외 메시지에 사용되는 식별자
 * @param clock 테스트 가능성을 위한 시간 소스 (기본값: System.currentTimeMillis)
 * @param agentMetrics 상태 전이를 기록하는 메트릭 레코더
 *
 * @see CircuitBreaker 인터페이스 계약
 * @see com.arc.reactor.resilience.CircuitBreakerRegistry 이름별 인스턴스 관리
 */
class DefaultCircuitBreaker(
    private val failureThreshold: Int = 5,
    private val resetTimeoutMs: Long = 30_000,
    private val halfOpenMaxCalls: Int = 1,
    private val name: String = "default",
    private val clock: () -> Long = System::currentTimeMillis,
    private val agentMetrics: AgentMetrics = NoOpAgentMetrics()
) : CircuitBreaker {

    /** 현재 회로 상태. CAS(Compare-And-Swap)로 원자적 전이를 보장한다. */
    private val state = AtomicReference(CircuitBreakerState.CLOSED)
    /** 연속 실패 횟수 카운터 */
    private val failureCount = AtomicInteger(0)
    /** 누적 성공 횟수 카운터 */
    private val successCount = AtomicInteger(0)
    /** HALF_OPEN 상태에서 진행된 시험 호출 수 */
    private val halfOpenCallCount = AtomicInteger(0)
    /** 마지막 실패 발생 시각(epoch ms) */
    private val lastFailureTime = AtomicLong(0)
    /** OPEN 상태로 전환된 시각(epoch ms). HALF_OPEN 전환 판단에 사용 */
    private val openedAt = AtomicLong(0)

    override suspend fun <T> execute(block: suspend () -> T): T {
        val currentState = evaluateState()

        when (currentState) {
            CircuitBreakerState.OPEN -> {
                // 회로가 열려있으면 즉시 거부하여 다운스트림 부하를 차단한다
                logger.debug { "서킷 브레이커 '$name' OPEN 상태, 호출 거부" }
                throw CircuitBreakerOpenException(name)
            }
            CircuitBreakerState.HALF_OPEN -> {
                // HALF_OPEN에서는 제한된 시험 호출만 허용한다
                if (halfOpenCallCount.incrementAndGet() > halfOpenMaxCalls) {
                    // 시험 호출 한도 초과 — 여전히 OPEN으로 취급
                    halfOpenCallCount.decrementAndGet()
                    throw CircuitBreakerOpenException(name)
                }
            }
            CircuitBreakerState.CLOSED -> { /* 정상 진행 */ }
        }

        return try {
            val result = block()
            onSuccess()
            result
        } catch (e: Exception) {
            // CancellationException은 코루틴 제어 신호이므로 Circuit Breaker 실패로 처리하지 않는다.
            // 이를 먼저 체크하지 않으면 정상적인 코루틴 취소가 실패로 집계되어 회로가 열릴 수 있다.
            e.throwIfCancellation()
            onFailure()
            throw e
        }
    }

    override fun state(): CircuitBreakerState = evaluateState()

    /** 모든 카운터를 초기화하고 CLOSED 상태로 복원한다. */
    override fun reset() {
        state.set(CircuitBreakerState.CLOSED)
        failureCount.set(0)
        successCount.set(0)
        halfOpenCallCount.set(0)
        lastFailureTime.set(0)
        openedAt.set(0)
        logger.info { "서킷 브레이커 '$name' CLOSED로 초기화" }
    }

    override fun metrics(): CircuitBreakerMetrics = CircuitBreakerMetrics(
        failureCount = failureCount.get(),
        successCount = successCount.get(),
        state = evaluateState(),
        lastFailureTime = lastFailureTime.get().takeIf { it > 0 }
    )

    /**
     * 유효 상태를 평가한다. OPEN → HALF_OPEN 타임아웃 전환을 처리한다.
     *
     * 왜 evaluateState()에서 HALF_OPEN 전환을 하는가:
     * 별도의 타이머 스레드 없이 호출 시점에 경과 시간을 확인하여 전환한다.
     * 이 방식은 추가 스레드 없이도 정확한 타이밍을 보장하며,
     * 호출이 없는 동안은 불필요한 상태 체크를 하지 않는다.
     *
     * CAS(compareAndSet)를 사용하여 여러 스레드가 동시에 전환을 시도해도
     * 정확히 하나의 스레드만 전환에 성공하도록 보장한다.
     */
    private fun evaluateState(): CircuitBreakerState {
        val current = state.get()
        if (current == CircuitBreakerState.OPEN) {
            val elapsed = clock() - openedAt.get()
            if (elapsed >= resetTimeoutMs) {
                // CAS: 정확히 하나의 스레드만 OPEN → HALF_OPEN 전환을 수행
                if (state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN)) {
                    halfOpenCallCount.set(0)
                    logger.info { "서킷 브레이커 '$name' 상태 전이 OPEN → HALF_OPEN (${elapsed}ms 경과)" }
                    agentMetrics.recordCircuitBreakerStateChange(
                        name, CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN
                    )
                }
                return state.get()
            }
        }
        return current
    }

    /**
     * 호출 성공 시 상태를 업데이트한다.
     *
     * - HALF_OPEN에서 성공: 서비스 복구가 확인되었으므로 CLOSED로 전환
     * - CLOSED에서 성공: 연속 실패 카운터를 리셋하여 일시적 오류를 용서
     */
    private fun onSuccess() {
        val current = state.get()
        if (current == CircuitBreakerState.HALF_OPEN) {
            // CAS: 하나의 스레드만 HALF_OPEN → CLOSED 전환을 수행
            if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED)) {
                failureCount.set(0)
                successCount.incrementAndGet()
                halfOpenCallCount.set(0)
                logger.info { "서킷 브레이커 '$name' 상태 전이 HALF_OPEN → CLOSED (복구 확인)" }
                agentMetrics.recordCircuitBreakerStateChange(
                    name, CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED
                )
            }
            // CAS 실패: 다른 스레드가 이미 전환했으므로 아무 작업 불필요
        } else {
            successCount.incrementAndGet()
            failureCount.set(0) // 성공 시 연속 실패 카운터를 리셋한다
        }
    }

    /**
     * 호출 실패 시 상태를 업데이트한다.
     *
     * - HALF_OPEN에서 실패: 복구가 아직 불완전하므로 다시 OPEN으로 전환
     * - CLOSED에서 실패: 연속 실패를 누적하고, threshold에 도달하면 OPEN으로 전환
     *
     * 왜 연속(consecutive) 실패만 추적하는가:
     * 간헐적 실패는 정상적인 네트워크 동작이다.
     * 연속 실패만 장애를 의미하며, 중간에 성공이 있으면 카운터가 리셋된다.
     */
    private fun onFailure() {
        lastFailureTime.set(clock())
        val current = state.get()

        if (current == CircuitBreakerState.HALF_OPEN) {
            // CAS: 하나의 스레드만 HALF_OPEN → OPEN 전환을 수행
            if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.OPEN)) {
                openedAt.set(clock())
                halfOpenCallCount.set(0)
                logger.warn { "서킷 브레이커 '$name' 상태 전이 HALF_OPEN → OPEN (시험 호출 실패)" }
                agentMetrics.recordCircuitBreakerStateChange(
                    name, CircuitBreakerState.HALF_OPEN, CircuitBreakerState.OPEN
                )
            }
        } else if (current == CircuitBreakerState.CLOSED) {
            val failures = failureCount.incrementAndGet()
            if (failures >= failureThreshold) {
                // CAS: 하나의 스레드만 CLOSED → OPEN 전환을 수행
                if (state.compareAndSet(CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN)) {
                    openedAt.set(clock())
                    logger.warn {
                        "서킷 브레이커 '$name' 상태 전이 CLOSED → OPEN " +
                            "(failures=$failures, threshold=$failureThreshold)"
                    }
                    agentMetrics.recordCircuitBreakerStateChange(
                        name, CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN
                    )
                }
            }
        }
    }
}
