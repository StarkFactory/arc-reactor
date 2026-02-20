package com.arc.reactor.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class McpAdminWebClientFactoryTest {

    @Test
    fun `should reuse client for same baseUrl and timeout settings`() {
        val factory = McpAdminWebClientFactory(maxCacheEntries = 8)

        val client1 = factory.getClient(
            baseUrl = "http://localhost:8080",
            connectTimeoutMs = 2_000,
            responseTimeoutMs = 10_000
        )
        val client2 = factory.getClient(
            baseUrl = "http://localhost:8080",
            connectTimeoutMs = 2_000,
            responseTimeoutMs = 10_000
        )

        assertSame(client1, client2, "Expected cached WebClient instance to be reused")
        assertEquals(1, factory.cacheSize(), "Expected single cache entry for identical settings")
    }

    @Test
    fun `should create distinct clients when timeout settings differ`() {
        val factory = McpAdminWebClientFactory(maxCacheEntries = 8)

        val client1 = factory.getClient(
            baseUrl = "http://localhost:8080",
            connectTimeoutMs = 1_000,
            responseTimeoutMs = 10_000
        )
        val client2 = factory.getClient(
            baseUrl = "http://localhost:8080",
            connectTimeoutMs = 3_000,
            responseTimeoutMs = 10_000
        )

        assertNotSame(client1, client2, "Different timeout settings should produce distinct clients")
        assertEquals(2, factory.cacheSize(), "Expected two cache entries for different timeout settings")
    }

    @Test
    fun `should keep cache bounded by max entries`() {
        val factory = McpAdminWebClientFactory(maxCacheEntries = 2)

        factory.getClient("http://localhost:8080", connectTimeoutMs = 1_000, responseTimeoutMs = 5_000)
        factory.getClient("http://localhost:8081", connectTimeoutMs = 1_000, responseTimeoutMs = 5_000)
        factory.getClient("http://localhost:8082", connectTimeoutMs = 1_000, responseTimeoutMs = 5_000)

        assertEquals(2, factory.cacheSize(), "Cache size should never exceed configured max entries")
    }
}
