package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.prompt.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

/**
 * PromptTemplateController에 대한 테스트.
 *
 * 프롬프트 템플릿 REST API의 동작을 검증합니다.
 */
class PromptTemplateControllerTest {

    private lateinit var store: PromptTemplateStore
    private lateinit var controller: PromptTemplateController

    private val now = Instant.parse("2026-02-08T12:00:00Z")

    @BeforeEach
    fun setup() {
        store = mockk()
        controller = PromptTemplateController(store)
    }

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attrs = mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN
        )
        every { exchange.attributes } returns attrs
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        val attrs = mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER
        )
        every { exchange.attributes } returns attrs
        return exchange
    }

    @Nested
    inner class TemplateEndpoints {

        @Test
        fun `GET은(는) list all templates해야 한다`() = runTest {
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
        fun `GET {id}은(는) return template with versions해야 한다`() = runTest {
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
            val body = response.body!! as TemplateDetailResponse
            assertEquals("customer-support", body.name) { "Name should match" }
            assertEquals(2, body.versions.size) { "Should have 2 versions" }
            assertNotNull(body.activeVersion) { "Should have an active version" }
            assertEquals("v-2", body.activeVersion!!.id) { "Active version should be v-2" }
        }

        @Test
        fun `GET {id}은(는) return 404 for nonexistent template해야 한다`() = runTest {
            every { store.getTemplate("nonexistent") } returns null

            val response = controller.getTemplate("nonexistent")

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }

        @Test
        fun `POST은(는) create template and return 201 for ADMIN해야 한다`() = runTest {
            val slot = slot<PromptTemplate>()
            every { store.saveTemplate(capture(slot)) } answers { slot.captured }

            val request = CreateTemplateRequest(name = "new-template", description = "A new one")
            val response = controller.createTemplate(request, adminExchange())

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should return 201 Created" }
            val body = response.body!! as TemplateResponse
            assertEquals("new-template", body.name) { "Name should match request" }
            assertEquals("A new one", body.description) { "Description should match request" }
            assertTrue(body.id.isNotBlank()) { "ID should be generated (UUID)" }
        }

        @Test
        fun `POST은(는) return 403 for non-ADMIN해야 한다`() = runTest {
            val request = CreateTemplateRequest(name = "new-template", description = "A new one")
            val response = controller.createTemplate(request, userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403 for USER role" }
        }

        @Test
        fun `PUT은(는) update template for ADMIN해야 한다`() = runTest {
            every { store.updateTemplate("t-1", "updated", null) } returns PromptTemplate(
                "t-1", "updated", "original desc", now, now.plusSeconds(10)
            )

            val response = controller.updateTemplate("t-1", UpdateTemplateRequest(name = "updated"), adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            assertEquals("updated", (response.body!! as TemplateResponse).name) { "Name should be updated" }
        }

        @Test
        fun `PUT은(는) return 403 for non-ADMIN해야 한다`() = runTest {
            val response = controller.updateTemplate("t-1", UpdateTemplateRequest(name = "updated"), userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403 for USER role" }
        }

        @Test
        fun `PUT은(는) return 404 for nonexistent template해야 한다`() = runTest {
            every { store.updateTemplate("nonexistent", any(), any()) } returns null

            val response = controller.updateTemplate("nonexistent", UpdateTemplateRequest(name = "x"), adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }

        @Test
        fun `DELETE은(는) return 204 for ADMIN해야 한다`() = runTest {
            every { store.deleteTemplate("t-1") } returns Unit

            val response = controller.deleteTemplate("t-1", adminExchange())

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) { "Should return 204" }
            verify(exactly = 1) { store.deleteTemplate("t-1") }
        }

        @Test
        fun `DELETE은(는) return 403 for non-ADMIN해야 한다`() = runTest {
            val response = controller.deleteTemplate("t-1", userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403 for USER role" }
        }
    }

    @Nested
    inner class VersionEndpoints {

        @Test
        fun `POST versions은(는) create new version and return 201 for ADMIN해야 한다`() = runTest {
            every { store.createVersion("t-1", "prompt content", "initial") } returns PromptVersion(
                "v-1", "t-1", 1, "prompt content", VersionStatus.DRAFT, "initial", now
            )

            val response = controller.createVersion(
                "t-1",
                CreateVersionRequest(content = "prompt content", changeLog = "initial"),
                adminExchange()
            )

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should return 201" }
            val body = response.body!! as VersionResponse
            assertEquals(1, body.version) { "Version number should be 1" }
            assertEquals("DRAFT", body.status) { "Status should be DRAFT" }
            assertEquals("prompt content", body.content) { "Content should match" }
        }

        @Test
        fun `POST versions은(는) return 403 for non-ADMIN해야 한다`() = runTest {
            val response = controller.createVersion(
                "t-1",
                CreateVersionRequest(content = "content"),
                userExchange()
            )

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403 for USER role" }
        }

        @Test
        fun `POST versions은(는) return 404 for nonexistent template해야 한다`() = runTest {
            every { store.createVersion("nonexistent", any(), any()) } returns null

            val response = controller.createVersion(
                "nonexistent",
                CreateVersionRequest(content = "content"),
                adminExchange()
            )

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }

        @Test
        fun `PUT activate은(는) activate version for ADMIN해야 한다`() = runTest {
            every { store.activateVersion("t-1", "v-1") } returns PromptVersion(
                "v-1", "t-1", 1, "content", VersionStatus.ACTIVE, "", now
            )

            val response = controller.activateVersion("t-1", "v-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            assertEquals("ACTIVE", (response.body!! as VersionResponse).status) { "Status should be ACTIVE" }
        }

        @Test
        fun `PUT activate은(는) return 403 for non-ADMIN해야 한다`() = runTest {
            val response = controller.activateVersion("t-1", "v-1", userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403 for USER role" }
        }

        @Test
        fun `PUT activate은(는) return 404 for nonexistent version해야 한다`() = runTest {
            every { store.activateVersion("t-1", "nonexistent") } returns null

            val response = controller.activateVersion("t-1", "nonexistent", adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }

        @Test
        fun `PUT archive은(는) archive version for ADMIN해야 한다`() = runTest {
            every { store.archiveVersion("v-1") } returns PromptVersion(
                "v-1", "t-1", 1, "content", VersionStatus.ARCHIVED, "", now
            )

            val response = controller.archiveVersion("t-1", "v-1", adminExchange())

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            assertEquals("ARCHIVED", (response.body!! as VersionResponse).status) { "Status should be ARCHIVED" }
        }

        @Test
        fun `PUT archive은(는) return 403 for non-ADMIN해야 한다`() = runTest {
            val response = controller.archiveVersion("t-1", "v-1", userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403 for USER role" }
        }

        @Test
        fun `PUT archive은(는) return 404 for nonexistent version해야 한다`() = runTest {
            every { store.archiveVersion("nonexistent") } returns null

            val response = controller.archiveVersion("t-1", "nonexistent", adminExchange())

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404" }
        }
    }
}
