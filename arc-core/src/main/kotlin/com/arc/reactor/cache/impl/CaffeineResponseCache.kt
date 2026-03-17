package com.arc.reactor.cache.impl

import com.arc.reactor.cache.CachedResponse
import com.arc.reactor.cache.ResponseCache
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Caffeine 기반 응답 캐시 구현체.
 *
 * 설정 가능한 TTL과 최대 크기를 가진 Caffeine 고성능 캐시를 사용한다.
 *
 * @param maxSize 캐시의 최대 항목 수
 * @param ttlMinutes 캐시 항목의 TTL(분)
 */
class CaffeineResponseCache(
    maxSize: Long = 1000,
    ttlMinutes: Long = 60
) : ResponseCache {

    /** Caffeine 캐시 인스턴스. 통계 기록 활성화. */
    private val cache: Cache<String, CachedResponse> = Caffeine.newBuilder()
        .maximumSize(maxSize)
        .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
        .recordStats()
        .build()

    override suspend fun get(key: String): CachedResponse? {
        val cached = cache.getIfPresent(key)
        if (cached != null) {
            logger.debug { "Cache hit for key: ${key.take(16)}..." }
        }
        return cached
    }

    override suspend fun put(key: String, response: CachedResponse) {
        if (response.content.isBlank()) {
            logger.debug { "Skipping cache for blank response: ${key.take(16)}..." }
            return
        }
        cache.put(key, response)
        logger.debug { "Cached response for key: ${key.take(16)}..." }
    }

    override fun invalidateAll() {
        val size = cache.estimatedSize()
        cache.invalidateAll()
        logger.info { "Invalidated all $size cached responses" }
    }

    /**
     * 대기 중인 퇴거를 즉시 실행한다.
     * Caffeine의 퇴거가 비동기이므로 테스트에서 유용하다.
     */
    fun cleanUp() {
        cache.cleanUp()
    }
}
