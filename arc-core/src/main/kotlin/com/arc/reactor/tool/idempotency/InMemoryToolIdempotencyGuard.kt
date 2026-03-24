package com.arc.reactor.tool.idempotency

import com.arc.reactor.util.HashUtils
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * [ToolIdempotencyGuard]의 인메모리 구현.
 *
 * Caffeine 캐시를 사용하여 도구명 + 인수 해시 기반의 멱등성 키를 관리한다.
 * TTL이 만료되면 자동으로 항목이 제거된다.
 *
 * @param ttlSeconds 캐시 항목 유효 시간(초). 기본 60초.
 * @param maxSize 최대 캐시 항목 수. 기본 1000.
 */
class InMemoryToolIdempotencyGuard(
    ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    maxSize: Long = DEFAULT_MAX_SIZE
) : ToolIdempotencyGuard {

    private val cache: Cache<String, CachedResult> = Caffeine.newBuilder()
        .maximumSize(maxSize)
        .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
        .build()

    override fun checkAndGet(toolName: String, arguments: Map<String, Any?>): CachedResult? {
        val key = buildIdempotencyKey(toolName, arguments)
        val cached = cache.getIfPresent(key)
        if (cached != null) {
            logger.debug { "멱등성 캐시 히트: tool=$toolName, key=$key" }
        }
        return cached
    }

    override fun store(toolName: String, arguments: Map<String, Any?>, result: String) {
        val key = buildIdempotencyKey(toolName, arguments)
        cache.put(key, CachedResult(result = result, cachedAt = Instant.now()))
        logger.debug { "멱등성 캐시 저장: tool=$toolName, key=$key" }
    }

    companion object {
        private const val DEFAULT_TTL_SECONDS = 60L
        private const val DEFAULT_MAX_SIZE = 1000L

        /**
         * 도구명 + 인수로부터 멱등성 키를 생성한다.
         *
         * 인수를 정렬된 키 순서로 직렬화한 뒤 SHA-256 해시를 생성하여
         * 일관된 키를 보장한다.
         */
        internal fun buildIdempotencyKey(toolName: String, arguments: Map<String, Any?>): String {
            val sortedArgs = arguments.entries
                .sortedBy { it.key }
                .joinToString("\u0000") { "${it.key}\u0001${it.value}" }
            return HashUtils.sha256Hex("$toolName:$sortedArgs")
        }
    }
}
