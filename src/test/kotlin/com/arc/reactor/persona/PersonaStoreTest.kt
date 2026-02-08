package com.arc.reactor.persona

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PersonaStoreTest {

    private lateinit var store: InMemoryPersonaStore

    @BeforeEach
    fun setup() {
        store = InMemoryPersonaStore()
    }

    @Nested
    inner class BasicCrud {

        @Test
        fun `should have default persona on initialization`() {
            val personas = store.list()

            assertEquals(1, personas.size) { "Should have 1 default persona" }
            assertEquals("default", personas[0].id) { "Default persona ID should be 'default'" }
            assertEquals("Default Assistant", personas[0].name) { "Default persona name should be 'Default Assistant'" }
            assertTrue(personas[0].isDefault) { "Default persona should be marked as default" }
        }

        @Test
        fun `should save and retrieve a persona`() {
            val persona = Persona(
                id = "test-1",
                name = "Test Persona",
                systemPrompt = "You are a test agent."
            )

            store.save(persona)
            val retrieved = store.get("test-1")

            assertNotNull(retrieved) { "Saved persona should be retrievable" }
            assertEquals("Test Persona", retrieved!!.name) { "Name should match" }
            assertEquals("You are a test agent.", retrieved.systemPrompt) { "System prompt should match" }
        }

        @Test
        fun `should list all personas sorted by createdAt`() {
            store.save(Persona(id = "p-2", name = "Second", systemPrompt = "prompt-2"))
            store.save(Persona(id = "p-3", name = "Third", systemPrompt = "prompt-3"))

            val personas = store.list()

            assertEquals(3, personas.size) { "Should have default + 2 saved personas" }
            assertEquals("default", personas[0].id) { "Default should be first (oldest)" }
        }

        @Test
        fun `should delete a persona`() {
            store.save(Persona(id = "to-delete", name = "Delete Me", systemPrompt = "prompt"))

            store.delete("to-delete")

            assertNull(store.get("to-delete")) { "Deleted persona should not be retrievable" }
        }

        @Test
        fun `should return null for nonexistent persona`() {
            assertNull(store.get("nonexistent")) { "Should return null for unknown ID" }
        }
    }

    @Nested
    inner class DefaultHandling {

        @Test
        fun `should return default persona via getDefault`() {
            val defaultPersona = store.getDefault()

            assertNotNull(defaultPersona) { "Should have a default persona" }
            assertTrue(defaultPersona!!.isDefault) { "getDefault() result should have isDefault=true" }
            assertEquals("default", defaultPersona.id) { "Default persona ID should be 'default'" }
        }

        @Test
        fun `should clear existing default when saving new default`() {
            store.save(Persona(id = "new-default", name = "New Default", systemPrompt = "prompt", isDefault = true))

            val oldDefault = store.get("default")
            val newDefault = store.get("new-default")

            assertFalse(oldDefault!!.isDefault) { "Old default should be cleared" }
            assertTrue(newDefault!!.isDefault) { "New persona should be the default" }
        }

        @Test
        fun `should clear existing default when updating isDefault to true`() {
            store.save(Persona(id = "candidate", name = "Candidate", systemPrompt = "prompt"))

            store.update("candidate", name = null, systemPrompt = null, isDefault = true)

            val oldDefault = store.get("default")
            val updated = store.get("candidate")

            assertFalse(oldDefault!!.isDefault) { "Old default should be cleared after update" }
            assertTrue(updated!!.isDefault) { "Updated persona should be the default" }
        }

        @Test
        fun `getDefault should return null when no default exists`() {
            // Delete the seed default
            store.delete("default")

            assertNull(store.getDefault()) { "Should return null when no default persona exists" }
        }
    }

    @Nested
    inner class UpdateBehavior {

        @Test
        fun `should partially update only provided fields`() {
            store.save(Persona(id = "partial", name = "Original", systemPrompt = "Original Prompt"))

            val updated = store.update("partial", name = "Updated Name", systemPrompt = null, isDefault = null)

            assertNotNull(updated) { "Update should return the updated persona" }
            assertEquals("Updated Name", updated!!.name) { "Name should be updated" }
            assertEquals("Original Prompt", updated.systemPrompt) { "System prompt should remain unchanged" }
        }

        @Test
        fun `should return null when updating nonexistent persona`() {
            val result = store.update("nonexistent", name = "New Name", systemPrompt = null, isDefault = null)

            assertNull(result) { "Update of nonexistent persona should return null" }
        }

        @Test
        fun `delete should be idempotent for nonexistent persona`() {
            assertDoesNotThrow { store.delete("nonexistent") }
        }
    }
}
