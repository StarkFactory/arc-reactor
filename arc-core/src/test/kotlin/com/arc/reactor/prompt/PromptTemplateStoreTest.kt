package com.arc.reactor.prompt

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * PromptTemplateStore에 대한 테스트.
 *
 * 프롬프트 템플릿 저장소의 CRUD 동작을 검증합니다.
 */
class PromptTemplateStoreTest {

    private lateinit var store: InMemoryPromptTemplateStore

    @BeforeEach
    fun setup() {
        store = InMemoryPromptTemplateStore()
    }

    @Nested
    inner class BasicTemplateCrud {

        @Test
        fun `save and retrieve a template해야 한다`() {
            val template = PromptTemplate(
                id = "t-1",
                name = "customer-support",
                description = "Customer support agent prompt"
            )

            store.saveTemplate(template)
            val retrieved = store.getTemplate("t-1")

            assertNotNull(retrieved) { "Saved template should be retrievable" }
            assertEquals("customer-support", retrieved!!.name) { "Name should match" }
            assertEquals("Customer support agent prompt", retrieved.description) { "Description should match" }
        }

        @Test
        fun `list all templates sorted by createdAt해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "first"))
            store.saveTemplate(PromptTemplate(id = "t-2", name = "second"))

            val templates = store.listTemplates()

            assertEquals(2, templates.size) { "Should have 2 templates" }
            assertEquals("t-1", templates[0].id) { "First template should come first" }
        }

        @Test
        fun `update template name and description해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "original", description = "original desc"))

            val updated = store.updateTemplate("t-1", name = "updated", description = null)

            assertNotNull(updated) { "Update should return the updated template" }
            assertEquals("updated", updated!!.name) { "Name should be updated" }
            assertEquals("original desc", updated.description) { "Description should remain unchanged" }
        }

        @Test
        fun `delete template and all its versions해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "to-delete"))
            store.createVersion("t-1", "prompt content v1")
            store.createVersion("t-1", "prompt content v2")

            store.deleteTemplate("t-1")

            assertNull(store.getTemplate("t-1")) { "Deleted template should not be retrievable" }
            assertTrue(store.listVersions("t-1").isEmpty()) { "Versions should be deleted with template" }
        }

        @Test
        fun `nonexistent template에 대해 return null해야 한다`() {
            assertNull(store.getTemplate("nonexistent")) { "Should return null for unknown ID" }
        }

        @Test
        fun `find template by name해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "unique-name"))

            val found = store.getTemplateByName("unique-name")
            val notFound = store.getTemplateByName("nonexistent")

            assertNotNull(found) { "Should find template by name" }
            assertEquals("t-1", found!!.id) { "Found template should have correct ID" }
            assertNull(notFound) { "Should return null for unknown name" }
        }

        @Test
        fun `delete은(는) be idempotent for nonexistent template해야 한다`() {
            assertDoesNotThrow { store.deleteTemplate("nonexistent") }
        }

        @Test
        fun `updating nonexistent template일 때 return null해야 한다`() {
            val result = store.updateTemplate("nonexistent", name = "new", description = null)
            assertNull(result) { "Update of nonexistent template should return null" }
        }
    }

    @Nested
    inner class VersionManagement {

        @Test
        fun `auto-increment number로 create version해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "test"))

            val v1 = store.createVersion("t-1", "content v1")
            val v2 = store.createVersion("t-1", "content v2")
            val v3 = store.createVersion("t-1", "content v3", "third version")

            assertNotNull(v1) { "First version should be created" }
            assertEquals(1, v1!!.version) { "First version number should be 1" }
            assertEquals(2, v2!!.version) { "Second version number should be 2" }
            assertEquals(3, v3!!.version) { "Third version number should be 3" }
            assertEquals("third version", v3.changeLog) { "Change log should be preserved" }
        }

        @Test
        fun `creating version for nonexistent template일 때 return null해야 한다`() {
            val result = store.createVersion("nonexistent", "content")
            assertNull(result) { "Should return null for nonexistent template" }
        }

        @Test
        fun `new versions은(는) start in DRAFT status해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "test"))
            val version = store.createVersion("t-1", "content")

            assertEquals(VersionStatus.DRAFT, version!!.status) { "New version should be DRAFT" }
        }

        @Test
        fun `a template sorted by version number에 대해 list versions해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "test"))
            store.createVersion("t-1", "v1")
            store.createVersion("t-1", "v2")
            store.createVersion("t-1", "v3")

            val versions = store.listVersions("t-1")

            assertEquals(3, versions.size) { "Should have 3 versions" }
            assertEquals(1, versions[0].version) { "First version should be 1" }
            assertEquals(2, versions[1].version) { "Second version should be 2" }
            assertEquals(3, versions[2].version) { "Third version should be 3" }
        }

        @Test
        fun `activate a version해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "test"))
            val v1 = store.createVersion("t-1", "content v1")!!

            val activated = store.activateVersion("t-1", v1.id)

            assertNotNull(activated) { "Activation should return the version" }
            assertEquals(VersionStatus.ACTIVE, activated!!.status) { "Version should be ACTIVE" }
        }

        @Test
        fun `activating new version일 때 archive previous ACTIVE해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "test"))
            val v1 = store.createVersion("t-1", "content v1")!!
            val v2 = store.createVersion("t-1", "content v2")!!

            store.activateVersion("t-1", v1.id)
            store.activateVersion("t-1", v2.id)

            val v1After = store.getVersion(v1.id)
            val v2After = store.getVersion(v2.id)

            assertEquals(VersionStatus.ARCHIVED, v1After!!.status) { "Previous active should be archived" }
            assertEquals(VersionStatus.ACTIVE, v2After!!.status) { "New version should be active" }
        }

        @Test
        fun `activating version with wrong templateId일 때 return null해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "test"))
            val v1 = store.createVersion("t-1", "content v1")!!

            val result = store.activateVersion("wrong-template", v1.id)
            assertNull(result) { "Should return null for mismatched templateId" }
        }

        @Test
        fun `archive a version해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "test"))
            val v1 = store.createVersion("t-1", "content v1")!!

            val archived = store.archiveVersion(v1.id)

            assertNotNull(archived) { "Archive should return the version" }
            assertEquals(VersionStatus.ARCHIVED, archived!!.status) { "Version should be ARCHIVED" }
        }

        @Test
        fun `template에 대해 return active version해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "test"))
            val v1 = store.createVersion("t-1", "content v1")!!
            store.activateVersion("t-1", v1.id)

            val active = store.getActiveVersion("t-1")

            assertNotNull(active) { "Should return the active version" }
            assertEquals(v1.id, active!!.id) { "Active version should match" }
            assertEquals(VersionStatus.ACTIVE, active.status) { "Status should be ACTIVE" }
        }

        @Test
        fun `none exists일 때 return null active version해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "test"))
            store.createVersion("t-1", "content v1") // DRAFT, not ACTIVE

            val active = store.getActiveVersion("t-1")
            assertNull(active) { "Should return null when no version is active" }
        }

        @Test
        fun `archiving nonexistent version일 때 return null해야 한다`() {
            assertNull(store.archiveVersion("nonexistent")) { "Should return null for unknown version" }
        }

        @Test
        fun `activating nonexistent version일 때 return null해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "test"))
            assertNull(store.activateVersion("t-1", "nonexistent")) { "Should return null for unknown version" }
        }
    }

    @Nested
    inner class VersionStatusFlow {

        @Test
        fun `transition DRAFT to ACTIVE to ARCHIVED해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "test"))
            val v1 = store.createVersion("t-1", "content v1")!!

            // DRAFT → ACTIVE
            assertEquals(VersionStatus.DRAFT, v1.status) { "Initial status should be DRAFT" }
            val activated = store.activateVersion("t-1", v1.id)!!
            assertEquals(VersionStatus.ACTIVE, activated.status) { "After activation should be ACTIVE" }

            // ACTIVE → ARCHIVED (by activating another version)
            val v2 = store.createVersion("t-1", "content v2")!!
            store.activateVersion("t-1", v2.id)

            val v1Final = store.getVersion(v1.id)!!
            val v2Final = store.getVersion(v2.id)!!

            assertEquals(VersionStatus.ARCHIVED, v1Final.status) { "v1 should be ARCHIVED after v2 activation" }
            assertEquals(VersionStatus.ACTIVE, v2Final.status) { "v2 should be ACTIVE" }
        }

        @Test
        fun `support direct DRAFT to ARCHIVED via archiveVersion해야 한다`() {
            store.saveTemplate(PromptTemplate(id = "t-1", name = "test"))
            val v1 = store.createVersion("t-1", "content v1")!!

            val archived = store.archiveVersion(v1.id)!!
            assertEquals(VersionStatus.ARCHIVED, archived.status) { "DRAFT should be directly archivable" }
        }
    }
}
