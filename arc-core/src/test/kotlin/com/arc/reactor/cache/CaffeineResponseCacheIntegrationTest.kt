package com.arc.reactor.cache

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.cache.impl.CaffeineResponseCache
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.prompt.ChatOptions

private val SHORT_RESPONSE = "짧은 응답" // 30자 미만

/**
 * CaffeineResponseCache + CacheKeyBuilder 통합 테스트.
 *
 * 실제 구현체를 조합하여 캐시 히트/미스, 저품질 응답 필터링,
 * 스코프 격리, TTL 만료, 최대 크기 퇴거를 검증한다.
 */
class CaffeineResponseCacheIntegrationTest {

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 캐시와 mock ChatClient가 포함된 SpringAiAgentExecutor를 생성한다. */
    private fun buildExecutor(
        cache: CaffeineResponseCache,
        fixture: AgentTestFixture,
        cacheableTemperature: Double = 0.5
    ): SpringAiAgentExecutor {
        every { fixture.requestSpec.options(any<ChatOptions>()) } returns fixture.requestSpec
        return SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = AgentTestFixture.defaultProperties(),
            responseCache = cache,
            cacheableTemperature = cacheableTemperature
        )
    }

    /** 기본 STANDARD 모드 커맨드를 생성한다. */
    private fun command(
        system: String = "시스템 프롬프트",
        user: String = "사용자 질문",
        userId: String? = null,
        tenantId: String? = null,
        temperature: Double = 0.0
    ) = AgentCommand(
        systemPrompt = system,
        userPrompt = user,
        mode = AgentMode.STANDARD,
        userId = userId,
        metadata = buildMap {
            if (tenantId != null) put("tenantId", tenantId)
        },
        temperature = temperature
    )

    // ── 테스트 그룹 ──────────────────────────────────────────────────────────

    @Nested
    inner class CacheHitAndMiss {

        @Test
        fun `동일 쿼리 두 번째 호출은 캐시에서 반환되어야 한다`() = runTest {
            val fixture = AgentTestFixture()
            val longResponse = "This is a sufficiently long response that exceeds the minimum cacheable length."
            fixture.mockCallResponse(longResponse)

            val cache = CaffeineResponseCache()
            val executor = buildExecutor(cache, fixture)
            val cmd = command(user = "파리의 수도는?")

            val first = executor.execute(cmd)
            val second = executor.execute(cmd)

            assertTrue(first.success) { "첫 번째 호출이 성공해야 한다" }
            assertTrue(second.success) { "두 번째 호출(캐시 히트)이 성공해야 한다" }
            assertEquals(first.content, second.content) { "캐시된 응답이 첫 번째 응답과 동일해야 한다" }
            // LLM은 한 번만 호출되어야 한다
            verify(exactly = 1) { fixture.requestSpec.call() }
        }

        @Test
        fun `다른 쿼리는 캐시 미스가 발생하여 LLM을 다시 호출해야 한다`() = runTest {
            val fixture = AgentTestFixture()
            val callSpec1 = fixture.mockFinalResponse("세계에서 가장 높은 산은 에베레스트입니다. 해발 8849미터입니다.")
            val callSpec2 = fixture.mockFinalResponse("세계에서 가장 깊은 바다는 마리아나 해구입니다. 깊이 11034미터입니다.")
            every { fixture.requestSpec.call() } returnsMany listOf(callSpec1, callSpec2)

            val cache = CaffeineResponseCache()
            val executor = buildExecutor(cache, fixture)

            val result1 = executor.execute(command(user = "가장 높은 산?"))
            val result2 = executor.execute(command(user = "가장 깊은 바다?"))

            assertTrue(result1.success) { "첫 번째 쿼리가 성공해야 한다" }
            assertTrue(result2.success) { "두 번째 쿼리가 성공해야 한다" }
            assertNotEquals(result1.content, result2.content) { "다른 쿼리 결과가 달라야 한다" }
            verify(exactly = 2) { fixture.requestSpec.call() }
        }
    }

    @Nested
    inner class LowQualityResponseFiltering {

        @Test
        fun `30자 미만의 짧은 응답은 캐시에 저장되지 않아야 한다`() = runTest {
            val fixture = AgentTestFixture()
            val shortCallSpec = fixture.mockFinalResponse(SHORT_RESPONSE)
            val validCallSpec = fixture.mockFinalResponse("이것은 30자 이상의 충분히 긴 응답입니다. 캐시에 저장될 수 있습니다.")
            every { fixture.requestSpec.call() } returnsMany listOf(shortCallSpec, validCallSpec)

            val cache = CaffeineResponseCache()
            val executor = buildExecutor(cache, fixture)
            val cmd = command(user = "짧은 답 테스트")

            // 첫 번째 호출 — 짧은 응답, 캐시에 저장 안 됨
            executor.execute(cmd)

            // 두 번째 호출 — 캐시 미스여야 하므로 LLM 재호출
            val second = executor.execute(cmd)

            assertTrue(second.success) { "두 번째 호출이 성공해야 한다" }
            verify(exactly = 2) { fixture.requestSpec.call() }
        }

        @Test
        fun `실패 패턴이 포함된 응답은 캐시에 저장되지 않아야 한다`() = runTest {
            val fixture = AgentTestFixture()
            val failureResponse = "죄송합니다만, 검증 가능한 출처를 찾지 못해 답변을 드리기 어렵습니다."
            val validResponse = "이것은 충분히 긴 정상 응답입니다. 검색된 정보를 기반으로 답변드립니다."
            val failCallSpec = fixture.mockFinalResponse(failureResponse)
            val validCallSpec = fixture.mockFinalResponse(validResponse)
            every { fixture.requestSpec.call() } returnsMany listOf(failCallSpec, validCallSpec)

            val cache = CaffeineResponseCache()
            val executor = buildExecutor(cache, fixture)
            val cmd = command(user = "실패 패턴 응답 테스트")

            // 첫 번째 호출 — 실패 패턴 응답, 캐시에 저장 안 됨
            executor.execute(cmd)

            // 두 번째 호출 — 캐시 미스여야 하므로 LLM 재호출
            val second = executor.execute(cmd)

            assertTrue(second.success) { "두 번째 호출이 성공해야 한다" }
            assertEquals(validResponse, second.content) { "두 번째 호출은 유효한 응답을 받아야 한다" }
            verify(exactly = 2) { fixture.requestSpec.call() }
        }

        @Test
        fun `I cannot 실패 패턴도 캐시에 저장되지 않아야 한다`() = runTest {
            val fixture = AgentTestFixture()
            val failureResponse = "I cannot provide information about that topic due to my guidelines."
            val validResponse = "이것은 30자를 초과하는 충분히 긴 정상 응답으로 캐시에 저장됩니다."
            val failCallSpec = fixture.mockFinalResponse(failureResponse)
            val validCallSpec = fixture.mockFinalResponse(validResponse)
            every { fixture.requestSpec.call() } returnsMany listOf(failCallSpec, validCallSpec)

            val cache = CaffeineResponseCache()
            val executor = buildExecutor(cache, fixture)
            val cmd = command(user = "I cannot 패턴 테스트")

            executor.execute(cmd)
            val second = executor.execute(cmd)

            assertTrue(second.success) { "두 번째 호출이 성공해야 한다" }
            verify(exactly = 2) { fixture.requestSpec.call() }
        }
    }

    @Nested
    inner class ScopeFingerprintIsolation {

        @Test
        fun `다른 userId는 독립적인 캐시 키를 가져야 한다`() = runTest {
            val fixture = AgentTestFixture()
            val respA = fixture.mockFinalResponse("사용자 A를 위한 충분히 긴 응답입니다. 개인 정보가 포함될 수 있습니다.")
            val respB = fixture.mockFinalResponse("사용자 B를 위한 충분히 긴 응답입니다. 개인 정보가 포함될 수 있습니다.")
            every { fixture.requestSpec.call() } returnsMany listOf(respA, respB)

            val cache = CaffeineResponseCache()
            val executor = buildExecutor(cache, fixture)

            val cmdA = command(user = "내 정보 알려줘", userId = "user-alpha")
            val cmdB = command(user = "내 정보 알려줘", userId = "user-beta")

            val resultA = executor.execute(cmdA)
            val resultB = executor.execute(cmdB)

            // 두 사용자가 동일한 쿼리를 해도 서로 다른 캐시 키로 격리되어야 함
            verify(exactly = 2) { fixture.requestSpec.call() }
            assertNotEquals(resultA.content, resultB.content) { "사용자 A와 B의 응답이 격리되어야 한다" }
        }

        @Test
        fun `다른 tenantId는 독립적인 캐시 키를 가져야 한다`() = runTest {
            val fixture = AgentTestFixture()
            val respT1 = fixture.mockFinalResponse("테넌트 1의 충분히 긴 전용 응답입니다. 격리가 보장되어야 합니다.")
            val respT2 = fixture.mockFinalResponse("테넌트 2의 충분히 긴 전용 응답입니다. 격리가 보장되어야 합니다.")
            every { fixture.requestSpec.call() } returnsMany listOf(respT1, respT2)

            val cache = CaffeineResponseCache()
            val executor = buildExecutor(cache, fixture)

            val cmdT1 = command(user = "공통 질문", tenantId = "tenant-001")
            val cmdT2 = command(user = "공통 질문", tenantId = "tenant-002")

            val resultT1 = executor.execute(cmdT1)
            val resultT2 = executor.execute(cmdT2)

            verify(exactly = 2) { fixture.requestSpec.call() }
            assertNotEquals(resultT1.content, resultT2.content) { "테넌트 간 캐시 누출이 없어야 한다" }
        }

        @Test
        fun `동일 userId + tenantId는 캐시를 공유해야 한다`() = runTest {
            val fixture = AgentTestFixture()
            fixture.mockCallResponse("동일 스코프에서 캐시 히트되어야 하는 충분히 긴 응답입니다.")

            val cache = CaffeineResponseCache()
            val executor = buildExecutor(cache, fixture)

            val cmd = command(user = "공통 질문", userId = "same-user", tenantId = "same-tenant")

            val first = executor.execute(cmd)
            val second = executor.execute(cmd)

            assertTrue(first.success) { "첫 번째 호출이 성공해야 한다" }
            assertTrue(second.success) { "두 번째 호출(캐시 히트)이 성공해야 한다" }
            verify(exactly = 1) { fixture.requestSpec.call() }
        }
    }

    @Nested
    inner class CacheKeyBuilderDirectUsage {

        @Test
        fun `빌드된 키는 64자 SHA-256 hex 문자열이어야 한다`() {
            val cmd = command(user = "테스트")
            val key = CacheKeyBuilder.buildKey(cmd, listOf("tool-a", "tool-b"))

            assertEquals(64, key.length) { "SHA-256 hex 키는 64자여야 한다" }
            assertTrue(key.matches(Regex("[0-9a-f]+"))) { "키는 소문자 hex여야 한다: $key" }
        }

        @Test
        fun `put-get 정확 매칭은 캐시 히트를 반환해야 한다`() = runTest {
            val cache = CaffeineResponseCache()
            val cmd = command(user = "정확 매칭 테스트")
            val key = CacheKeyBuilder.buildKey(cmd, listOf("my-tool"))
            val stored = CachedResponse(
                content = "정확히 매칭된 캐시 응답입니다.",
                toolsUsed = listOf("my-tool")
            )

            cache.put(key, stored)
            val retrieved = cache.get(key)

            assertNotNull(retrieved) { "동일 키로 저장한 응답이 조회되어야 한다" }
            assertEquals(stored.content, retrieved!!.content) { "저장된 콘텐츠와 조회된 콘텐츠가 일치해야 한다" }
            assertEquals(stored.toolsUsed, retrieved.toolsUsed) { "저장된 toolsUsed와 조회된 toolsUsed가 일치해야 한다" }
        }

        @Test
        fun `다른 쿼리는 다른 키를 생성하여 캐시 미스가 발생해야 한다`() = runTest {
            val cache = CaffeineResponseCache()
            val cmdA = command(user = "질문 A")
            val cmdB = command(user = "질문 B")
            val keyA = CacheKeyBuilder.buildKey(cmdA, emptyList())
            val keyB = CacheKeyBuilder.buildKey(cmdB, emptyList())

            cache.put(keyA, CachedResponse(content = "A의 응답"))

            val hitA = cache.get(keyA)
            val missB = cache.get(keyB)

            assertNotNull(hitA) { "질문 A의 키로 히트가 발생해야 한다" }
            assertNull(missB) { "질문 B의 키는 미스가 발생해야 한다" }
        }

        @Test
        fun `scopeFingerprint는 userId가 다를 때 다른 값을 반환해야 한다`() {
            val cmdA = command(user = "공통 질문", userId = "user-1")
            val cmdB = command(user = "공통 질문", userId = "user-2")

            val fpA = CacheKeyBuilder.buildScopeFingerprint(cmdA, emptyList())
            val fpB = CacheKeyBuilder.buildScopeFingerprint(cmdB, emptyList())

            assertNotEquals(fpA, fpB) { "userId가 다를 때 scopeFingerprint가 달라야 한다" }
        }

        @Test
        fun `scopeFingerprint는 userPrompt가 달라도 동일해야 한다`() {
            val cmdA = command(user = "질문 A버전")
            val cmdB = command(user = "질문 B버전")

            val fpA = CacheKeyBuilder.buildScopeFingerprint(cmdA, listOf("tool"))
            val fpB = CacheKeyBuilder.buildScopeFingerprint(cmdB, listOf("tool"))

            assertEquals(fpA, fpB) { "scopeFingerprint는 userPrompt 변형에 안정적이어야 한다" }
        }
    }

    @Nested
    inner class TtlAndEviction {

        @Test
        fun `TTL 만료 후 캐시 항목은 반환되지 않아야 한다`() = runTest {
            // TTL=0분은 Caffeine에서 즉시 만료를 의미한다
            val cache = CaffeineResponseCache(ttlMinutes = 0)
            val key = CacheKeyBuilder.buildKey(command(user = "만료 테스트"), emptyList())

            cache.put(key, CachedResponse(content = "만료될 응답"))
            cache.cleanUp()

            val result = cache.get(key)

            assertNull(result) { "TTL 만료 후 캐시 항목이 반환되지 않아야 한다" }
        }

        @Test
        fun `maxSize 초과 시 오래된 항목이 퇴거되어야 한다`() = runTest {
            val cache = CaffeineResponseCache(maxSize = 3)

            // maxSize보다 더 많은 항목 저장
            for (i in 1..8) {
                val key = CacheKeyBuilder.buildKey(
                    command(user = "질문 $i"),
                    emptyList()
                )
                cache.put(key, CachedResponse(content = "응답 $i"))
            }
            cache.cleanUp()

            var hitCount = 0
            for (i in 1..8) {
                val key = CacheKeyBuilder.buildKey(
                    command(user = "질문 $i"),
                    emptyList()
                )
                if (cache.get(key) != null) hitCount++
            }

            assertTrue(hitCount <= 3) {
                "maxSize=3 초과 시 퇴거가 발생하여 최대 3개만 남아야 하지만 ${hitCount}개가 남아 있다"
            }
        }

        @Test
        fun `invalidateAll 후 모든 항목이 제거되어야 한다`() = runTest {
            val cache = CaffeineResponseCache()

            val keys = (1..5).map { i ->
                CacheKeyBuilder.buildKey(command(user = "질문 $i"), emptyList()).also { key ->
                    cache.put(key, CachedResponse(content = "응답 $i 내용이 충분히 길어야 한다"))
                }
            }

            cache.invalidateAll()

            for ((index, key) in keys.withIndex()) {
                assertNull(cache.get(key)) { "invalidateAll 후 질문 ${index + 1}의 항목이 남아 있으면 안 된다" }
            }
        }
    }
}
