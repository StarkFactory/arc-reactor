package com.arc.reactor.tool.idempotency

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
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
    }
}
