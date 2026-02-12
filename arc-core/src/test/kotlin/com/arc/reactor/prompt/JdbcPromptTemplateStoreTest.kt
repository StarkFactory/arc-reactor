package com.arc.reactor.prompt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType

class JdbcPromptTemplateStoreTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcPromptTemplateStore

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()

        jdbcTemplate = JdbcTemplate(dataSource)

        // V5 DDL: prompt_templates + prompt_versions
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS prompt_templates (
                id          VARCHAR(36)  PRIMARY KEY,
                name        VARCHAR(255) NOT NULL UNIQUE,
                description TEXT         NOT NULL DEFAULT '',
                created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS prompt_versions (
                id          VARCHAR(36)  PRIMARY KEY,
                template_id VARCHAR(36)  NOT NULL REFERENCES prompt_templates(id) ON DELETE CASCADE,
                version     INT          NOT NULL,
                content     TEXT         NOT NULL,
                status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
                change_log  TEXT         NOT NULL DEFAULT '',
                created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(template_id, version)
            )
        """.trimIndent())

        store = JdbcPromptTemplateStore(jdbcTemplate)
    }

    private fun createTemplate(
        id: String = "tmpl-1",
        name: String = "Test Template",
        description: String = "A test template"
    ) = PromptTemplate(id = id, name = name, description = description)

    @Nested
    inner class TemplateCrud {

        @Test
        fun `should save and get template`() {
            val template = createTemplate()
            store.saveTemplate(template)

            val found = store.getTemplate("tmpl-1")

            assertNotNull(found) { "Saved template should be retrievable" }
            assertEquals("tmpl-1", found!!.id) { "ID should match" }
            assertEquals("Test Template", found.name) { "Name should match" }
            assertEquals("A test template", found.description) { "Description should match" }
        }

        @Test
        fun `should list templates ordered by createdAt`() {
            store.saveTemplate(createTemplate(id = "t1", name = "First"))
            Thread.sleep(10)
            store.saveTemplate(createTemplate(id = "t2", name = "Second"))

            val list = store.listTemplates()

            assertEquals(2, list.size) { "Should have 2 templates" }
            assertEquals("First", list[0].name) { "First created should be first" }
            assertEquals("Second", list[1].name) { "Second created should be second" }
        }

        @Test
        fun `should get template by name`() {
            store.saveTemplate(createTemplate(name = "unique-name"))

            val found = store.getTemplateByName("unique-name")

            assertNotNull(found) { "Should find template by name" }
            assertEquals("tmpl-1", found!!.id) { "ID should match" }
        }

        @Test
        fun `should update template metadata`() {
            store.saveTemplate(createTemplate())

            val updated = store.updateTemplate("tmpl-1", "Updated", "New desc")

            assertNotNull(updated) { "Update should return updated template" }
            assertEquals("Updated", updated!!.name) { "Name should be updated" }
            assertEquals("New desc", updated.description) { "Description should be updated" }

            // Verify persistence
            val reloaded = store.getTemplate("tmpl-1")!!
            assertEquals("Updated", reloaded.name) { "Updated name should persist" }
        }

        @Test
        fun `should delete template`() {
            store.saveTemplate(createTemplate())

            store.deleteTemplate("tmpl-1")

            assertNull(store.getTemplate("tmpl-1"), "Deleted template should be null")
        }

        @Test
        fun `should return null for unknown template`() {
            assertNull(store.getTemplate("nonexistent"), "Unknown template should return null")
        }

        @Test
        fun `should return null when updating nonexistent template`() {
            assertNull(
                store.updateTemplate("nonexistent", "X", null),
                "Updating nonexistent template should return null"
            )
        }
    }

    @Nested
    inner class VersionLifecycle {

        @Test
        fun `should create version with auto-incremented number`() {
            store.saveTemplate(createTemplate())

            val v1 = store.createVersion("tmpl-1", "Content v1", "Initial")
            val v2 = store.createVersion("tmpl-1", "Content v2", "Update")
            val v3 = store.createVersion("tmpl-1", "Content v3", "Another")

            assertNotNull(v1) { "v1 should be created" }
            assertNotNull(v2) { "v2 should be created" }
            assertNotNull(v3) { "v3 should be created" }
            assertEquals(1, v1!!.version) { "First version should be 1" }
            assertEquals(2, v2!!.version) { "Second version should be 2" }
            assertEquals(3, v3!!.version) { "Third version should be 3" }
        }

        @Test
        fun `should create version in DRAFT status`() {
            store.saveTemplate(createTemplate())

            val version = store.createVersion("tmpl-1", "Content", "Log")

            assertEquals(VersionStatus.DRAFT, version!!.status) { "New version should be DRAFT" }
        }

        @Test
        fun `should activate version`() {
            store.saveTemplate(createTemplate())
            val v1 = store.createVersion("tmpl-1", "Content v1", "")!!

            val activated = store.activateVersion("tmpl-1", v1.id)

            assertNotNull(activated) { "Activated version should be returned" }
            assertEquals(VersionStatus.ACTIVE, activated!!.status) { "Status should be ACTIVE" }
        }

        @Test
        fun `should get active version`() {
            store.saveTemplate(createTemplate())
            val v1 = store.createVersion("tmpl-1", "Content v1", "")!!
            store.activateVersion("tmpl-1", v1.id)

            val active = store.getActiveVersion("tmpl-1")

            assertNotNull(active) { "Active version should be retrievable" }
            assertEquals(v1.id, active!!.id) { "Active version ID should match" }
            assertEquals("Content v1", active.content) { "Active version content should match" }
        }

        @Test
        fun `should archive version`() {
            store.saveTemplate(createTemplate())
            val v1 = store.createVersion("tmpl-1", "Content", "")!!

            val archived = store.archiveVersion(v1.id)

            assertNotNull(archived) { "Archived version should be returned" }
            assertEquals(VersionStatus.ARCHIVED, archived!!.status) { "Status should be ARCHIVED" }
        }

        @Test
        fun `should list versions ordered by version number`() {
            store.saveTemplate(createTemplate())
            store.createVersion("tmpl-1", "v1 content", "")
            store.createVersion("tmpl-1", "v2 content", "")
            store.createVersion("tmpl-1", "v3 content", "")

            val versions = store.listVersions("tmpl-1")

            assertEquals(3, versions.size) { "Should have 3 versions" }
            assertEquals(1, versions[0].version) { "First should be v1" }
            assertEquals(2, versions[1].version) { "Second should be v2" }
            assertEquals(3, versions[2].version) { "Third should be v3" }
        }
    }

    @Nested
    inner class SingleActiveEnforcement {

        @Test
        fun `should archive previous active when activating new version`() {
            store.saveTemplate(createTemplate())
            val v1 = store.createVersion("tmpl-1", "v1", "")!!
            val v2 = store.createVersion("tmpl-1", "v2", "")!!

            store.activateVersion("tmpl-1", v1.id)
            store.activateVersion("tmpl-1", v2.id)

            val v1After = store.getVersion(v1.id)!!
            val v2After = store.getVersion(v2.id)!!

            assertEquals(VersionStatus.ARCHIVED, v1After.status) {
                "Previously active v1 should be ARCHIVED after v2 activation"
            }
            assertEquals(VersionStatus.ACTIVE, v2After.status) {
                "Newly activated v2 should be ACTIVE"
            }
        }

        @Test
        fun `should have at most one active version`() {
            store.saveTemplate(createTemplate())
            val v1 = store.createVersion("tmpl-1", "v1", "")!!
            val v2 = store.createVersion("tmpl-1", "v2", "")!!
            val v3 = store.createVersion("tmpl-1", "v3", "")!!

            store.activateVersion("tmpl-1", v1.id)
            store.activateVersion("tmpl-1", v2.id)
            store.activateVersion("tmpl-1", v3.id)

            val versions = store.listVersions("tmpl-1")
            val activeCount = versions.count { it.status == VersionStatus.ACTIVE }

            assertEquals(1, activeCount) { "Should have exactly 1 active version, got $activeCount" }
            assertEquals(VersionStatus.ACTIVE, versions.first { it.id == v3.id }.status) {
                "v3 (latest activated) should be ACTIVE"
            }
        }
    }

    @Nested
    inner class CascadeDelete {

        @Test
        fun `should delete all versions when template is deleted`() {
            store.saveTemplate(createTemplate())
            val v1 = store.createVersion("tmpl-1", "v1", "")!!
            val v2 = store.createVersion("tmpl-1", "v2", "")!!

            store.deleteTemplate("tmpl-1")

            assertNull(store.getVersion(v1.id), "v1 should be cascade-deleted")
            assertNull(store.getVersion(v2.id), "v2 should be cascade-deleted")
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `should return null when creating version for nonexistent template`() {
            val result = store.createVersion("nonexistent", "content", "log")

            assertNull(result, "Creating version for nonexistent template should return null")
        }

        @Test
        fun `should return null when activating version with wrong templateId`() {
            store.saveTemplate(createTemplate(id = "tmpl-1", name = "Template 1"))
            store.saveTemplate(createTemplate(id = "tmpl-2", name = "Template 2"))
            val v1 = store.createVersion("tmpl-1", "content", "")!!

            val result = store.activateVersion("tmpl-2", v1.id)

            assertNull(result, "Activating version with wrong templateId should return null")
        }

        @Test
        fun `should return null when archiving nonexistent version`() {
            assertNull(store.archiveVersion("nonexistent"), "Archiving nonexistent version should return null")
        }

        @Test
        fun `should return null for getActiveVersion when no active version`() {
            store.saveTemplate(createTemplate())
            store.createVersion("tmpl-1", "draft content", "")

            assertNull(
                store.getActiveVersion("tmpl-1"),
                "Should return null when all versions are DRAFT"
            )
        }

        @Test
        fun `should preserve version content and changeLog`() {
            store.saveTemplate(createTemplate())
            val created = store.createVersion("tmpl-1", "Long content here", "Fixed typo in prompt")!!

            val retrieved = store.getVersion(created.id)!!

            assertEquals("Long content here", retrieved.content) { "Content should be preserved" }
            assertEquals("Fixed typo in prompt", retrieved.changeLog) { "ChangeLog should be preserved" }
        }
    }
}
