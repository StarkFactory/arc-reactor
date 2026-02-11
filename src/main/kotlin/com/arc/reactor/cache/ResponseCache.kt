package com.arc.reactor.cache

/**
 * Cache for agent responses.
 *
 * Caches responses by content hash to avoid redundant LLM calls
 * for identical requests. Only deterministic responses (low temperature)
 * should be cached.
 *
 * ## Example: Custom Cache Implementation
 * ```kotlin
 * @Bean
 * fun responseCache(): ResponseCache = MyRedisResponseCache(redisTemplate)
 * ```
 *
 * @see CaffeineResponseCache for the default implementation
 * @see NoOpResponseCache for the disabled (no-op) implementation
 */
interface ResponseCache {

    /**
     * Retrieve a cached response by key.
     *
     * @param key Cache key (typically SHA-256 hash of request parameters)
     * @return Cached response, or null if not found
     */
    suspend fun get(key: String): CachedResponse?

    /**
     * Store a response in the cache.
     *
     * @param key Cache key
     * @param response Response to cache
     */
    suspend fun put(key: String, response: CachedResponse)

    /** Invalidate all cached entries. */
    fun invalidateAll()
}

/**
 * Cached response data.
 *
 * @param content The agent's response content
 * @param toolsUsed List of tool names used during execution
 * @param cachedAt Timestamp when the response was cached
 */
data class CachedResponse(
    val content: String,
    val toolsUsed: List<String> = emptyList(),
    val cachedAt: Long = System.currentTimeMillis()
)
