package com.arc.reactor.resilience

/**
 * 오류를 추적하고 실패율이 너무 높으면 호출을 단락(short-circuit)하여
 * 연쇄 장애(cascading failure)를 방지하는 Circuit Breaker.
 *
 * ## 상태 전이
 * ```
 * CLOSED ──(failures >= threshold)──> OPEN
 * OPEN ──(resetTimeout 경과)──> HALF_OPEN
 * HALF_OPEN ──(성공)──> CLOSED
 * HALF_OPEN ──(실패)──> OPEN
 * ```
 *
 * ## 사용 예시
 * ```kotlin
 * val cb = DefaultCircuitBreaker(properties)
 * val result = cb.execute { riskyCall() }
 * ```
 *
 * @see com.arc.reactor.resilience.impl.DefaultCircuitBreaker 기본 구현체
 * @see CircuitBreakerRegistry 이름별 Circuit Breaker 인스턴스 관리
 */
interface CircuitBreaker {

    /**
     * Circuit Breaker 내에서 블록을 실행한다.
     *
     * @throws CircuitBreakerOpenException 회로가 OPEN이고 resetTimeout이 경과하지 않은 경우
     */
    suspend fun <T> execute(block: suspend () -> T): T

    /** 현재 Circuit Breaker 상태. */
    fun state(): CircuitBreakerState

    /** Circuit Breaker를 CLOSED 상태로 리셋하고 모든 카운터를 초기화한다. */
    fun reset()

    /** 현재 메트릭의 스냅샷. */
    fun metrics(): CircuitBreakerMetrics
}

/**
 * Circuit Breaker 상태.
 *
 * - [CLOSED]: 정상 운영 중. 실패 횟수가 카운트된다.
 * - [OPEN]: 모든 호출이 즉시 거부된다. resetTimeout 대기 중.
 * - [HALF_OPEN]: 복구를 테스트하기 위한 시험 호출이 허용된다.
 */
enum class CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}

/**
 * 관측성(observability)을 위한 Circuit Breaker 메트릭 스냅샷.
 *
 * @param failureCount 현재 연속 실패 횟수
 * @param successCount 누적 성공 횟수
 * @param state 현재 상태
 * @param lastFailureTime 마지막 실패 시각(epoch ms). 실패가 없으면 null.
 */
data class CircuitBreakerMetrics(
    val failureCount: Int,
    val successCount: Int,
    val state: CircuitBreakerState,
    val lastFailureTime: Long?
)

/**
 * Circuit Breaker가 [CircuitBreakerState.OPEN] 상태일 때 호출이 거부되면 발생하는 예외.
 */
class CircuitBreakerOpenException(
    val breakerName: String = "unknown"
) : RuntimeException("Circuit breaker '$breakerName' is OPEN — call rejected")
