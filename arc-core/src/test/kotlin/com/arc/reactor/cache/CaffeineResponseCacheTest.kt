package com.arc.reactor.cache

import com.arc.reactor.cache.impl.CaffeineResponseCache
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * CaffeineResponseCache에 대한 테스트.
 */
class CaffeineResponseCacheTest {

    @Nested
    inner class BasicOperations {

        @Test
        fun `returns null for missing key를 가져온다`() = runTest {
            val cache = CaffeineResponseCache()

            val result = cache.get("nonexistent")

            assertNull(result) { "Cache should return null for missing key" }
        }

        @Test
        fun `put은(는) and get returns cached response`() = runTest {
            val cache = CaffeineResponseCache()
            val response = CachedResponse(content = "Hello, world!", toolsUsed = listOf("tool1"))

            cache.put("key1", response)
            val result = cache.get("key1")

            assertNotNull(result) { "Cache should return stored response" }
            assertEquals("Hello, world!", result!!.content) { "Content should match" }
            assertEquals(listOf("tool1"), result.toolsUsed) { "Tools used should match" }
        }

        @Test
        fun `put은(는) overwrites existing entry`() = runTest {
            val cache = CaffeineResponseCache()

            cache.put("key1", CachedResponse(content = "first"))
            cache.put("key1", CachedResponse(content = "second"))
            val result = cache.get("key1")

            assertNotNull(result) { "Cache should return updated response" }
            assertEquals("second", result!!.content) { "Content should be the latest value" }
        }

        @Test
        fun `invalidateAll은(는) clears all entries`() = runTest {
            val cache = CaffeineResponseCache()

            cache.put("key1", CachedResponse(content = "a"))
            cache.put("key2", CachedResponse(content = "b"))
            cache.put("key3", CachedResponse(content = "c"))

            cache.invalidateAll()

            assertNull(cache.get("key1")) { "key1 should be evicted" }
            assertNull(cache.get("key2")) { "key2 should be evicted" }
            assertNull(cache.get("key3")) { "key3 should be evicted" }
        }
    }

    @Nested
    inner class SizeLimits {

        @Test
        fun `cache with maxSize은(는) not grow unbounded해야 한다`() = runTest {
            val cache = CaffeineResponseCache(maxSize = 3, ttlMinutes = 60)

            for (i in 1..10) {
                cache.put("key-$i", CachedResponse(content = "response-$i"))
            }

            // Caffeine eviction is async — force cleanup before asserting
            cache.cleanUp()

            var hitCount = 0
            for (i in 1..10) {
                if (cache.get("key-$i") != null) hitCount++
            }

            assertTrue(hitCount <= 3) {
                "Cache should have evicted to maxSize=3, but found $hitCount/10"
            }
        }
    }

    @Nested
    inner class CachedResponseData {

        @Test
        fun `CachedResponse 기본값`() {
            val response = CachedResponse(content = "test")

            assertEquals("test", response.content) { "Content should match" }
            assertTrue(response.toolsUsed.isEmpty()) { "Default toolsUsed should be empty" }
            assertTrue(response.cachedAt > 0) { "cachedAt should be a positive timestamp" }
        }

        @Test
        fun `모든 필드가 있는 CachedResponse`() {
            val response = CachedResponse(
                content = "result",
                toolsUsed = listOf("calc", "search"),
                cachedAt = 12345L
            )

            assertEquals("result", response.content) { "Content should match" }
            assertEquals(listOf("calc", "search"), response.toolsUsed) { "Tools should match" }
            assertEquals(12345L, response.cachedAt) { "Timestamp should match" }
        }
    }
}
