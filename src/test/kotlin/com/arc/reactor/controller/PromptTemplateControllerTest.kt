package com.arc.reactor.controller

import com.arc.reactor.prompt.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Instant

class PromptTemplateControllerTest {

    private lateinit var store: PromptTemplateStore
    private lateinit var controller: PromptTemplateController

    private val now = Instant.parse("2026-02-08T12:00:00Z")

    @BeforeEach
    fun setup() {
        store = mockk()
        controller = PromptTemplateController(store)
    }

    @Nested
    inner class TemplateEndpoints {

        @Test
        fun `GET should list all templates`() = runTest {
            every { store.listTemplates() } returns listOf(
                PromptTemplate("t-1", "customer-support", "CS agent", now, now),
                PromptTemplate("t-2", "code-reviewer", "Code review", now, now)
            )

            val result = controller.listTemplates()

            assertEquals(2, result.size) { "Should return 2 templates" }
            assertEquals("customer-support", result[0].name) { "First template name should match" }
            assertEquals(now.toEpochMilli(), result[0].createdAt) { "Timestamp should be epoch millis" }
        }

        @Test
        fun `GET {id} should return template with versions`() = runTest {
            val template = PromptTemplate("t-1", "customer-support", "CS agent", now, now)
            val activeVersion = PromptVersion("v-2", "t-1", 2, "You are a CS agent v2.", VersionStatus.ACTIVE, "improved", now)
            val versions = listOf(
                PromptVersion("v-1", "t-1", 1, "You are a CS agent.", VersionStatus.ARCHIVED, "initial", now),
                activeVersion
            )
            every { store.getTemplate("t-1") } returns template
            every { store.listVersions("t-1") } returns versions

            val response = controller.getTemplate("t-1")

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            val body = response.body!!
            assertEquals("customer-support", body.name) { "Name should match" }
            assertEquals(2, body.versions.size) { "Should have 2 versions" }
            assertNotNull(body.activeVersion) { "Should have an active version" }
            assertEquals("v-2", body.activeVersion!!.id) { "Active version should be v-2" }
        }

        @Test
        fun `GET {id} should return 404 for nonexistent template`() = runTest {
            every { store.getTemplate("nonexistent") } returns null

            val response = controller.getTemplate("nonexistent")

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }

        @Test
        fun `POST should create template and return 201`() = runTest {
            val slot = slot<PromptTemplate>()
            every { store.saveTemplate(capture(slot)) } answers { slot.captured }

            val request = CreateTemplateRequest(name = "new-template", description = "A new one")
            val response = controller.createTemplate(request)

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should return 201 Created" }
            val body = response.body!!
            assertEquals("new-template", body.name) { "Name should match request" }
            assertEquals("A new one", body.description) { "Description should match request" }
            assertTrue(body.id.isNotBlank()) { "ID should be generated (UUID)" }
        }

        @Test
        fun `PUT should update template`() = runTest {
            every { store.updateTemplate("t-1", "updated", null) } returns PromptTemplate(
                "t-1", "updated", "original desc", now, now.plusSeconds(10)
            )

            val response = controller.updateTemplate("t-1", UpdateTemplateRequest(name = "updated"))

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            assertEquals("updated", response.body!!.name) { "Name should be updated" }
        }

        @Test
        fun `PUT should return 404 for nonexistent template`() = runTest {
            every { store.updateTemplate("nonexistent", any(), any()) } returns null

            val response = controller.updateTemplate("nonexistent", UpdateTemplateRequest(name = "x"))

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }

        @Test
        fun `DELETE should return 204`() = runTest {
            every { store.deleteTemplate("t-1") } returns Unit

            val response = controller.deleteTemplate("t-1")

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) { "Should return 204" }
            verify(exactly = 1) { store.deleteTemplate("t-1") }
        }
    }

    @Nested
    inner class VersionEndpoints {

        @Test
        fun `POST versions should create new version and return 201`() = runTest {
            every { store.createVersion("t-1", "prompt content", "initial") } returns PromptVersion(
                "v-1", "t-1", 1, "prompt content", VersionStatus.DRAFT, "initial", now
            )

            val response = controller.createVersion(
                "t-1",
                CreateVersionRequest(content = "prompt content", changeLog = "initial")
            )

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should return 201" }
            val body = response.body!!
            assertEquals(1, body.version) { "Version number should be 1" }
            assertEquals("DRAFT", body.status) { "Status should be DRAFT" }
            assertEquals("prompt content", body.content) { "Content should match" }
        }

        @Test
        fun `POST versions should return 404 for nonexistent template`() = runTest {
            every { store.createVersion("nonexistent", any(), any()) } returns null

            val response = controller.createVersion(
                "nonexistent",
                CreateVersionRequest(content = "content")
            )

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }

        @Test
        fun `PUT activate should activate version`() = runTest {
            every { store.activateVersion("t-1", "v-1") } returns PromptVersion(
                "v-1", "t-1", 1, "content", VersionStatus.ACTIVE, "", now
            )

            val response = controller.activateVersion("t-1", "v-1")

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            assertEquals("ACTIVE", response.body!!.status) { "Status should be ACTIVE" }
        }

        @Test
        fun `PUT activate should return 404 for nonexistent version`() = runTest {
            every { store.activateVersion("t-1", "nonexistent") } returns null

            val response = controller.activateVersion("t-1", "nonexistent")

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }

        @Test
        fun `PUT archive should archive version`() = runTest {
            every { store.archiveVersion("v-1") } returns PromptVersion(
                "v-1", "t-1", 1, "content", VersionStatus.ARCHIVED, "", now
            )

            val response = controller.archiveVersion("t-1", "v-1")

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            assertEquals("ARCHIVED", response.body!!.status) { "Status should be ARCHIVED" }
        }

        @Test
        fun `PUT archive should return 404 for nonexistent version`() = runTest {
            every { store.archiveVersion("nonexistent") } returns null

            val response = controller.archiveVersion("t-1", "nonexistent")

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }
    }
}
