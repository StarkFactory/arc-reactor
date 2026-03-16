package com.arc.reactor.cache

import com.arc.reactor.cache.impl.NoOpResponseCache
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * NoOpResponseCache에 대한 테스트.
 */
class NoOpResponseCacheTest {

    @Test
    fun `always returns null를 가져온다`() = runTest {
        val cache = NoOpResponseCache()

        cache.put("key1", CachedResponse(content = "stored"))
        cache.invalidateAll()
        val result = cache.get("key1")

        assertNull(result) { "NoOp cache should always return null" }
    }
}
