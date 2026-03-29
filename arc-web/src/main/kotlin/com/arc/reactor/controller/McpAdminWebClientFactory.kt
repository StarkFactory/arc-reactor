package com.arc.reactor.controller

import com.github.benmanes.caffeine.cache.Caffeine
import io.netty.channel.ChannelOption
import mu.KotlinLogging
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import org.springframework.beans.factory.DisposableBean
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * MCP admin 프록시 호출용 WebClient 인스턴스 캐시.
 *
 * 요청마다 커넥터를 재생성하지 않도록 baseUrl + timeout 조합을 키로
 * WebClient를 캐싱합니다. Caffeine bounded cache를 사용하여 메모리 누수를 방지합니다.
 */
class McpAdminWebClientFactory(
    private val maxCacheEntries: Int = DEFAULT_CACHE_ENTRIES
) : DisposableBean {
    private val clients = Caffeine.newBuilder()
        .maximumSize(maxCacheEntries.toLong())
        .build<CacheKey, WebClient>()

    fun getClient(
        baseUrl: String,
        connectTimeoutMs: Int,
        responseTimeoutMs: Long
    ): WebClient {
        val key = CacheKey(baseUrl, connectTimeoutMs, responseTimeoutMs)
        return clients.get(key) { buildClient(it) }
    }

    internal fun cacheSize(): Int = clients.estimatedSize().toInt()

    /** 테스트용: Caffeine 비동기 퇴거를 강제 실행한다. */
    internal fun cleanUp() = clients.cleanUp()

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

    override fun destroy() {
        clients.invalidateAll()
        sharedConnectionProvider.dispose()
        logger.info { "McpAdminWebClientFactory: 커넥션 프로바이더 해제 완료" }
    }

    companion object {
        private const val DEFAULT_CACHE_ENTRIES = 256
        private val sharedConnectionProvider = ConnectionProvider.builder("mcp-admin-proxy")
            .maxConnections(200)
            .pendingAcquireMaxCount(500)
            .build()
    }
}
