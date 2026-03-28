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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.data.redis.core.Cursor
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.ZSetOperations
import java.time.Duration

/**
 * RedisSemanticResponseCache 경계 케이스 및 방어 로직 테스트.
 *
 * 기존 [RedisSemanticResponseCacheTest]가 다루지 않는 다음 영역을 검증한다:
 * - init 블록 파라미터 검증
 * - 빈 응답 캐싱 방지 (content.isBlank)
 * - 임베딩 실패 시 폴백 동작
 * - 스코프 불일치 후보 건너뜀
 * - 빈 임베딩 후보 건너뜀
 * - 유사도 임계값 미달 시 null 반환
 * - trimIndex 초과 항목 삭제
 * - invalidateAll 청크 단위 삭제
 */
class RedisSemanticResponseCacheExtendedTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    // ------------------------------------------------------------------
    // 공통 헬퍼
    // ------------------------------------------------------------------

    /** Redis 목 기본 세팅 반환 */
    private fun buildRedisMocks(): Triple<StringRedisTemplate, ValueOperations<String, String>, ZSetOperations<String, String>> {
        val redis = mockk<StringRedisTemplate>()
        val valueOps = mockk<ValueOperations<String, String>>()
        val zsetOps = mockk<ZSetOperations<String, String>>()
        every { redis.opsForValue() } returns valueOps
        every { redis.opsForZSet() } returns zsetOps
        every { redis.delete(any<String>()) } returns true
        return Triple(redis, valueOps, zsetOps)
    }

    /** StoredEntry JSON 페이로드 생성 */
    private fun payload(
        key: String,
        scope: String,
        embedding: List<Float>,
        content: String,
        toolsUsed: List<String> = listOf("tool-a"),
        cachedAt: Long = 111L
    ): String = objectMapper.writeValueAsString(
        mapOf(
            "key" to key,
            "scopeFingerprint" to scope,
            "embedding" to embedding,
            "content" to content,
            "toolsUsed" to toolsUsed,
            "cachedAt" to cachedAt
        )
    )

    // ------------------------------------------------------------------
    // 1. init 블록 파라미터 검증
    // ------------------------------------------------------------------

    @Nested
    inner class InitValidation {

        private val redis = buildRedisMocks().first
        private val embeddingModel = mockk<EmbeddingModel>()

        @Test
        fun `ttlMinutes가 0이면 IllegalArgumentException이 발생한다`() {
            assertThrows<IllegalArgumentException>("ttlMinutes=0은 허용되지 않아야 한다") {
                RedisSemanticResponseCache(
                    redisTemplate = redis,
                    embeddingModel = embeddingModel,
                    objectMapper = objectMapper,
                    ttlMinutes = 0
                )
            }
        }

        @Test
        fun `similarityThreshold가 범위를 초과하면 IllegalArgumentException이 발생한다`() {
            assertThrows<IllegalArgumentException>("similarityThreshold=1.5는 허용되지 않아야 한다") {
                RedisSemanticResponseCache(
                    redisTemplate = redis,
                    embeddingModel = embeddingModel,
                    objectMapper = objectMapper,
                    similarityThreshold = 1.5
                )
            }
        }

        @Test
        fun `maxCandidates가 0이면 IllegalArgumentException이 발생한다`() {
            assertThrows<IllegalArgumentException>("maxCandidates=0은 허용되지 않아야 한다") {
                RedisSemanticResponseCache(
                    redisTemplate = redis,
                    embeddingModel = embeddingModel,
                    objectMapper = objectMapper,
                    maxCandidates = 0
                )
            }
        }

        @Test
        fun `keyPrefix가 공백이면 IllegalArgumentException이 발생한다`() {
            assertThrows<IllegalArgumentException>("공백 keyPrefix는 허용되지 않아야 한다") {
                RedisSemanticResponseCache(
                    redisTemplate = redis,
                    embeddingModel = embeddingModel,
                    objectMapper = objectMapper,
                    keyPrefix = "   "
                )
            }
        }

        @Test
        fun `maxEntriesPerScope가 0이면 IllegalArgumentException이 발생한다`() {
            assertThrows<IllegalArgumentException>("maxEntriesPerScope=0은 허용되지 않아야 한다") {
                RedisSemanticResponseCache(
                    redisTemplate = redis,
                    embeddingModel = embeddingModel,
                    objectMapper = objectMapper,
                    maxEntriesPerScope = 0
                )
            }
        }

        @Test
        fun `유효한 파라미터로 인스턴스가 생성된다`() {
            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = embeddingModel,
                objectMapper = objectMapper,
                ttlMinutes = 30,
                similarityThreshold = 0.85,
                maxCandidates = 20,
                maxEntriesPerScope = 500,
                keyPrefix = "arc:valid"
            )
            assertNotNull(cache) { "유효한 파라미터로 캐시 인스턴스가 생성되어야 한다" }
        }
    }

    // ------------------------------------------------------------------
    // 2. put — 빈 콘텐츠 저장 방지
    // ------------------------------------------------------------------

    @Nested
    inner class PutBlankContentPrevention {

        @Test
        fun `content가 빈 문자열이면 Redis에 저장하지 않는다`() = runTest {
            val (redis, valueOps, _) = buildRedisMocks()
            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = mockk(),
                objectMapper = objectMapper,
                keyPrefix = "arc:test"
            )

            cache.put("key1", CachedResponse(content = ""))

            verify(exactly = 0) { valueOps.set(any(), any(), any<Duration>()) }
        }

        @Test
        fun `content가 공백만 있으면 Redis에 저장하지 않는다`() = runTest {
            val (redis, valueOps, _) = buildRedisMocks()
            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = mockk(),
                objectMapper = objectMapper,
                keyPrefix = "arc:test"
            )

            cache.put("key1", CachedResponse(content = "   "))

            verify(exactly = 0) { valueOps.set(any(), any(), any<Duration>()) }
        }

        @Test
        fun `유효한 content는 Redis에 저장된다`() = runTest {
            val (redis, valueOps, _) = buildRedisMocks()
            every { valueOps.set(any(), any(), any<Duration>()) } returns Unit

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = mockk(),
                objectMapper = objectMapper,
                ttlMinutes = 5,
                keyPrefix = "arc:test"
            )

            cache.put("key1", CachedResponse(content = "유효한 응답"))

            verify(exactly = 1) { valueOps.set("arc:test:entry:key1", any(), Duration.ofMinutes(5)) }
        }
    }

    // ------------------------------------------------------------------
    // 3. putSemantic — 빈 콘텐츠 및 임베딩 실패 폴백
    // ------------------------------------------------------------------

    @Nested
    inner class PutSemanticEdgeCases {

        @Test
        fun `content가 빈 문자열이면 putSemantic이 저장을 건너뛴다`() = runTest {
            val (redis, valueOps, _) = buildRedisMocks()
            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = mockk(),
                objectMapper = objectMapper,
                keyPrefix = "arc:test"
            )
            val command = AgentCommand(systemPrompt = "sys", userPrompt = "query")

            cache.putSemantic(command, listOf("tool"), "key1", CachedResponse(content = ""))

            verify(exactly = 0) { valueOps.set(any(), any(), any<Duration>()) }
        }

        @Test
        fun `임베딩 실패 시 putSemantic이 일반 put으로 폴백한다`() = runTest {
            val (redis, valueOps, _) = buildRedisMocks()
            val embeddingModel = mockk<EmbeddingModel>()
            every { embeddingModel.embed(any<String>()) } throws RuntimeException("embedding 서버 오류")
            every { valueOps.set(any(), any(), any<Duration>()) } returns Unit

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = embeddingModel,
                objectMapper = objectMapper,
                ttlMinutes = 5,
                keyPrefix = "arc:test"
            )
            val command = AgentCommand(systemPrompt = "sys", userPrompt = "query")
            val response = CachedResponse(content = "임베딩 실패 폴백 응답")

            cache.putSemantic(command, listOf("tool"), "key1", response)

            // 일반 put 경로로 저장되어야 한다 (embedding 없이)
            verify(exactly = 1) { valueOps.set("arc:test:entry:key1", any(), Duration.ofMinutes(5)) }
        }
    }

    // ------------------------------------------------------------------
    // 4. getSemantic — 의미 검색 경계 케이스
    // ------------------------------------------------------------------

    @Nested
    inner class GetSemanticEdgeCases {

        @Test
        fun `임베딩 실패 시 getSemantic이 null을 반환한다`() = runTest {
            val (redis, valueOps, zsetOps) = buildRedisMocks()
            val embeddingModel = mockk<EmbeddingModel>()

            // exactKey 미스
            every { valueOps.get("arc:test:entry:exact-miss") } returns null
            // 임베딩 실패
            every { embeddingModel.embed(any<String>()) } throws RuntimeException("embedding 타임아웃")

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = embeddingModel,
                objectMapper = objectMapper,
                keyPrefix = "arc:test"
            )
            val command = AgentCommand(systemPrompt = "sys", userPrompt = "검색 실패 쿼리")

            val result = cache.getSemantic(command, listOf("tool"), "exact-miss")

            assertNull(result) { "임베딩 실패 시 getSemantic은 null을 반환해야 한다" }
        }

        @Test
        fun `스코프 불일치 후보는 시맨틱 검색에서 건너뛴다`() = runTest {
            val (redis, valueOps, zsetOps) = buildRedisMocks()
            val embeddingModel = mockk<EmbeddingModel>()

            val command = AgentCommand(systemPrompt = "sys", userPrompt = "쿼리")
            val toolNames = listOf("tool-x")
            val exactKey = "exact-miss"
            val realScope = CacheKeyBuilder.buildScopeFingerprint(command, toolNames)
            val indexKey = "arc:test:scope-index:$realScope"
            val wrongScopeKey = "wrong-scope-candidate"

            every { valueOps.get("arc:test:entry:$exactKey") } returns null
            every { zsetOps.reverseRange(indexKey, 0, 9) } returns linkedSetOf(wrongScopeKey)
            // 스코프가 다른 후보
            every { valueOps.get("arc:test:entry:$wrongScopeKey") } returns payload(
                key = wrongScopeKey,
                scope = "다른-스코프",
                embedding = listOf(1f, 0f),
                content = "다른 스코프 응답"
            )
            every { embeddingModel.embed("쿼리") } returns floatArrayOf(1f, 0f)

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = embeddingModel,
                objectMapper = objectMapper,
                similarityThreshold = 0.5,
                maxCandidates = 10,
                keyPrefix = "arc:test"
            )

            val result = cache.getSemantic(command, toolNames, exactKey)

            assertNull(result) { "스코프가 다른 후보는 반환되어서는 안 된다" }
        }

        @Test
        fun `임베딩이 비어있는 후보는 시맨틱 검색에서 건너뛴다`() = runTest {
            val (redis, valueOps, zsetOps) = buildRedisMocks()
            val embeddingModel = mockk<EmbeddingModel>()

            val command = AgentCommand(systemPrompt = "sys", userPrompt = "쿼리")
            val toolNames = listOf("tool-y")
            val exactKey = "exact-miss"
            val scope = CacheKeyBuilder.buildScopeFingerprint(command, toolNames)
            val indexKey = "arc:test:scope-index:$scope"
            val emptyEmbeddingKey = "empty-embedding-candidate"

            every { valueOps.get("arc:test:entry:$exactKey") } returns null
            every { zsetOps.reverseRange(indexKey, 0, 9) } returns linkedSetOf(emptyEmbeddingKey)
            // 임베딩이 빈 후보 (put()으로 저장된 엔트리)
            every { valueOps.get("arc:test:entry:$emptyEmbeddingKey") } returns payload(
                key = emptyEmbeddingKey,
                scope = scope,
                embedding = emptyList(),
                content = "임베딩 없는 응답"
            )
            every { embeddingModel.embed("쿼리") } returns floatArrayOf(1f, 0f)

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = embeddingModel,
                objectMapper = objectMapper,
                similarityThreshold = 0.5,
                maxCandidates = 10,
                keyPrefix = "arc:test"
            )

            val result = cache.getSemantic(command, toolNames, exactKey)

            assertNull(result) { "임베딩이 없는 후보는 시맨틱 검색에서 반환되어서는 안 된다" }
        }

        @Test
        fun `모든 후보가 유사도 임계값 미달이면 null을 반환한다`() = runTest {
            val (redis, valueOps, zsetOps) = buildRedisMocks()
            val embeddingModel = mockk<EmbeddingModel>()

            val command = AgentCommand(systemPrompt = "sys", userPrompt = "완전히 다른 질문")
            val toolNames = listOf("tool-z")
            val exactKey = "exact-miss"
            val scope = CacheKeyBuilder.buildScopeFingerprint(command, toolNames)
            val indexKey = "arc:test:scope-index:$scope"
            val lowSimKey = "low-similarity-candidate"

            every { valueOps.get("arc:test:entry:$exactKey") } returns null
            every { zsetOps.reverseRange(indexKey, 0, 9) } returns linkedSetOf(lowSimKey)
            // [1, 0]과 [0, 1]은 코사인 유사도 0.0 (직교)
            every { valueOps.get("arc:test:entry:$lowSimKey") } returns payload(
                key = lowSimKey,
                scope = scope,
                embedding = listOf(0f, 1f),
                content = "관련 없는 응답"
            )
            every { embeddingModel.embed("완전히 다른 질문") } returns floatArrayOf(1f, 0f)

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = embeddingModel,
                objectMapper = objectMapper,
                similarityThreshold = 0.95,  // 높은 임계값
                maxCandidates = 10,
                keyPrefix = "arc:test"
            )

            val result = cache.getSemantic(command, toolNames, exactKey)

            assertNull(result) { "유사도 임계값 미달 후보가 있더라도 null을 반환해야 한다" }
        }

        @Test
        fun `후보가 삭제된(Redis 없음) 경우 인덱스에서 제거하고 다음 후보를 탐색한다`() = runTest {
            val (redis, valueOps, zsetOps) = buildRedisMocks()
            val embeddingModel = mockk<EmbeddingModel>()

            val command = AgentCommand(systemPrompt = "sys", userPrompt = "탐색 질문")
            val toolNames = listOf("tool-a")
            val exactKey = "exact-miss"
            val scope = CacheKeyBuilder.buildScopeFingerprint(command, toolNames)
            val indexKey = "arc:test:scope-index:$scope"
            val missingKey = "missing-candidate"
            val validKey = "valid-candidate"

            every { valueOps.get("arc:test:entry:$exactKey") } returns null
            every { zsetOps.reverseRange(indexKey, 0, 9) } returns linkedSetOf(missingKey, validKey)
            // missingKey는 Redis에 없음
            every { valueOps.get("arc:test:entry:$missingKey") } returns null
            every { zsetOps.remove(indexKey, missingKey) } returns 1L
            // validKey는 존재하며 유사도 높음
            every { valueOps.get("arc:test:entry:$validKey") } returns payload(
                key = validKey,
                scope = scope,
                embedding = listOf(0.99f, 0.14f),
                content = "유효한 응답"
            )
            every { embeddingModel.embed("탐색 질문") } returns floatArrayOf(0.99f, 0.14f)

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = embeddingModel,
                objectMapper = objectMapper,
                similarityThreshold = 0.9,
                maxCandidates = 10,
                keyPrefix = "arc:test"
            )

            val result = cache.getSemantic(command, toolNames, exactKey)

            assertNotNull(result) { "삭제된 후보를 건너뛰고 유효한 후보를 반환해야 한다" }
            assertEquals("유효한 응답", result!!.content) {
                "Redis에 없는 후보를 건너뛰고 유효한 후보의 content를 반환해야 한다"
            }
            verify(exactly = 1) { zsetOps.remove(indexKey, missingKey) }
        }

        @Test
        fun `exactKey와 동일한 후보는 시맨틱 검색에서 건너뛴다`() = runTest {
            val (redis, valueOps, zsetOps) = buildRedisMocks()
            val embeddingModel = mockk<EmbeddingModel>()

            val command = AgentCommand(systemPrompt = "sys", userPrompt = "동일 키 테스트")
            val toolNames = listOf("tool-a")
            val exactKey = "same-key"
            val scope = CacheKeyBuilder.buildScopeFingerprint(command, toolNames)
            val indexKey = "arc:test:scope-index:$scope"

            // exactKey 미스 (정확 매칭 실패)
            every { valueOps.get("arc:test:entry:$exactKey") } returns null
            // 인덱스에서 exactKey 자체가 후보로 나옴
            every { zsetOps.reverseRange(indexKey, 0, 9) } returns linkedSetOf(exactKey)
            every { embeddingModel.embed("동일 키 테스트") } returns floatArrayOf(1f, 0f)

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = embeddingModel,
                objectMapper = objectMapper,
                similarityThreshold = 0.5,
                maxCandidates = 10,
                keyPrefix = "arc:test"
            )

            val result = cache.getSemantic(command, toolNames, exactKey)

            assertNull(result) { "exactKey 자신은 시맨틱 후보에서 제외되어야 한다" }
        }
    }

    // ------------------------------------------------------------------
    // 5. trimIndex — maxEntriesPerScope 초과 삭제
    // ------------------------------------------------------------------

    @Nested
    inner class TrimIndexOnPutSemantic {

        @Test
        fun `maxEntriesPerScope 초과 시 오래된 항목을 삭제한다`() = runTest {
            val (redis, valueOps, zsetOps) = buildRedisMocks()
            val embeddingModel = mockk<EmbeddingModel>()

            val command = AgentCommand(systemPrompt = "sys", userPrompt = "트림 테스트")
            val toolNames = listOf("tool-a")
            val exactKey = "new-key"
            val scope = CacheKeyBuilder.buildScopeFingerprint(command, toolNames)
            val indexKey = "arc:test:scope-index:$scope"
            val staleKey = "stale-key"

            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(1f, 0f)
            every { valueOps.set(any(), any(), any<Duration>()) } returns Unit
            every { zsetOps.add(any(), any(), any()) } returns true
            every { redis.expire(any(), any<Duration>()) } returns true
            // maxEntriesPerScope=2 이고 현재 크기=3 → overflow=1
            every { zsetOps.size(indexKey) } returns 3L
            every { zsetOps.range(indexKey, 0, 0) } returns linkedSetOf(staleKey)
            every { zsetOps.remove(indexKey, staleKey) } returns 1L
            every { redis.delete(listOf("arc:test:entry:$staleKey")) } returns 1L

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = embeddingModel,
                objectMapper = objectMapper,
                ttlMinutes = 5,
                maxEntriesPerScope = 2,
                keyPrefix = "arc:test"
            )

            cache.putSemantic(command, toolNames, exactKey, CachedResponse(content = "새 응답"))

            verify(exactly = 1) { zsetOps.remove(indexKey, staleKey) }
            verify(exactly = 1) { redis.delete(listOf("arc:test:entry:$staleKey")) }
        }

        @Test
        fun `maxEntriesPerScope 미초과 시 trimIndex가 삭제를 호출하지 않는다`() = runTest {
            val (redis, valueOps, zsetOps) = buildRedisMocks()
            val embeddingModel = mockk<EmbeddingModel>()

            val command = AgentCommand(systemPrompt = "sys", userPrompt = "트림 없음 테스트")
            val toolNames = listOf("tool-a")
            val exactKey = "new-key"
            val scope = CacheKeyBuilder.buildScopeFingerprint(command, toolNames)
            val indexKey = "arc:test:scope-index:$scope"

            every { embeddingModel.embed(any<String>()) } returns floatArrayOf(1f, 0f)
            every { valueOps.set(any(), any(), any<Duration>()) } returns Unit
            every { zsetOps.add(any(), any(), any()) } returns true
            every { redis.expire(any(), any<Duration>()) } returns true
            // 현재 크기 1 < maxEntriesPerScope 100 → 트림 없음
            every { zsetOps.size(indexKey) } returns 1L

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = embeddingModel,
                objectMapper = objectMapper,
                ttlMinutes = 5,
                maxEntriesPerScope = 100,
                keyPrefix = "arc:test"
            )

            cache.putSemantic(command, toolNames, exactKey, CachedResponse(content = "정상 응답"))

            verify(exactly = 0) { zsetOps.range(any(), any(), any()) }
        }
    }

    // ------------------------------------------------------------------
    // 6. invalidateAll — Redis 예외 무시 및 청크 삭제
    // ------------------------------------------------------------------

    @Nested
    inner class InvalidateAll {

        @Test
        fun `Redis 예외 발생 시 invalidateAll이 조용히 실패한다`() {
            val redis = mockk<StringRedisTemplate>()
            every { redis.opsForValue() } returns mockk()
            every { redis.opsForZSet() } returns mockk()
            every { redis.scan(any<ScanOptions>()) } throws RuntimeException("Redis 연결 오류")

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = mockk(),
                objectMapper = objectMapper,
                keyPrefix = "arc:test"
            )

            // 예외가 전파되지 않아야 한다
            cache.invalidateAll()
        }

        @Test
        fun `빈 커서인 경우 invalidateAll이 delete를 호출하지 않는다`() {
            val redis = mockk<StringRedisTemplate>()
            every { redis.opsForValue() } returns mockk()
            every { redis.opsForZSet() } returns mockk()

            // 빈 이터레이터를 갖는 구체 커서 구현
            val emptyCursor = object : Cursor<String> {
                override fun getId(): Cursor.CursorId = Cursor.CursorId.of(0L)
                override fun getCursorId(): Long = 0L
                override fun isClosed(): Boolean = false
                override fun getPosition(): Long = 0L
                override fun close() {}
                override fun hasNext(): Boolean = false
                override fun next(): String = throw NoSuchElementException()
                override fun remove() = throw UnsupportedOperationException()
            }
            every { redis.scan(any<ScanOptions>()) } returns emptyCursor

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = mockk(),
                objectMapper = objectMapper,
                keyPrefix = "arc:test"
            )

            cache.invalidateAll()

            verify(exactly = 0) { redis.delete(any<Collection<String>>()) }
        }
    }

    // ------------------------------------------------------------------
    // 7. get — 손상된 JSON 페이로드 복구
    // ------------------------------------------------------------------

    @Nested
    inner class CorruptedPayloadRecovery {

        @Test
        fun `손상된 JSON이 저장된 경우 get이 null을 반환하고 키를 삭제한다`() = runTest {
            val (redis, valueOps, _) = buildRedisMocks()

            every { valueOps.get("arc:test:entry:corrupt-key") } returns "{ invalid json %%% }"

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = mockk(),
                objectMapper = objectMapper,
                keyPrefix = "arc:test"
            )

            val result = cache.get("corrupt-key")

            assertNull(result) { "손상된 JSON 페이로드는 null을 반환해야 한다" }
            verify(exactly = 1) { redis.delete("arc:test:entry:corrupt-key") }
        }

        @Test
        fun `null 페이로드인 경우 get이 null을 반환한다`() = runTest {
            val (redis, valueOps, _) = buildRedisMocks()

            every { valueOps.get("arc:test:entry:missing-key") } returns null

            val cache = RedisSemanticResponseCache(
                redisTemplate = redis,
                embeddingModel = mockk(),
                objectMapper = objectMapper,
                keyPrefix = "arc:test"
            )

            val result = cache.get("missing-key")

            assertNull(result) { "존재하지 않는 키는 null을 반환해야 한다" }
        }
    }
}
