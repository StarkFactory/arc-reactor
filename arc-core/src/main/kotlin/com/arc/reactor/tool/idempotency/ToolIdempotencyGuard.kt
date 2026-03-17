package com.arc.reactor.tool.idempotency

import java.time.Instant

/**
 * 도구 실행 멱등성 보호 인터페이스.
 *
 * 동일한 도구 호출(도구명 + 인수 해시)이 중복 실행되는 것을 방지한다.
 * TTL 기반 캐시를 사용하여 중복 호출을 감지하고, 이전 결과를 반환한다.
 *
 * @see InMemoryToolIdempotencyGuard 기본 인메모리 구현
 */
interface ToolIdempotencyGuard {

    /**
     * 주어진 도구 호출에 대해 캐시된 결과가 있는지 확인한다.
     *
     * @param toolName 도구 이름
     * @param arguments 도구 호출 인수
     * @return 캐시된 결과가 있으면 [CachedResult], 없으면 null
     */
    fun checkAndGet(toolName: String, arguments: Map<String, Any?>): CachedResult?

    /**
     * 도구 실행 결과를 캐시에 저장한다.
     *
     * @param toolName 도구 이름
     * @param arguments 도구 호출 인수
     * @param result 도구 실행 결과 문자열
     */
    fun store(toolName: String, arguments: Map<String, Any?>, result: String)
}

/**
 * 캐시된 도구 실행 결과.
 *
 * @property result 도구 실행 결과 문자열
 * @property cachedAt 캐시된 시각
 */
data class CachedResult(
    val result: String,
    val cachedAt: Instant
)
