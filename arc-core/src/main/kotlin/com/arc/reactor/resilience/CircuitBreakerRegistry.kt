package com.arc.reactor.resilience

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.resilience.impl.DefaultCircuitBreaker
import java.util.concurrent.ConcurrentHashMap

/**
 * 이름별 [CircuitBreaker] 인스턴스를 관리하는 레지스트리.
 *
 * 각 이름은 독립된 Circuit Breaker를 갖는다 (예: "llm", "mcp:server-name").
 * Breaker는 첫 접근 시 지연(lazy) 생성된다.
 *
 * ## 왜 이름별 인스턴스인가?
 * LLM 호출과 MCP 서버 호출은 장애 패턴이 다르다.
 * 각각 독립된 Circuit Breaker로 관리하면 한쪽 장애가 다른 쪽에 영향을 주지 않는다.
 *
 * @param failureThreshold 새 Breaker의 기본 실패 임계값
 * @param resetTimeoutMs 새 Breaker의 기본 리셋 타임아웃
 * @param halfOpenMaxCalls 새 Breaker의 기본 HALF_OPEN 시험 호출 수
 * @param agentMetrics Circuit Breaker 상태 전이를 기록하는 메트릭 레코더
 *
 * @see CircuitBreaker Circuit Breaker 인터페이스
 * @see com.arc.reactor.resilience.impl.DefaultCircuitBreaker 기본 구현체
 */
class CircuitBreakerRegistry(
    private val failureThreshold: Int = 5,
    private val resetTimeoutMs: Long = 30_000,
    private val halfOpenMaxCalls: Int = 1,
    private val agentMetrics: AgentMetrics = NoOpAgentMetrics()
) {

    /** 이름 → CircuitBreaker 인스턴스 맵. ConcurrentHashMap으로 스레드 안전. */
    private val breakers = ConcurrentHashMap<String, CircuitBreaker>()

    /**
     * 주어진 이름의 Circuit Breaker를 가져오거나 새로 생성한다.
     * computeIfAbsent로 같은 이름에 대해 하나의 인스턴스만 생성됨을 보장한다.
     */
    fun get(name: String): CircuitBreaker = breakers.computeIfAbsent(name) {
        DefaultCircuitBreaker(
            failureThreshold = failureThreshold,
            resetTimeoutMs = resetTimeoutMs,
            halfOpenMaxCalls = halfOpenMaxCalls,
            name = name,
            agentMetrics = agentMetrics
        )
    }

    /** 이미 존재하는 Breaker만 반환한다. 없으면 null. */
    fun getIfExists(name: String): CircuitBreaker? = breakers[name]

    /** 등록된 모든 Breaker 이름. */
    fun names(): Set<String> = breakers.keys.toSet()

    /** 모든 Breaker를 CLOSED 상태로 리셋한다. */
    fun resetAll() {
        for ((_, breaker) in breakers) {
            breaker.reset()
        }
    }
}
