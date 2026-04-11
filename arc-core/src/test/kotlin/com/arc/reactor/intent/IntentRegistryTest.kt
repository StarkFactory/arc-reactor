package com.arc.reactor.intent

import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * IntentRegistry에 대한 테스트.
 *
 * 인텐트 레지스트리의 등록/조회 동작을 검증합니다.
 */
class IntentRegistryTest {

    private lateinit var registry: InMemoryIntentRegistry

    @BeforeEach
    fun setUp() {
        registry = InMemoryIntentRegistry()
    }

    @Nested
    inner class BasicCrud {

        @Test
        fun `and get intent definition를 저장한다`() {
            val intent = createIntent("greeting", "Simple greetings")
            registry.save(intent)

            val found = registry.get("greeting")
            assertNotNull(found) { "Expected intent to be found after save" }
            assertEquals("greeting", found!!.name) { "Intent name should match" }
            assertEquals("Simple greetings", found.description) { "Intent description should match" }
        }

        @Test
        fun `returns null for non-existent intent를 가져온다`() {
            val found = registry.get("non-existent")
            assertNull(found) { "Expected null for non-existent intent" }
        }

        @Test
        fun `목록 returns all intents sorted by name`() {
            registry.save(createIntent("order", "Orders"))
            registry.save(createIntent("greeting", "Greetings"))
            registry.save(createIntent("refund", "Refunds"))

            val all = registry.list()
            assertEquals(3, all.size) { "Expected 3 intents" }
            assertEquals(listOf("greeting", "order", "refund"), all.map { it.name }) {
                "Intents should be sorted by name"
            }
        }

        @Test
        fun `removes intent를 삭제한다`() {
            registry.save(createIntent("greeting", "Greetings"))
            registry.delete("greeting")

            val found = registry.get("greeting")
            assertNull(found) { "Expected intent to be null after delete" }
        }

        @Test
        fun `delete은(는) idempotent for non-existent intent이다`() {
            assertDoesNotThrow { registry.delete("non-existent") }
        }
    }

    @Nested
    inner class UpdateBehavior {

        @Test
        fun `existing intent preserves createdAt and updates updatedAt를 저장한다`() {
            val original = createIntent("greeting", "V1")
            registry.save(original)
            val savedOriginal = registry.get("greeting")!!

            Thread.sleep(10) // ensure timestamp difference
            val updated = original.copy(description = "V2")
            registry.save(updated)
            val savedUpdated = registry.get("greeting")!!

            assertEquals("V2", savedUpdated.description) { "Description should be updated" }
            assertEquals(savedOriginal.createdAt, savedUpdated.createdAt) {
                "createdAt should be preserved on update"
            }
            assertTrue(savedUpdated.updatedAt >= savedOriginal.updatedAt) {
                "updatedAt should be >= original updatedAt"
            }
        }
    }

    @Nested
    inner class EnabledFiltering {

        @Test
        fun `listEnabled은(는) returns only enabled intents`() {
            registry.save(createIntent("greeting", "Greetings", enabled = true))
            registry.save(createIntent("disabled", "Disabled", enabled = false))
            registry.save(createIntent("order", "Orders", enabled = true))

            val enabled = registry.listEnabled()
            assertEquals(2, enabled.size) { "Expected 2 enabled intents" }
            assertTrue(enabled.all { it.enabled }) { "All returned intents should be enabled" }
            assertFalse(enabled.any { it.name == "disabled" }) {
                "Disabled intent should not be in enabled list"
            }
        }

        @Test
        fun `no intents are enabled일 때 listEnabled returns empty list`() {
            registry.save(createIntent("a", "A", enabled = false))
            registry.save(createIntent("b", "B", enabled = false))

            val enabled = registry.listEnabled()
            assertTrue(enabled.isEmpty()) { "Expected empty list when no intents are enabled" }
        }
    }

    @Nested
    inner class BoundedCacheBehavior {

        /**
         * R305 회귀: Caffeine 마이그레이션 후 maxEntries 상한이 동작한다.
         *
         * 과거 ConcurrentHashMap은 무제한 성장 → OOM 위험. Caffeine bounded cache로
         * 상한 초과 시 W-TinyLFU 정책으로 evict 되어야 한다.
         */
        @Test
        fun `maxEntries 초과 시 Caffeine bounded cache가 evict해야 한다`() {
            val bounded = InMemoryIntentRegistry(maxEntries = 5)
            repeat(100) { i ->
                bounded.save(createIntent("intent-$i", "desc-$i"))
            }
            bounded.forceCleanUp()
            val all = bounded.list()
            // Caffeine은 W-TinyLFU 정책이라 정확한 maxSize가 아닌 근사치로 수렴.
            // 100개 저장 후 상한 5 초과분이 실제로 축출되었는지(size < 100) 확인.
            assertTrue(all.size < 100) {
                "Expected eviction to reduce size below 100, got ${all.size}"
            }
            assertTrue(all.size <= 20) {
                "Expected Caffeine bounded cache to converge near maxSize=5, got ${all.size}"
            }
        }

        @Test
        fun `기본 maxEntries는 10000이다`() {
            assertEquals(10_000L, InMemoryIntentRegistry.DEFAULT_MAX_INTENTS) {
                "Expected default max entries to be 10000"
            }
        }
    }

    @Nested
    inner class ProfileStorage {

        @Test
        fun `intent profile은(는) preserved on save and get이다`() {
            val profile = IntentProfile(
                model = "gemini",
                temperature = 0.5,
                maxToolCalls = 3,
                allowedTools = setOf("tool1", "tool2"),
                systemPrompt = "Custom prompt"
            )
            val intent = createIntent("order", "Orders", profile = profile)
            registry.save(intent)

            val found = registry.get("order")!!
            assertEquals("gemini", found.profile.model) { "Profile model should be preserved" }
            assertEquals(0.5, found.profile.temperature) { "Profile temperature should be preserved" }
            assertEquals(3, found.profile.maxToolCalls) { "Profile maxToolCalls should be preserved" }
            assertEquals(setOf("tool1", "tool2"), found.profile.allowedTools) {
                "Profile allowedTools should be preserved"
            }
            assertEquals("Custom prompt", found.profile.systemPrompt) {
                "Profile systemPrompt should be preserved"
            }
        }
    }

    private fun createIntent(
        name: String,
        description: String,
        enabled: Boolean = true,
        profile: IntentProfile = IntentProfile()
    ) = IntentDefinition(
        name = name,
        description = description,
        enabled = enabled,
        profile = profile
    )
}
