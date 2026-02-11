package com.arc.reactor.cache.impl

import com.arc.reactor.cache.CachedResponse
import com.arc.reactor.cache.ResponseCache
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Caffeine-based response cache implementation.
 *
 * Uses Caffeine's high-performance cache with configurable TTL and max size.
 *
 * @param maxSize Maximum number of entries in the cache
 * @param ttlMinutes Time-to-live for cache entries in minutes
 */
class CaffeineResponseCache(
    maxSize: Long = 1000,
    ttlMinutes: Long = 60
) : ResponseCache {

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
        cache.put(key, response)
        logger.debug { "Cached response for key: ${key.take(16)}..." }
    }

    override fun invalidateAll() {
        val size = cache.estimatedSize()
        cache.invalidateAll()
        logger.info { "Invalidated all $size cached responses" }
    }
}
