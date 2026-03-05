package com.arc.reactor.cache.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.cache.CacheKeyBuilder
import com.arc.reactor.cache.CachedResponse
import com.arc.reactor.cache.SemanticResponseCache
import com.arc.reactor.tool.SemanticToolSelector
import com.fasterxml.jackson.databind.ObjectMapper
import mu.KotlinLogging
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.concurrent.CancellationException
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Redis-backed semantic response cache.
 *
 * This cache performs:
 * 1) Exact-key lookup (same behavior as hash cache)
 * 2) Semantic fallback lookup within a strict request scope fingerprint
 *
 * Scope fingerprinting prevents cross-tenant/cross-user/cross-session cache leakage.
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
        require(ttlMinutes > 0) { "ttlMinutes must be > 0" }
        require(similarityThreshold in 0.0..1.0) { "similarityThreshold must be between 0.0 and 1.0" }
        require(maxCandidates > 0) { "maxCandidates must be > 0" }
        require(maxEntriesPerScope > 0) { "maxEntriesPerScope must be > 0" }
        require(keyPrefix.isNotBlank()) { "keyPrefix must not be blank" }
    }

    override suspend fun get(key: String): CachedResponse? {
        return readEntry(key)?.toCachedResponse()
    }

    override suspend fun put(key: String, response: CachedResponse) {
        val record = StoredEntry(
            key = key,
            scopeFingerprint = "",
            embedding = emptyList(),
            content = response.content,
            toolsUsed = response.toolsUsed,
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
        val promptEmbedding = safeEmbed(command.userPrompt) ?: return null
        val indexKey = scopeIndexKey(scope)
        val candidates = redisTemplate.opsForZSet().reverseRange(indexKey, 0, (maxCandidates - 1).toLong())
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
            val similarity = SemanticToolSelector.cosineSimilarity(promptEmbedding, candidateEmbedding)
            if (similarity < similarityThreshold) continue
            if (best == null || similarity > best.second) {
                best = entry to similarity
            }
        }

        val bestEntry = best?.first ?: return null
        logger.debug {
            "Semantic cache hit scope=${scope.take(12)} key=${bestEntry.key.take(12)} " +
                "threshold=$similarityThreshold"
        }
        return bestEntry.toCachedResponse()
    }

    override suspend fun putSemantic(
        command: AgentCommand,
        toolNames: List<String>,
        exactKey: String,
        response: CachedResponse
    ) {
        val scope = CacheKeyBuilder.buildScopeFingerprint(command, toolNames)
        val promptEmbedding = safeEmbed(command.userPrompt)
        if (promptEmbedding == null) {
            put(exactKey, response)
            return
        }

        val record = StoredEntry(
            key = exactKey,
            scopeFingerprint = scope,
            embedding = promptEmbedding.toList(),
            content = response.content,
            toolsUsed = response.toolsUsed,
            cachedAt = response.cachedAt
        )
        writeEntry(exactKey, record)

        val indexKey = scopeIndexKey(scope)
        val zsetOps = redisTemplate.opsForZSet()
        zsetOps.add(indexKey, exactKey, response.cachedAt.toDouble())
        redisTemplate.expire(indexKey, ttl())
        trimIndex(indexKey, zsetOps)
    }

    override fun invalidateAll() {
        try {
            val pattern = "$keyPrefix:*"
            val keys = redisTemplate.keys(pattern)
            val deleted = if (keys.isEmpty()) 0L else redisTemplate.delete(keys)
            logger.info { "Invalidated $deleted Redis semantic cache keys for prefix=$keyPrefix" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to invalidate Redis semantic cache for prefix=$keyPrefix" }
        }
    }

    private fun safeEmbed(text: String): FloatArray? {
        return try {
            embeddingModel.embed(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Semantic cache embedding failed; falling back to exact-cache behavior" }
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
            logger.warn(e) { "Failed to write cache entry key=${key.take(16)}..." }
        }
    }

    private fun readEntry(key: String): StoredEntry? {
        val payload = redisTemplate.opsForValue().get(entryKey(key)) ?: return null
        return try {
            objectMapper.readValue(payload, StoredEntry::class.java)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Corrupted cache payload key=${key.take(16)}... removing entry" }
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
        staleKeys.forEach { redisTemplate.delete(entryKey(it)) }
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
        val cachedAt: Long
    ) {
        fun toCachedResponse(): CachedResponse {
            return CachedResponse(
                content = content,
                toolsUsed = toolsUsed,
                cachedAt = cachedAt
            )
        }
    }
}
