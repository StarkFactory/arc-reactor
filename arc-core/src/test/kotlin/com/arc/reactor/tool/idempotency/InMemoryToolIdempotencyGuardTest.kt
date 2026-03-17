package com.arc.reactor.tool.idempotency

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [InMemoryToolIdempotencyGuard] 단위 테스트.
 */
class InMemoryToolIdempotencyGuardTest {

    @Nested
    inner class CheckAndGet {

        @Test
        fun `캐시가 비어있으면 null을 반환한다`() {
            val guard = InMemoryToolIdempotencyGuard()

            val result = guard.checkAndGet("myTool", mapOf("key" to "value"))

            result.shouldBeNull()
        }

        @Test
        fun `저장된 결과가 있으면 캐시된 결과를 반환한다`() {
            val guard = InMemoryToolIdempotencyGuard()
            val args = mapOf<String, Any?>("query" to "hello")

            guard.store("searchTool", args, "search result")
            val cached = guard.checkAndGet("searchTool", args)

            cached.shouldNotBeNull()
            cached.result shouldBe "search result"
            cached.cachedAt.shouldNotBeNull()
        }

        @Test
        fun `다른 도구명이면 null을 반환한다`() {
            val guard = InMemoryToolIdempotencyGuard()
            val args = mapOf<String, Any?>("query" to "hello")

            guard.store("toolA", args, "resultA")
            val cached = guard.checkAndGet("toolB", args)

            cached.shouldBeNull()
        }

        @Test
        fun `다른 인수이면 null을 반환한다`() {
            val guard = InMemoryToolIdempotencyGuard()

            guard.store("myTool", mapOf("a" to 1), "result1")
            val cached = guard.checkAndGet("myTool", mapOf("a" to 2))

            cached.shouldBeNull()
        }

        @Test
        fun `인수 순서가 달라도 동일한 결과를 반환한다`() {
            val guard = InMemoryToolIdempotencyGuard()
            val args1 = linkedMapOf<String, Any?>("b" to 2, "a" to 1)
            val args2 = linkedMapOf<String, Any?>("a" to 1, "b" to 2)

            guard.store("myTool", args1, "result")
            val cached = guard.checkAndGet("myTool", args2)

            cached.shouldNotBeNull()
            cached.result shouldBe "result"
        }
    }

    @Nested
    inner class Store {

        @Test
        fun `동일한 키로 다시 저장하면 결과가 갱신된다`() {
            val guard = InMemoryToolIdempotencyGuard()
            val args = mapOf<String, Any?>("key" to "value")

            guard.store("myTool", args, "first")
            guard.store("myTool", args, "second")
            val cached = guard.checkAndGet("myTool", args)

            cached.shouldNotBeNull()
            cached.result shouldBe "second"
        }

        @Test
        fun `빈 인수도 정상 저장된다`() {
            val guard = InMemoryToolIdempotencyGuard()
            val args = emptyMap<String, Any?>()

            guard.store("myTool", args, "result")
            val cached = guard.checkAndGet("myTool", args)

            cached.shouldNotBeNull()
            cached.result shouldBe "result"
        }

        @Test
        fun `null 값 인수도 정상 처리된다`() {
            val guard = InMemoryToolIdempotencyGuard()
            val args = mapOf<String, Any?>("key" to null)

            guard.store("myTool", args, "result")
            val cached = guard.checkAndGet("myTool", args)

            cached.shouldNotBeNull()
            cached.result shouldBe "result"
        }
    }

    @Nested
    inner class IdempotencyKey {

        @Test
        fun `동일한 입력에 대해 동일한 키를 생성한다`() {
            val key1 = InMemoryToolIdempotencyGuard.buildIdempotencyKey(
                "tool", mapOf("a" to 1)
            )
            val key2 = InMemoryToolIdempotencyGuard.buildIdempotencyKey(
                "tool", mapOf("a" to 1)
            )

            key1 shouldBe key2
        }

        @Test
        fun `다른 도구명에 대해 다른 키를 생성한다`() {
            val key1 = InMemoryToolIdempotencyGuard.buildIdempotencyKey(
                "toolA", mapOf("a" to 1)
            )
            val key2 = InMemoryToolIdempotencyGuard.buildIdempotencyKey(
                "toolB", mapOf("a" to 1)
            )

            key1 shouldBe key1 // 자기 자신과는 같다
            (key1 != key2) shouldBe true
        }

        @Test
        fun `키는 비어있지 않은 해시 문자열이다`() {
            val key = InMemoryToolIdempotencyGuard.buildIdempotencyKey(
                "tool", mapOf("x" to "y")
            )

            key.shouldNotBeEmpty()
        }

        @Test
        fun `매우 긴 인수도 충돌 없이 고유한 키를 생성한다`() {
            val longValue = "v".repeat(100_000)
            val key1 = InMemoryToolIdempotencyGuard.buildIdempotencyKey(
                "tool", mapOf("key" to longValue)
            )
            val key2 = InMemoryToolIdempotencyGuard.buildIdempotencyKey(
                "tool", mapOf("key" to longValue)
            )
            val keyDiff = InMemoryToolIdempotencyGuard.buildIdempotencyKey(
                "tool", mapOf("key" to (longValue + "x"))
            )

            key1 shouldBe key2
            (key1 != keyDiff) shouldBe true
        }

        @Test
        fun `특수 문자가 포함된 도구명도 고유한 키를 생성한다`() {
            val keyNormal = InMemoryToolIdempotencyGuard.buildIdempotencyKey(
                "my-tool_v2.0", mapOf("a" to 1)
            )
            val keySpecial = InMemoryToolIdempotencyGuard.buildIdempotencyKey(
                "my-tool_v2.0!", mapOf("a" to 1)
            )

            keyNormal.shouldNotBeEmpty()
            keySpecial.shouldNotBeEmpty()
            (keyNormal != keySpecial) shouldBe true
        }

        @Test
        fun `유니코드 도구명과 인수도 올바르게 처리된다`() {
            val key1 = InMemoryToolIdempotencyGuard.buildIdempotencyKey(
                "검색도구", mapOf("쿼리" to "안녕하세요")
            )
            val key2 = InMemoryToolIdempotencyGuard.buildIdempotencyKey(
                "검색도구", mapOf("쿼리" to "안녕하세요")
            )
            val keyDiff = InMemoryToolIdempotencyGuard.buildIdempotencyKey(
                "검색도구", mapOf("쿼리" to "goodbye")
            )

            key1 shouldBe key2
            (key1 != keyDiff) shouldBe true
        }
    }

    @Nested
    inner class EdgeCasesTest {

        @Test
        fun `매우 긴 인수 문자열도 저장하고 조회할 수 있다`() {
            val guard = InMemoryToolIdempotencyGuard()
            val longValue = "x".repeat(50_000)
            val args = mapOf<String, Any?>("data" to longValue)

            guard.store("bigTool", args, "big result")
            val cached = guard.checkAndGet("bigTool", args)

            cached.shouldNotBeNull()
            cached.result shouldBe "big result"
        }

        @Test
        fun `특수 문자(콜론, 쉼표, 등호)가 포함된 도구명도 정상 동작한다`() {
            val guard = InMemoryToolIdempotencyGuard()
            val args = mapOf<String, Any?>("k" to "v")

            guard.store("tool:with=special,chars", args, "result")
            val cached = guard.checkAndGet("tool:with=special,chars", args)

            cached.shouldNotBeNull()
            cached.result shouldBe "result"
        }

        @Test
        fun `도구명이 빈 문자열이어도 저장하고 조회할 수 있다`() {
            val guard = InMemoryToolIdempotencyGuard()
            val args = mapOf<String, Any?>("k" to "v")

            guard.store("", args, "empty-name result")
            val cached = guard.checkAndGet("", args)

            cached.shouldNotBeNull()
            cached.result shouldBe "empty-name result"
        }

        @Test
        fun `인수 값이 숫자, 불리언, 중첩 문자열 등 혼합 타입이어도 동일 키를 생성한다`() {
            val guard = InMemoryToolIdempotencyGuard()
            val args = mapOf<String, Any?>("count" to 42, "active" to true, "label" to "test")

            guard.store("mixedTool", args, "mixed result")
            val cached = guard.checkAndGet("mixedTool", args)

            cached.shouldNotBeNull()
            cached.result shouldBe "mixed result"
        }

        @Test
        fun `동시 store와 checkAndGet은 데이터 손실 없이 처리되어야 한다`() = runTest {
            val guard = InMemoryToolIdempotencyGuard()
            val iterations = 100

            // 동시에 여러 도구 저장
            val jobs = (0 until iterations).map { i ->
                launch(Dispatchers.Default) {
                    val args = mapOf<String, Any?>("id" to i)
                    guard.store("tool-$i", args, "result-$i")
                }
            }
            jobs.forEach { it.join() }

            // 저장된 결과 검증
            for (i in 0 until iterations) {
                val args = mapOf<String, Any?>("id" to i)
                val cached = guard.checkAndGet("tool-$i", args)
                cached.shouldNotBeNull()
                cached.result shouldBe "result-$i"
            }
        }
    }
}
