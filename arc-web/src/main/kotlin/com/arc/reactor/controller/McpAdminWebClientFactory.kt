package com.arc.reactor.controller

import io.netty.channel.ChannelOption
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches WebClient instances for MCP admin proxy calls to avoid rebuilding connectors per request.
 */
class McpAdminWebClientFactory(
    private val maxCacheEntries: Int = DEFAULT_CACHE_ENTRIES
) {
    private val clients = ConcurrentHashMap<CacheKey, WebClient>()

    fun getClient(
        baseUrl: String,
        connectTimeoutMs: Int,
        responseTimeoutMs: Long
    ): WebClient {
        val key = CacheKey(baseUrl, connectTimeoutMs, responseTimeoutMs)
        if (clients.size >= maxCacheEntries && !clients.containsKey(key)) {
            evictOne()
        }
        return clients.computeIfAbsent(key) { buildClient(it) }
    }

    internal fun cacheSize(): Int = clients.size

    private fun evictOne() {
        val firstKey = clients.keys.firstOrNull() ?: return
        clients.remove(firstKey)
    }

    private fun buildClient(key: CacheKey): WebClient {
        val httpClient = HttpClient.create(sharedConnectionProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, key.connectTimeoutMs)
            .responseTimeout(Duration.ofMillis(key.responseTimeoutMs))
        return WebClient.builder()
            .baseUrl(key.baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }

    private data class CacheKey(
        val baseUrl: String,
        val connectTimeoutMs: Int,
        val responseTimeoutMs: Long
    )

    companion object {
        private const val DEFAULT_CACHE_ENTRIES = 256
        private val sharedConnectionProvider = ConnectionProvider.builder("mcp-admin-proxy")
            .maxConnections(200)
            .pendingAcquireMaxCount(500)
            .build()
    }
}
