package com.arc.reactor.resilience

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.resilience.impl.DefaultCircuitBreaker
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine

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
    private val agentMetrics: AgentMetrics = NoOpAgentMetrics(),
    maxBreakers: Long = DEFAULT_MAX_BREAKERS
) {

    /**
     * 이름 → CircuitBreaker 인스턴스 맵 (Caffeine bounded cache).
     *
     * R313 fix: ConcurrentHashMap → Caffeine. 기존 구현은 MCP 서버가 동적으로 추가/삭제될 때
     * breaker 이름 공간이 무제한 성장 가능했다. 이제 [maxBreakers] 상한(기본 1000)을 넘으면
     * W-TinyLFU 정책으로 evict — 가장 오래/자주 안 쓰인 breaker부터 제거.
     */
    private val breakers: Cache<String, CircuitBreaker> = Caffeine.newBuilder()
        .maximumSize(maxBreakers)
        .build()

    /**
     * 주어진 이름의 Circuit Breaker를 가져오거나 새로 생성한다.
     * Caffeine의 get(key, mappingFunction)은 atomic get-or-create이며 non-null 보장.
     */
    fun get(name: String): CircuitBreaker = breakers.get(name) {
        DefaultCircuitBreaker(
            failureThreshold = failureThreshold,
            resetTimeoutMs = resetTimeoutMs,
            halfOpenMaxCalls = halfOpenMaxCalls,
            name = name,
            agentMetrics = agentMetrics
        )
    }

    /** 이미 존재하는 Breaker만 반환한다. 없으면 null. */
    fun getIfExists(name: String): CircuitBreaker? = breakers.getIfPresent(name)

    /** 등록된 모든 Breaker 이름. */
    fun names(): Set<String> = breakers.asMap().keys.toSet()

    /** 모든 Breaker를 CLOSED 상태로 리셋한다. */
    fun resetAll() {
        for ((_, breaker) in breakers.asMap()) {
            breaker.reset()
        }
    }

    /** 테스트 전용: Caffeine 지연 maintenance를 강제 실행한다. */
    internal fun forceCleanUp() {
        breakers.cleanUp()
    }

    companion object {
        /** 기본 breaker 레지스트리 상한. 초과 시 W-TinyLFU 정책으로 evict. */
        const val DEFAULT_MAX_BREAKERS: Long = 1_000L
    }
}
