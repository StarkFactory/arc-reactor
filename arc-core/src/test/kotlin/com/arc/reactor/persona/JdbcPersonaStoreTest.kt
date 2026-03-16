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

/**
 * JdbcPersonaStore에 대한 테스트.
 *
 * JDBC 기반 페르소나 저장소의 동작을 검증합니다.
 */
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

        // V2 + V29 DDL (without seed INSERT)
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS personas (
                id                 VARCHAR(36)   PRIMARY KEY,
                name               VARCHAR(200)  NOT NULL,
                system_prompt      TEXT          NOT NULL,
                is_default         BOOLEAN       NOT NULL DEFAULT FALSE,
                description        TEXT,
                response_guideline TEXT,
                welcome_message    TEXT,
                icon               VARCHAR(20),
                is_active          BOOLEAN       NOT NULL DEFAULT TRUE,
                prompt_template_id VARCHAR(36),
                created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
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
        fun `save and get persona해야 한다`() {
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
        fun `list personas ordered by createdAt해야 한다`() {
            store.save(createPersona(id = "p-1", name = "First"))
            Thread.sleep(10)
            store.save(createPersona(id = "p-2", name = "Second"))

            val list = store.list()

            assertEquals(2, list.size) { "Should have 2 personas" }
            assertEquals("First", list[0].name) { "First created should be first in list" }
            assertEquals("Second", list[1].name) { "Second created should be second in list" }
        }

        @Test
        fun `delete persona해야 한다`() {
            store.save(createPersona())
            assertNotNull(store.get("p-1")) { "Should exist before delete" }

            store.delete("p-1")

            assertNull(store.get("p-1"), "Should be null after delete")
        }

        @Test
        fun `unknown persona에 대해 return null해야 한다`() {
            assertNull(store.get("nonexistent"), "Unknown persona should return null")
        }
    }

    @Nested
    inner class DefaultManagement {

        @Test
        fun `save persona as default해야 한다`() {
            store.save(createPersona(isDefault = true))

            val found = store.get("p-1")
            assertTrue(found!!.isDefault) { "Saved persona should be default" }

            val defaultPersona = store.getDefault()
            assertNotNull(defaultPersona) { "getDefault should return the default persona" }
            assertEquals("p-1", defaultPersona!!.id) { "Default persona ID should match" }
        }

        @Test
        fun `saving new default일 때 switch default해야 한다`() {
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
        fun `no default exists일 때 return null해야 한다`() {
            store.save(createPersona(isDefault = false))

            assertNull(store.getDefault(), "Should return null when no default persona exists")
        }

        @Test
        fun `update persona to be default해야 한다`() {
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
        fun `update name only해야 한다`() {
            store.save(createPersona(name = "Original", systemPrompt = "Original prompt"))

            val updated = store.update("p-1", name = "Updated Name", systemPrompt = null, isDefault = null)

            assertNotNull(updated) { "Update should return the updated persona" }
            assertEquals("Updated Name", updated!!.name) { "Name should be updated" }
            assertEquals("Original prompt", updated.systemPrompt) { "System prompt should be unchanged" }
        }

        @Test
        fun `update systemPrompt only해야 한다`() {
            store.save(createPersona(name = "Keep This", systemPrompt = "Old prompt"))

            val updated = store.update("p-1", name = null, systemPrompt = "New prompt", isDefault = null)

            assertNotNull(updated) { "Update should return the updated persona" }
            assertEquals("Keep This", updated!!.name) { "Name should be unchanged" }
            assertEquals("New prompt", updated.systemPrompt) { "System prompt should be updated" }
        }

        @Test
        fun `updating nonexistent persona일 때 return null해야 한다`() {
            val result = store.update("nonexistent", name = "New Name", systemPrompt = null, isDefault = null)

            assertNull(result, "Updating nonexistent persona should return null")
        }

        @Test
        fun `update updatedAt timestamp해야 한다`() {
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

    @Nested
    inner class ExtendedFields {

        @Test
        fun `save and retrieve all extended fields via JDBC해야 한다`() {
            val persona = Persona(
                id = "ext-1",
                name = "Expert",
                systemPrompt = "You are an expert.",
                description = "A domain expert persona",
                responseGuideline = "Always respond in bullet points.",
                welcomeMessage = "Hello! How can I help?",
                icon = "🧑‍💻",
                isActive = true,
                promptTemplateId = "template-expert"
            )

            store.save(persona)
            val retrieved = store.get("ext-1")

            assertNotNull(retrieved) { "Persona with extended fields should be retrievable" }
            assertEquals("A domain expert persona", retrieved!!.description) { "Description should match" }
            assertEquals("Always respond in bullet points.", retrieved.responseGuideline) {
                "Response guideline should match"
            }
            assertEquals("Hello! How can I help?", retrieved.welcomeMessage) { "Welcome message should match" }
            assertEquals("🧑‍💻", retrieved.icon) { "Icon should match" }
            assertTrue(retrieved.isActive) { "isActive should be true" }
            assertEquals("template-expert", retrieved.promptTemplateId) { "Linked prompt template should match" }
        }

        @Test
        fun `minimal persona에 대해 default isActive to true해야 한다`() {
            store.save(createPersona())
            val retrieved = store.get("p-1")

            assertNotNull(retrieved) { "Minimal persona should be retrievable" }
            assertNull(retrieved!!.description) { "Description should be null" }
            assertNull(retrieved.responseGuideline) { "Response guideline should be null" }
            assertNull(retrieved.welcomeMessage) { "Welcome message should be null" }
            assertNull(retrieved.icon) { "Icon should be null" }
            assertTrue(retrieved.isActive) { "isActive should default to true" }
        }

        @Test
        fun `update extended fields via JDBC해야 한다`() {
            store.save(Persona(
                id = "upd-ext",
                name = "Original",
                systemPrompt = "prompt",
                description = "Old desc"
            ))

            val updated = store.update(
                "upd-ext",
                description = "New desc",
                responseGuideline = "New guideline",
                icon = "🎯",
                promptTemplateId = "template-b"
            )

            assertNotNull(updated) { "Update should return the updated persona" }
            assertEquals("New desc", updated!!.description) { "Description should be updated" }
            assertEquals("New guideline", updated.responseGuideline) { "Guideline should be updated" }
            assertEquals("🎯", updated.icon) { "Icon should be updated" }
            assertEquals("template-b", updated.promptTemplateId) { "Linked prompt template should be updated" }
            assertEquals("Original", updated.name) { "Name should remain unchanged" }

            // via fresh read from DB 확인
            val fromDb = store.get("upd-ext")!!
            assertEquals("New desc", fromDb.description) { "DB should have updated description" }
            assertEquals("New guideline", fromDb.responseGuideline) { "DB should have updated guideline" }
            assertEquals("🎯", fromDb.icon) { "DB should have updated icon" }
            assertEquals("template-b", fromDb.promptTemplateId) { "DB should have updated linked prompt template" }
        }

        @Test
        fun `clear linked prompt template via JDBC해야 한다`() {
            store.save(Persona(
                id = "clear-link",
                name = "Linked",
                systemPrompt = "prompt",
                promptTemplateId = "template-a"
            ))

            val updated = store.update("clear-link", promptTemplateId = "")

            assertNotNull(updated) { "Update should return the updated persona" }
            assertNull(updated!!.promptTemplateId) { "Linked prompt template should be cleared" }
        }

        @Test
        fun `deactivate persona via JDBC해야 한다`() {
            store.save(Persona(
                id = "deactivate",
                name = "Active",
                systemPrompt = "prompt",
                isActive = true
            ))

            store.update("deactivate", isActive = false)

            val fromDb = store.get("deactivate")!!
            assertFalse(fromDb.isActive) { "isActive should be false after deactivation" }
        }

        @Test
        fun `list extended fields correctly해야 한다`() {
            store.save(Persona(
                id = "list-ext",
                name = "Listed",
                systemPrompt = "prompt",
                description = "A listed persona",
                icon = "📋",
                isActive = false
            ))

            val list = store.list()
            val found = list.first { it.id == "list-ext" }

            assertEquals("A listed persona", found.description) { "Listed persona description should match" }
            assertEquals("📋", found.icon) { "Listed persona icon should match" }
            assertFalse(found.isActive) { "Listed persona isActive should be false" }
        }
    }
}
