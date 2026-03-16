package com.arc.reactor.cache

/**
 * 에이전트 응답 캐시.
 *
 * 콘텐츠 해시 기반으로 응답을 캐시하여 동일한 요청에 대한
 * 중복 LLM 호출을 방지한다. 결정적 응답(낮은 temperature)만 캐시해야 한다.
 *
 * ## 커스텀 캐시 구현 예시
 * ```kotlin
 * @Bean
 * fun responseCache(): ResponseCache = MyRedisResponseCache(redisTemplate)
 * ```
 *
 * @see CaffeineResponseCache 기본 구현체
 * @see NoOpResponseCache 비활성화(no-op) 구현체
 */
interface ResponseCache {

    /**
     * 키로 캐시된 응답을 조회한다.
     *
     * @param key 캐시 키 (보통 요청 파라미터의 SHA-256 해시)
     * @return 캐시된 응답, 없으면 null
     */
    suspend fun get(key: String): CachedResponse?

    /**
     * 응답을 캐시에 저장한다.
     *
     * @param key 캐시 키
     * @param response 캐시할 응답
     */
    suspend fun put(key: String, response: CachedResponse)

    /** 모든 캐시 항목을 무효화한다. */
    fun invalidateAll()
}

/**
 * 캐시된 응답 데이터.
 *
 * @param content 에이전트의 응답 콘텐츠
 * @param toolsUsed 실행 중 사용된 도구 이름 목록
 * @param cachedAt 응답이 캐시된 시각(epoch ms)
 */
data class CachedResponse(
    val content: String,
    val toolsUsed: List<String> = emptyList(),
    val cachedAt: Long = System.currentTimeMillis()
)
