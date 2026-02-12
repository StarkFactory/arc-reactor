package com.arc.reactor.intent

import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class IntentRegistryTest {

    private lateinit var registry: InMemoryIntentRegistry

    @BeforeEach
    fun setUp() {
        registry = InMemoryIntentRegistry()
    }

    @Nested
    inner class BasicCrud {

        @Test
        fun `save and get intent definition`() {
            val intent = createIntent("greeting", "Simple greetings")
            registry.save(intent)

            val found = registry.get("greeting")
            assertNotNull(found) { "Expected intent to be found after save" }
            assertEquals("greeting", found!!.name) { "Intent name should match" }
            assertEquals("Simple greetings", found.description) { "Intent description should match" }
        }

        @Test
        fun `get returns null for non-existent intent`() {
            val found = registry.get("non-existent")
            assertNull(found) { "Expected null for non-existent intent" }
        }

        @Test
        fun `list returns all intents sorted by name`() {
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
        fun `delete removes intent`() {
            registry.save(createIntent("greeting", "Greetings"))
            registry.delete("greeting")

            val found = registry.get("greeting")
            assertNull(found) { "Expected intent to be null after delete" }
        }

        @Test
        fun `delete is idempotent for non-existent intent`() {
            assertDoesNotThrow { registry.delete("non-existent") }
        }
    }

    @Nested
    inner class UpdateBehavior {

        @Test
        fun `save existing intent preserves createdAt and updates updatedAt`() {
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
        fun `listEnabled returns only enabled intents`() {
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
        fun `listEnabled returns empty list when no intents are enabled`() {
            registry.save(createIntent("a", "A", enabled = false))
            registry.save(createIntent("b", "B", enabled = false))

            val enabled = registry.listEnabled()
            assertTrue(enabled.isEmpty()) { "Expected empty list when no intents are enabled" }
        }
    }

    @Nested
    inner class ProfileStorage {

        @Test
        fun `intent profile is preserved on save and get`() {
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
