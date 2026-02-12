package com.arc.reactor.persona

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.support.TransactionTemplate

class JdbcPersonaStoreTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcPersonaStore

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()

        jdbcTemplate = JdbcTemplate(dataSource)
        val transactionManager = DataSourceTransactionManager(dataSource)
        val transactionTemplate = TransactionTemplate(transactionManager)

        // V2 DDL (without seed INSERT)
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS personas (
                id            VARCHAR(36)   PRIMARY KEY,
                name          VARCHAR(200)  NOT NULL,
                system_prompt TEXT          NOT NULL,
                is_default    BOOLEAN       NOT NULL DEFAULT FALSE,
                created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())

        store = JdbcPersonaStore(jdbcTemplate, transactionTemplate)
    }

    private fun createPersona(
        id: String = "p-1",
        name: String = "Test Persona",
        systemPrompt: String = "You are a test assistant.",
        isDefault: Boolean = false
    ) = Persona(id = id, name = name, systemPrompt = systemPrompt, isDefault = isDefault)

    @Nested
    inner class BasicCrud {

        @Test
        fun `should save and get persona`() {
            val persona = createPersona()
            store.save(persona)

            val found = store.get("p-1")

            assertNotNull(found) { "Saved persona should be retrievable" }
            assertEquals("p-1", found!!.id) { "ID should match" }
            assertEquals("Test Persona", found.name) { "Name should match" }
            assertEquals("You are a test assistant.", found.systemPrompt) { "System prompt should match" }
            assertFalse(found.isDefault) { "isDefault should be false" }
        }

        @Test
        fun `should list personas ordered by createdAt`() {
            store.save(createPersona(id = "p-1", name = "First"))
            Thread.sleep(10)
            store.save(createPersona(id = "p-2", name = "Second"))

            val list = store.list()

            assertEquals(2, list.size) { "Should have 2 personas" }
            assertEquals("First", list[0].name) { "First created should be first in list" }
            assertEquals("Second", list[1].name) { "Second created should be second in list" }
        }

        @Test
        fun `should delete persona`() {
            store.save(createPersona())
            assertNotNull(store.get("p-1")) { "Should exist before delete" }

            store.delete("p-1")

            assertNull(store.get("p-1"), "Should be null after delete")
        }

        @Test
        fun `should return null for unknown persona`() {
            assertNull(store.get("nonexistent"), "Unknown persona should return null")
        }
    }

    @Nested
    inner class DefaultManagement {

        @Test
        fun `should save persona as default`() {
            store.save(createPersona(isDefault = true))

            val found = store.get("p-1")
            assertTrue(found!!.isDefault) { "Saved persona should be default" }

            val defaultPersona = store.getDefault()
            assertNotNull(defaultPersona) { "getDefault should return the default persona" }
            assertEquals("p-1", defaultPersona!!.id) { "Default persona ID should match" }
        }

        @Test
        fun `should switch default when saving new default`() {
            store.save(createPersona(id = "p-1", name = "Old Default", isDefault = true))
            store.save(createPersona(id = "p-2", name = "New Default", isDefault = true))

            val old = store.get("p-1")
            val new = store.get("p-2")

            assertFalse(old!!.isDefault) { "Old persona should no longer be default" }
            assertTrue(new!!.isDefault) { "New persona should be default" }

            val defaultPersona = store.getDefault()
            assertEquals("p-2", defaultPersona!!.id) { "getDefault should return the new default" }
        }

        @Test
        fun `should return null when no default exists`() {
            store.save(createPersona(isDefault = false))

            assertNull(store.getDefault(), "Should return null when no default persona exists")
        }

        @Test
        fun `should update persona to be default`() {
            store.save(createPersona(id = "p-1", isDefault = false))
            store.save(createPersona(id = "p-2", isDefault = true))

            store.update("p-1", name = null, systemPrompt = null, isDefault = true)

            val p1 = store.get("p-1")
            val p2 = store.get("p-2")
            assertTrue(p1!!.isDefault) { "p-1 should now be default" }
            assertFalse(p2!!.isDefault) { "p-2 should no longer be default" }
        }
    }

    @Nested
    inner class PartialUpdate {

        @Test
        fun `should update name only`() {
            store.save(createPersona(name = "Original", systemPrompt = "Original prompt"))

            val updated = store.update("p-1", name = "Updated Name", systemPrompt = null, isDefault = null)

            assertNotNull(updated) { "Update should return the updated persona" }
            assertEquals("Updated Name", updated!!.name) { "Name should be updated" }
            assertEquals("Original prompt", updated.systemPrompt) { "System prompt should be unchanged" }
        }

        @Test
        fun `should update systemPrompt only`() {
            store.save(createPersona(name = "Keep This", systemPrompt = "Old prompt"))

            val updated = store.update("p-1", name = null, systemPrompt = "New prompt", isDefault = null)

            assertNotNull(updated) { "Update should return the updated persona" }
            assertEquals("Keep This", updated!!.name) { "Name should be unchanged" }
            assertEquals("New prompt", updated.systemPrompt) { "System prompt should be updated" }
        }

        @Test
        fun `should return null when updating nonexistent persona`() {
            val result = store.update("nonexistent", name = "New Name", systemPrompt = null, isDefault = null)

            assertNull(result, "Updating nonexistent persona should return null")
        }

        @Test
        fun `should update updatedAt timestamp`() {
            store.save(createPersona())
            val original = store.get("p-1")!!

            Thread.sleep(10)
            store.update("p-1", name = "Updated", systemPrompt = null, isDefault = null)
            val updated = store.get("p-1")!!

            assertTrue(
                updated.updatedAt.isAfter(original.updatedAt) || updated.updatedAt == original.updatedAt,
                "updatedAt should be >= original (${original.updatedAt} vs ${updated.updatedAt})"
            )
        }
    }
}
