package com.arc.reactor.cache.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.cache.CacheKeyBuilder
import com.arc.reactor.cache.CachedResponse
import com.arc.reactor.cache.SemanticResponseCache
import com.arc.reactor.tool.SemanticToolSelector
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.CancellationException
import java.time.Duration

private val logger = KotlinLogging.logger {}

/** invalidateAll() 청크 삭제 크기. 메모리 수집량을 제한하여 GC 압력을 줄인다. */
private const val INVALIDATE_CHUNK_SIZE = 500

/**
 * Redis 기반 시맨틱 응답 캐시.
 *
 * 다음 두 단계의 캐시 조회를 수행한다:
 * 1) 정확 키 조회 (해시 캐시와 동일한 동작)
 * 2) 요청 스코프 핑거프린트 내에서의 시맨틱 폴백 조회
 *
 * 스코프 핑거프린팅으로 cross-tenant/cross-user/cross-session 캐시 누출을 방지한다.
 */
class RedisSemanticResponseCache(
    private val redisTemplate: StringRedisTemplate,
    private val embeddingModel: EmbeddingModel,
    private val objectMapper: ObjectMapper,
    private val ttlMinutes: Long = 60,
    private val similarityThreshold: Double = 0.92,
    private val maxCandidates: Int = 50,
    private val maxEntriesPerScope: Long = 1000,
    private val keyPrefix: String = "arc:cache"
) : SemanticResponseCache {

    init {
        require(ttlMinutes > 0) { "ttlMinutes는 0보다 커야 한다" }
        require(similarityThreshold in 0.0..1.0) { "similarityThreshold는 0.0~1.0 범위여야 한다" }
        require(maxCandidates > 0) { "maxCandidates는 0보다 커야 한다" }
        require(maxEntriesPerScope > 0) { "maxEntriesPerScope는 0보다 커야 한다" }
        require(keyPrefix.isNotBlank()) { "keyPrefix는 비어있으면 안 된다" }
    }

    override suspend fun get(key: String): CachedResponse? = withContext(Dispatchers.IO) {
        readEntry(key)?.toCachedResponse()
    }

    /**
     * 정확 키 기반 캐시 저장.
     *
     * scopeFingerprint와 embedding이 비어있으므로 getSemantic()의
     * 유사도 검색에서는 발견되지 않고, 정확 키 매칭에서만 히트한다.
     * 이는 의도된 동작이다: put()은 단순 캐시, putSemantic()은 의미 캐시.
     */
    override suspend fun put(key: String, response: CachedResponse) = withContext(Dispatchers.IO) {
        if (response.content.isBlank()) {
            logger.debug { "빈 응답 캐시 저장 생략: key=${key.take(16)}..." }
            return@withContext
        }
        val record = StoredEntry(
            key = key,
            scopeFingerprint = "",
            embedding = emptyList(),
            content = response.content,
            toolsUsed = response.toolsUsed,
            metadata = response.metadata,
            cachedAt = response.cachedAt
        )
        writeEntry(key, record)
    }

    override suspend fun getSemantic(
        command: AgentCommand,
        toolNames: List<String>,
        exactKey: String
    ): CachedResponse? {
        get(exactKey)?.let { return it }
        val scope = CacheKeyBuilder.buildScopeFingerprint(command, toolNames)
        // 블로킹 EmbeddingModel.embed()를 IO 디스패처에서 실행하여 코루틴 스레드 고갈 방지
        val promptEmbedding = withContext(Dispatchers.IO) { safeEmbed(command.userPrompt) } ?: return null
        return withContext(Dispatchers.IO) {
            val indexKey = scopeIndexKey(scope)
            val candidates = redisTemplate.opsForZSet()
                .reverseRange(indexKey, 0, (maxCandidates - 1).toLong())
                ?: emptySet()
            var best: Pair<StoredEntry, Double>? = null
            for (candidateKey in candidates) {
                if (candidateKey == exactKey) continue
                val entry = readEntry(candidateKey)
                if (entry == null) {
                    redisTemplate.opsForZSet().remove(indexKey, candidateKey)
                    continue
                }
                if (entry.scopeFingerprint != scope || entry.embedding.isEmpty()) {
                    continue
                }
                val candidateEmbedding = entry.embedding.toFloatArray()
                if (candidateEmbedding.size != promptEmbedding.size) continue
                val similarity = SemanticToolSelector.cosineSimilarity(
                    promptEmbedding, candidateEmbedding
                )
                if (similarity < similarityThreshold) continue
                if (best == null || similarity > best.second) {
                    best = entry to similarity
                }
            }

            val bestEntry = best?.first ?: return@withContext null
            logger.debug {
                "시맨틱 캐시 히트: scope=${scope.take(12)} key=${bestEntry.key.take(12)} " +
                    "threshold=$similarityThreshold"
            }
            bestEntry.toCachedResponse()
        }
    }

    override suspend fun putSemantic(
        command: AgentCommand,
        toolNames: List<String>,
        exactKey: String,
        response: CachedResponse
    ) {
        if (response.content.isBlank()) {
            logger.debug { "빈 응답 시맨틱 캐시 저장 생략: key=${exactKey.take(16)}..." }
            return
        }
        val scope = CacheKeyBuilder.buildScopeFingerprint(command, toolNames)
        // 블로킹 EmbeddingModel.embed()를 IO 디스패처에서 실행
        val promptEmbedding = withContext(Dispatchers.IO) { safeEmbed(command.userPrompt) }
        if (promptEmbedding == null) {
            put(exactKey, response)
            return
        }

        withContext(Dispatchers.IO) {
            val record = StoredEntry(
                key = exactKey,
                scopeFingerprint = scope,
                embedding = promptEmbedding.toList(),
                content = response.content,
                toolsUsed = response.toolsUsed,
                metadata = response.metadata,
                cachedAt = response.cachedAt
            )
            writeEntry(exactKey, record)

            val indexKey = scopeIndexKey(scope)
            val zsetOps = redisTemplate.opsForZSet()
            zsetOps.add(indexKey, exactKey, response.cachedAt.toDouble())
            redisTemplate.expire(indexKey, ttl())
            trimIndex(indexKey, zsetOps)
        }
    }

    /**
     * 모든 캐시 엔트리를 삭제한다.
     *
     * 청크 방식으로 삭제하여 대량 키 수집 시 OOM을 방지한다.
     * 기존: 전체 키를 Set에 수집 후 한 번에 삭제 → 수만 키 시 GC 압력 + OOM 위험
     * 변경: SCAN 커서에서 CHUNK_SIZE(500)씩 모아서 즉시 삭제
     */
    override fun invalidateAll() {
        try {
            val pattern = "$keyPrefix:*"
            val scanOptions = ScanOptions.scanOptions().match(pattern).count(100).build()
            var totalDeleted = 0L
            val chunk = mutableSetOf<String>()

            redisTemplate.scan(scanOptions).use { cursor ->
                for (key in cursor) {
                    chunk.add(key)
                    if (chunk.size >= INVALIDATE_CHUNK_SIZE) {
                        totalDeleted += redisTemplate.delete(chunk)
                        chunk.clear()
                    }
                }
            }
            if (chunk.isNotEmpty()) {
                totalDeleted += redisTemplate.delete(chunk)
            }
            logger.info { "Redis 시맨틱 캐시 무효화 완료: ${totalDeleted}건 삭제 (prefix=$keyPrefix)" }
        } catch (e: Exception) {
            logger.warn(e) { "Redis 시맨틱 캐시 무효화 실패: prefix=$keyPrefix" }
        }
    }

    private fun safeEmbed(text: String): FloatArray? {
        return try {
            embeddingModel.embed(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "시맨틱 캐시 임베딩 실패, 정확 매칭 캐시로 폴백" }
            null
        }
    }

    private fun writeEntry(key: String, entry: StoredEntry) {
        try {
            val payload = objectMapper.writeValueAsString(entry)
            redisTemplate.opsForValue().set(entryKey(key), payload, ttl())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "캐시 엔트리 쓰기 실패: key=${key.take(16)}..." }
        }
    }

    private fun readEntry(key: String): StoredEntry? {
        val payload = redisTemplate.opsForValue().get(entryKey(key)) ?: return null
        return try {
            objectMapper.readValue(payload, StoredEntry::class.java)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "손상된 캐시 페이로드 삭제: key=${key.take(16)}..." }
            redisTemplate.delete(entryKey(key))
            null
        }
    }

    private fun trimIndex(
        indexKey: String,
        zsetOps: org.springframework.data.redis.core.ZSetOperations<String, String>
    ) {
        val size = zsetOps.size(indexKey) ?: return
        val overflow = size - maxEntriesPerScope
        if (overflow <= 0) return
        val staleKeys = zsetOps.range(indexKey, 0, overflow - 1) ?: emptySet()
        if (staleKeys.isEmpty()) return
        zsetOps.remove(indexKey, *staleKeys.toTypedArray())
        // 일괄 삭제로 Redis 라운드트립 최소화
        val entryKeys = staleKeys.map { entryKey(it) }
        redisTemplate.delete(entryKeys)
    }

    private fun ttl(): Duration = Duration.ofMinutes(ttlMinutes)

    private fun entryKey(key: String): String = "$keyPrefix:entry:$key"

    private fun scopeIndexKey(scope: String): String = "$keyPrefix:scope-index:$scope"

    private data class StoredEntry(
        val key: String,
        val scopeFingerprint: String,
        val embedding: List<Float>,
        val content: String,
        val toolsUsed: List<String>,
        val metadata: Map<String, Any> = emptyMap(),
        val cachedAt: Long
    ) {
        fun toCachedResponse(): CachedResponse {
            return CachedResponse(
                content = content,
                toolsUsed = toolsUsed,
                metadata = metadata,
                cachedAt = cachedAt
            )
        }
    }
}
