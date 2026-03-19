package com.arc.reactor.agent.config

/**
 * LLM 및 MCP 호출을 위한 서킷 브레이커 설정.
 *
 * @see com.arc.reactor.resilience.CircuitBreaker 서킷 브레이커 구현
 */
data class CircuitBreakerProperties(
    /** 서킷 브레이커 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 회로 개방 전 연속 실패 횟수. */
    val failureThreshold: Int = 5,

    /** OPEN에서 HALF_OPEN으로 전환하기 전 대기 시간 (밀리초). */
    val resetTimeoutMs: Long = 30_000,

    /** HALF_OPEN 상태에서 허용되는 시험 호출 수. */
    val halfOpenMaxCalls: Int = 1
)

/**
 * 응답 캐시 설정.
 *
 * @see com.arc.reactor.cache.ResponseCache 응답 캐시 인터페이스
 */
data class CacheProperties(
    /** 응답 캐시 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 최대 캐시 항목 수. */
    val maxSize: Long = 1000,

    /** 캐시 항목 유효 시간 (분). */
    val ttlMinutes: Long = 60,

    /** 이 값 이하의 temperature인 응답만 캐시한다. */
    val cacheableTemperature: Double = 0.0,

    /** 선택적 시맨틱 캐시 설정 (Redis 기반). */
    val semantic: SemanticCacheProperties = SemanticCacheProperties()
)

/**
 * 시맨틱 캐시 설정.
 */
data class SemanticCacheProperties(
    /** 시맨틱 응답 캐시 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 시맨틱 캐시 히트에 필요한 최소 코사인 유사도. */
    val similarityThreshold: Double = 0.92,

    /** 조회당 평가할 최대 시맨틱 후보 수. */
    val maxCandidates: Int = 50,

    /** 스코프 핑거프린트당 최대 시맨틱 캐시 항목 수. */
    val maxEntriesPerScope: Long = 1000,

    /** 시맨틱 캐시 레코드 및 인덱스의 Redis 키 접두사. */
    val keyPrefix: String = "arc:cache"
)

/**
 * 장애 완화 / 폴백 설정.
 *
 * @see com.arc.reactor.resilience.FallbackStrategy 폴백 전략 인터페이스
 */
data class FallbackProperties(
    /** 장애 완화 활성화. 기본 비활성 (opt-in). */
    val enabled: Boolean = false,

    /** 우선순위순 폴백 모델 이름 목록. */
    val models: List<String> = emptyList()
)
