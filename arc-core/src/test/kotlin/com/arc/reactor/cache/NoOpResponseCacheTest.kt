package com.arc.reactor.cache

import com.arc.reactor.cache.impl.NoOpResponseCache
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for NoOpResponseCache.
 */
class NoOpResponseCacheTest {

    @Test
    fun `get always returns null`() = runTest {
        val cache = NoOpResponseCache()

        cache.put("key1", CachedResponse(content = "stored"))
        val result = cache.get("key1")

        assertNull(result) { "NoOp cache should always return null" }
    }

    @Test
    fun `invalidateAll does not throw`() {
        val cache = NoOpResponseCache()

        // Should not throw
        cache.invalidateAll()
    }
}
