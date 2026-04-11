package com.arc.reactor.agent.multiagent

import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [DefaultSharedAgentContext] 단위 테스트.
 *
 * put/get/getTyped/getAll 동작을 검증한다.
 */
class SharedAgentContextTest {

    private lateinit var context: DefaultSharedAgentContext

    @BeforeEach
    fun setUp() {
        context = DefaultSharedAgentContext()
    }

    @Nested
    inner class PutAndGet {

        @Test
        fun `저장한 값을 조회할 수 있어야 한다`() {
            context.put("key", "value")

            context.get("key") shouldBe "value"
        }

        @Test
        fun `존재하지 않는 키는 null을 반환해야 한다`() {
            context.get("unknown").shouldBeNull()
        }

        @Test
        fun `동일 키로 저장하면 덮어써야 한다`() {
            context.put("key", "old")
            context.put("key", "new")

            context.get("key") shouldBe "new"
        }

        @Test
        fun `다양한 타입의 값을 저장할 수 있어야 한다`() {
            context.put("string", "hello")
            context.put("number", 42)
            context.put("list", listOf("a", "b"))

            context.get("string") shouldBe "hello"
            context.get("number") shouldBe 42
            context.get("list") shouldBe listOf("a", "b")
        }
    }

    @Nested
    inner class GetTyped {

        @Test
        fun `타입이 일치하면 캐스팅된 값을 반환해야 한다`() {
            context.put("name", "arc-reactor")

            val result = context.getTyped("name", String::class.java)

            result shouldBe "arc-reactor"
        }

        @Test
        fun `타입이 불일치하면 null을 반환해야 한다`() {
            context.put("number", 42)

            val result = context.getTyped("number", String::class.java)

            result.shouldBeNull()
        }

        @Test
        fun `존재하지 않는 키는 null을 반환해야 한다`() {
            val result = context.getTyped("missing", String::class.java)

            result.shouldBeNull()
        }
    }

    @Nested
    inner class GetAll {

        @Test
        fun `저장된 모든 키-값 쌍을 반환해야 한다`() {
            context.put("a", 1)
            context.put("b", 2)

            val all = context.getAll()

            all shouldHaveSize 2
            all shouldContainExactly mapOf("a" to 1, "b" to 2)
        }

        @Test
        fun `비어있으면 빈 맵을 반환해야 한다`() {
            context.getAll() shouldHaveSize 0
        }

        @Test
        fun `반환된 맵은 원본에 영향을 주지 않아야 한다`() {
            context.put("key", "value")

            val snapshot = context.getAll()
            // snapshot은 불변 복사본이므로 원본에 영향 없음
            context.put("key2", "value2")

            snapshot shouldHaveSize 1
            context.getAll() shouldHaveSize 2
        }
    }

    @Nested
    inner class R311BoundedCache {

        /**
         * R311 회귀: ConcurrentHashMap → Caffeine bounded cache 마이그레이션.
         *
         * maxEntries 상한을 넘으면 W-TinyLFU 정책으로 evict되어야 한다.
         */
        @Test
        fun `maxEntries 초과 시 Caffeine이 evict해야 한다`() {
            val bounded = DefaultSharedAgentContext(maxEntries = 5)
            repeat(100) { i ->
                bounded.put("key-$i", "value-$i")
            }
            bounded.forceCleanUp()
            val all = bounded.getAll()
            assertTrue(all.size < 100) {
                "Expected eviction to reduce size below 100, got ${all.size}"
            }
            assertTrue(all.size <= 20) {
                "Expected Caffeine bounded cache to converge near maxEntries=5, got ${all.size}"
            }
        }

        @Test
        fun `DEFAULT_MAX_ENTRIES는 10000이다`() {
            assertEquals(
                10_000L,
                DefaultSharedAgentContext.DEFAULT_MAX_ENTRIES,
                "Expected default max entries to be 10000"
            )
        }
    }
}
