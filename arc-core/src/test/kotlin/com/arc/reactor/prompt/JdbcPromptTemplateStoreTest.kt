package com.arc.reactor.prompt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType

/**
 * JdbcPromptTemplateStore에 대한 테스트.
 *
 * JDBC 기반 프롬프트 템플릿 저장소의 동작을 검증합니다.
 */
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
        fun `save and get template해야 한다`() {
            val template = createTemplate()
            store.saveTemplate(template)

            val found = store.getTemplate("tmpl-1")

            assertNotNull(found) { "Saved template should be retrievable" }
            assertEquals("tmpl-1", found!!.id) { "ID should match" }
            assertEquals("Test Template", found.name) { "Name should match" }
            assertEquals("A test template", found.description) { "Description should match" }
        }

        @Test
        fun `list templates ordered by createdAt해야 한다`() {
            store.saveTemplate(createTemplate(id = "t1", name = "First"))
            Thread.sleep(10)
            store.saveTemplate(createTemplate(id = "t2", name = "Second"))

            val list = store.listTemplates()

            assertEquals(2, list.size) { "Should have 2 templates" }
            assertEquals("First", list[0].name) { "First created should be first" }
            assertEquals("Second", list[1].name) { "Second created should be second" }
        }

        @Test
        fun `get template by name해야 한다`() {
            store.saveTemplate(createTemplate(name = "unique-name"))

            val found = store.getTemplateByName("unique-name")

            assertNotNull(found) { "Should find template by name" }
            assertEquals("tmpl-1", found!!.id) { "ID should match" }
        }

        @Test
        fun `update template metadata해야 한다`() {
            store.saveTemplate(createTemplate())

            val updated = store.updateTemplate("tmpl-1", "Updated", "New desc")

            assertNotNull(updated) { "Update should return updated template" }
            assertEquals("Updated", updated!!.name) { "Name should be updated" }
            assertEquals("New desc", updated.description) { "Description should be updated" }

            // persistence 확인
            val reloaded = store.getTemplate("tmpl-1")!!
            assertEquals("Updated", reloaded.name) { "Updated name should persist" }
        }

        @Test
        fun `delete template해야 한다`() {
            store.saveTemplate(createTemplate())

            store.deleteTemplate("tmpl-1")

            assertNull(store.getTemplate("tmpl-1"), "Deleted template should be null")
        }

        @Test
        fun `unknown template에 대해 return null해야 한다`() {
            assertNull(store.getTemplate("nonexistent"), "Unknown template should return null")
        }

        @Test
        fun `updating nonexistent template일 때 return null해야 한다`() {
            assertNull(
                store.updateTemplate("nonexistent", "X", null),
                "Updating nonexistent template should return null"
            )
        }
    }

    @Nested
    inner class VersionLifecycle {

        @Test
        fun `auto-incremented number로 create version해야 한다`() {
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
        fun `create version in DRAFT status해야 한다`() {
            store.saveTemplate(createTemplate())

            val version = store.createVersion("tmpl-1", "Content", "Log")

            assertEquals(VersionStatus.DRAFT, version!!.status) { "New version should be DRAFT" }
        }

        @Test
        fun `activate version해야 한다`() {
            store.saveTemplate(createTemplate())
            val v1 = store.createVersion("tmpl-1", "Content v1", "")!!

            val activated = store.activateVersion("tmpl-1", v1.id)

            assertNotNull(activated) { "Activated version should be returned" }
            assertEquals(VersionStatus.ACTIVE, activated!!.status) { "Status should be ACTIVE" }
        }

        @Test
        fun `get active version해야 한다`() {
            store.saveTemplate(createTemplate())
            val v1 = store.createVersion("tmpl-1", "Content v1", "")!!
            store.activateVersion("tmpl-1", v1.id)

            val active = store.getActiveVersion("tmpl-1")

            assertNotNull(active) { "Active version should be retrievable" }
            assertEquals(v1.id, active!!.id) { "Active version ID should match" }
            assertEquals("Content v1", active.content) { "Active version content should match" }
        }

        @Test
        fun `archive version해야 한다`() {
            store.saveTemplate(createTemplate())
            val v1 = store.createVersion("tmpl-1", "Content", "")!!

            val archived = store.archiveVersion(v1.id)

            assertNotNull(archived) { "Archived version should be returned" }
            assertEquals(VersionStatus.ARCHIVED, archived!!.status) { "Status should be ARCHIVED" }
        }

        @Test
        fun `list versions ordered by version number해야 한다`() {
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
        fun `activating new version일 때 archive previous active해야 한다`() {
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
        fun `have at most one active version해야 한다`() {
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
        fun `template is deleted일 때 delete all versions해야 한다`() {
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
        fun `creating version for nonexistent template일 때 return null해야 한다`() {
            val result = store.createVersion("nonexistent", "content", "log")

            assertNull(result, "Creating version for nonexistent template should return null")
        }

        @Test
        fun `activating version with wrong templateId일 때 return null해야 한다`() {
            store.saveTemplate(createTemplate(id = "tmpl-1", name = "Template 1"))
            store.saveTemplate(createTemplate(id = "tmpl-2", name = "Template 2"))
            val v1 = store.createVersion("tmpl-1", "content", "")!!

            val result = store.activateVersion("tmpl-2", v1.id)

            assertNull(result, "Activating version with wrong templateId should return null")
        }

        @Test
        fun `archiving nonexistent version일 때 return null해야 한다`() {
            assertNull(store.archiveVersion("nonexistent"), "Archiving nonexistent version should return null")
        }

        @Test
        fun `no active version일 때 return null for getActiveVersion해야 한다`() {
            store.saveTemplate(createTemplate())
            store.createVersion("tmpl-1", "draft content", "")

            assertNull(
                store.getActiveVersion("tmpl-1"),
                "Should return null when all versions are DRAFT"
            )
        }

        @Test
        fun `preserve version content and changeLog해야 한다`() {
            store.saveTemplate(createTemplate())
            val created = store.createVersion("tmpl-1", "Long content here", "Fixed typo in prompt")!!

            val retrieved = store.getVersion(created.id)!!

            assertEquals("Long content here", retrieved.content) { "Content should be preserved" }
            assertEquals("Fixed typo in prompt", retrieved.changeLog) { "ChangeLog should be preserved" }
        }
    }
}
