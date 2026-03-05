package com.arc.reactor.cache

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.cache.impl.RedisSemanticResponseCache
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.ZSetOperations
import java.time.Duration

class RedisSemanticResponseCacheTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @Test
    fun `getSemantic should return exact match before semantic fallback`() = runTest {
        val redis = mockk<StringRedisTemplate>()
        val valueOps = mockk<ValueOperations<String, String>>()
        val zsetOps = mockk<ZSetOperations<String, String>>()
        val embeddingModel = mockk<EmbeddingModel>()
        every { redis.opsForValue() } returns valueOps
        every { redis.opsForZSet() } returns zsetOps
        every { redis.delete(any<String>()) } returns true

        val exactKey = "exact-key"
        every { valueOps.get("arc:test:entry:$exactKey") } returns payload(
            key = exactKey,
            scope = "",
            embedding = emptyList(),
            content = "exact-response"
        )

        val cache = RedisSemanticResponseCache(
            redisTemplate = redis,
            embeddingModel = embeddingModel,
            objectMapper = objectMapper,
            keyPrefix = "arc:test"
        )

        val result = cache.getSemantic(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hello"),
            toolNames = listOf("tool-a"),
            exactKey = exactKey
        )

        assertNotNull(result) { "Exact cache entry should be returned when present" }
        assertEquals("exact-response", result!!.content) { "Exact cache content should match" }
        verify(exactly = 0) { embeddingModel.embed(any<String>()) }
    }

    @Test
    fun `getSemantic should return best semantic candidate within threshold`() = runTest {
        val redis = mockk<StringRedisTemplate>()
        val valueOps = mockk<ValueOperations<String, String>>()
        val zsetOps = mockk<ZSetOperations<String, String>>()
        val embeddingModel = mockk<EmbeddingModel>()
        every { redis.opsForValue() } returns valueOps
        every { redis.opsForZSet() } returns zsetOps
        every { redis.delete(any<String>()) } returns true

        val command = AgentCommand(systemPrompt = "sys", userPrompt = "refund my order")
        val toolNames = listOf("refund_tool")
        val exactKey = "exact-miss"
        val scope = CacheKeyBuilder.buildScopeFingerprint(command, toolNames)
        val indexKey = "arc:test:scope-index:$scope"
        val badKey = "candidate-bad"
        val goodKey = "candidate-good"

        every { valueOps.get("arc:test:entry:$exactKey") } returns null
        every { zsetOps.reverseRange(indexKey, 0, 9) } returns linkedSetOf(badKey, goodKey)
        every { valueOps.get("arc:test:entry:$badKey") } returns payload(
            key = badKey,
            scope = scope,
            embedding = listOf(0f, 1f),
            content = "bad"
        )
        every { valueOps.get("arc:test:entry:$goodKey") } returns payload(
            key = goodKey,
            scope = scope,
            embedding = listOf(1f, 0f),
            content = "good"
        )
        every { embeddingModel.embed("refund my order") } returns floatArrayOf(0.98f, 0.02f)

        val cache = RedisSemanticResponseCache(
            redisTemplate = redis,
            embeddingModel = embeddingModel,
            objectMapper = objectMapper,
            similarityThreshold = 0.9,
            maxCandidates = 10,
            keyPrefix = "arc:test"
        )

        val result = cache.getSemantic(command, toolNames, exactKey)

        assertNotNull(result) { "Semantic lookup should find the most similar candidate" }
        assertEquals("good", result!!.content) { "Highest-similarity candidate should be returned" }
    }

    @Test
    fun `putSemantic should write entry and update scope index`() = runTest {
        val redis = mockk<StringRedisTemplate>()
        val valueOps = mockk<ValueOperations<String, String>>()
        val zsetOps = mockk<ZSetOperations<String, String>>()
        val embeddingModel = mockk<EmbeddingModel>()
        every { redis.opsForValue() } returns valueOps
        every { redis.opsForZSet() } returns zsetOps
        every { embeddingModel.embed(any<String>()) } returns floatArrayOf(1f, 0f)
        every { valueOps.set(any(), any(), any<Duration>()) } returns Unit
        every { zsetOps.add(any(), any(), any()) } returns true
        every { redis.expire(any(), any<Duration>()) } returns true
        every { zsetOps.size(any()) } returns 1L

        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")
        val toolNames = listOf("tool-a")
        val exactKey = "key-1"
        val response = CachedResponse(content = "cached", toolsUsed = listOf("tool-a"), cachedAt = 1234L)
        val scope = CacheKeyBuilder.buildScopeFingerprint(command, toolNames)

        val cache = RedisSemanticResponseCache(
            redisTemplate = redis,
            embeddingModel = embeddingModel,
            objectMapper = objectMapper,
            ttlMinutes = 5,
            keyPrefix = "arc:test"
        )

        cache.putSemantic(command, toolNames, exactKey, response)

        verify(exactly = 1) { valueOps.set("arc:test:entry:$exactKey", any(), Duration.ofMinutes(5)) }
        verify(exactly = 1) { zsetOps.add("arc:test:scope-index:$scope", exactKey, 1234.0) }
        verify(exactly = 1) { redis.expire("arc:test:scope-index:$scope", Duration.ofMinutes(5)) }
    }

    private fun payload(
        key: String,
        scope: String,
        embedding: List<Float>,
        content: String
    ): String {
        return objectMapper.writeValueAsString(
            mapOf(
                "key" to key,
                "scopeFingerprint" to scope,
                "embedding" to embedding,
                "content" to content,
                "toolsUsed" to listOf("tool-a"),
                "cachedAt" to 111L
            )
        )
    }
}
