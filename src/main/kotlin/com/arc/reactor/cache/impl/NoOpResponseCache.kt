package com.arc.reactor.cache.impl

import com.arc.reactor.cache.CachedResponse
import com.arc.reactor.cache.ResponseCache

/**
 * No-op response cache implementation.
 *
 * Used when caching is disabled. All operations are no-ops.
 */
class NoOpResponseCache : ResponseCache {

    override suspend fun get(key: String): CachedResponse? = null

    override suspend fun put(key: String, response: CachedResponse) {
        // No-op
    }

    override fun invalidateAll() {
        // No-op
    }
}
