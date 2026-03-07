package com.arc.reactor.controller

import com.arc.reactor.auth.JwtAuthWebFilter
import com.arc.reactor.auth.UserRole
import com.arc.reactor.persona.Persona
import com.arc.reactor.persona.PersonaStore
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

class PersonaControllerTest {

    private lateinit var personaStore: PersonaStore
    private lateinit var controller: PersonaController

    @BeforeEach
    fun setup() {
        personaStore = mockk()
        controller = PersonaController(personaStore)
    }

    private fun adminExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.ADMIN
        )
        return exchange
    }

    private fun userExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>(
            JwtAuthWebFilter.USER_ROLE_ATTRIBUTE to UserRole.USER
        )
        return exchange
    }

    private fun noAuthExchange(): ServerWebExchange {
        val exchange = mockk<ServerWebExchange>()
        every { exchange.attributes } returns mutableMapOf<String, Any>()
        return exchange
    }

    @Nested
    inner class ListPersonas {

        @Test
        fun `should return empty list when no personas exist`() = runTest {
            every { personaStore.list() } returns emptyList()

            val result = controller.listPersonas(activeOnly = false)

            assertTrue(result.isEmpty()) { "Expected empty list, got ${result.size} personas" }
        }

        @Test
        fun `should return default persona in list`() = runTest {
            val now = Instant.parse("2026-02-08T12:00:00Z")
            every { personaStore.list() } returns listOf(
                Persona("default", "Default Assistant", "You are helpful.", true, createdAt = now, updatedAt = now)
            )

            val result = controller.listPersonas(activeOnly = false)

            assertEquals(1, result.size) { "Should have 1 persona" }
            assertEquals("default", result[0].id) { "ID should be 'default'" }
            assertTrue(result[0].isDefault) { "Should be marked as default" }
        }

        @Test
        fun `should return multiple personas with correct fields`() = runTest {
            val now = Instant.parse("2026-02-08T12:00:00Z")
            every { personaStore.list() } returns listOf(
                Persona("default", "Default", "prompt-1", true, createdAt = now, updatedAt = now),
                Persona("custom-1", "Python Expert", "You are a Python expert.", false, createdAt = now, updatedAt = now)
            )

            val result = controller.listPersonas(activeOnly = false)

            assertEquals(2, result.size) { "Should have 2 personas" }
            assertEquals("Python Expert", result[1].name) { "Second persona name should match" }
            assertEquals(now.toEpochMilli(), result[0].createdAt) { "Timestamp should be epoch millis" }
        }
    }

    @Nested
    inner class GetPersona {

        @Test
        fun `should return 404 when persona does not exist`() = runTest {
            every { personaStore.get("nonexistent") } returns null

            val response = controller.getPersona("nonexistent")

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404 for missing persona" }
        }

        @Test
        fun `should return persona with correct fields`() = runTest {
            val now = Instant.parse("2026-02-08T12:00:00Z")
            every { personaStore.get("p-1") } returns Persona(
                "p-1", "Customer Agent", "You handle customer inquiries.", false, createdAt = now, updatedAt = now
            )

            val response = controller.getPersona("p-1")

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            val body = response.body!!
            assertEquals("p-1", body.id) { "ID should match" }
            assertEquals("Customer Agent", body.name) { "Name should match" }
            assertEquals("You handle customer inquiries.", body.systemPrompt) { "System prompt should match" }
            assertFalse(body.isDefault) { "Should not be default" }
            assertEquals(now.toEpochMilli(), body.createdAt) { "CreatedAt should be epoch millis" }
            assertEquals(now.toEpochMilli(), body.updatedAt) { "UpdatedAt should be epoch millis" }
        }

        @Test
        fun `should return extended fields in response`() = runTest {
            val now = Instant.parse("2026-02-08T12:00:00Z")
            every { personaStore.get("ext-1") } returns Persona(
                id = "ext-1",
                name = "Expert",
                systemPrompt = "prompt",
                description = "A domain expert",
                responseGuideline = "Use bullet points.",
                welcomeMessage = "Hello!",
                promptTemplateId = "template-expert",
                icon = "🧑‍💻",
                isActive = false,
                createdAt = now,
                updatedAt = now
            )

            val response = controller.getPersona("ext-1")

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            val body = response.body!!
            assertEquals("A domain expert", body.description) { "Description should match" }
            assertEquals("Use bullet points.", body.responseGuideline) { "Guideline should match" }
            assertEquals("Hello!", body.welcomeMessage) { "Welcome message should match" }
            assertEquals("template-expert", body.promptTemplateId) { "Linked prompt template should match" }
            assertEquals("🧑‍💻", body.icon) { "Icon should match" }
            assertFalse(body.isActive) { "isActive should be false" }
        }
    }

    @Nested
    inner class CreatePersona {

        @Test
        fun `should return 201 with created persona for admin`() = runTest {
            val slot = slot<Persona>()
            every { personaStore.save(capture(slot)) } answers { slot.captured }

            val request = CreatePersonaRequest(
                name = "New Persona",
                systemPrompt = "You are new.",
                isDefault = false
            )
            val response = controller.createPersona(request, adminExchange())

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should return 201 Created" }
            val body = response.body as PersonaResponse
            assertEquals("New Persona", body.name) { "Name should match request" }
            assertEquals("You are new.", body.systemPrompt) { "System prompt should match request" }
            assertFalse(body.isDefault) { "Should not be default" }
            assertTrue(body.id.isNotBlank()) { "ID should be generated (UUID)" }
        }

        @Test
        fun `should return 403 for non-admin user`() = runTest {
            val request = CreatePersonaRequest(name = "test", systemPrompt = "test")

            val response = controller.createPersona(request, userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403 for USER role" }
        }

        @Test
        fun `should reject create when role is missing`() = runTest {
            val slot = slot<Persona>()
            every { personaStore.save(capture(slot)) } answers { slot.captured }

            val request = CreatePersonaRequest(name = "test", systemPrompt = "test")
            val response = controller.createPersona(request, noAuthExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) {
                "Missing role should be rejected"
            }
        }

        @Test
        fun `should pass isDefault to store when creating default persona`() = runTest {
            val slot = slot<Persona>()
            every { personaStore.save(capture(slot)) } answers { slot.captured }

            val request = CreatePersonaRequest(
                name = "New Default",
                systemPrompt = "prompt",
                isDefault = true
            )
            controller.createPersona(request, adminExchange())

            assertTrue(slot.captured.isDefault) { "Saved persona should have isDefault=true" }
        }

        @Test
        fun `should pass extended fields to store when creating persona`() = runTest {
            val slot = slot<Persona>()
            every { personaStore.save(capture(slot)) } answers { slot.captured }

            val request = CreatePersonaRequest(
                name = "Expert",
                systemPrompt = "You are an expert.",
                description = "A domain expert",
                responseGuideline = "Use bullet points.",
                welcomeMessage = "Hello!",
                promptTemplateId = "template-expert",
                icon = "🧑‍💻",
                isActive = true
            )
            val response = controller.createPersona(request, adminExchange())

            assertEquals(HttpStatus.CREATED, response.statusCode) { "Should return 201" }
            val body = response.body as PersonaResponse
            assertEquals("A domain expert", body.description) { "Description should match" }
            assertEquals("Use bullet points.", body.responseGuideline) { "Guideline should match" }
            assertEquals("Hello!", body.welcomeMessage) { "Welcome message should match" }
            assertEquals("template-expert", body.promptTemplateId) { "Linked prompt template should match" }
            assertEquals("🧑‍💻", body.icon) { "Icon should match" }
            assertTrue(body.isActive) { "isActive should be true" }

            // Verify captured persona
            assertEquals("A domain expert", slot.captured.description) { "Stored description should match" }
            assertEquals("Use bullet points.", slot.captured.responseGuideline) { "Stored guideline should match" }
            assertEquals("template-expert", slot.captured.promptTemplateId) { "Stored linked prompt template should match" }
        }

        @Test
        fun `should default isActive to true and extended fields to null`() = runTest {
            val slot = slot<Persona>()
            every { personaStore.save(capture(slot)) } answers { slot.captured }

            val request = CreatePersonaRequest(
                name = "Minimal",
                systemPrompt = "prompt"
            )
            controller.createPersona(request, adminExchange())

            assertTrue(slot.captured.isActive) { "isActive should default to true" }
            assertNull(slot.captured.description) { "Description should default to null" }
            assertNull(slot.captured.responseGuideline) { "Guideline should default to null" }
            assertNull(slot.captured.welcomeMessage) { "Welcome message should default to null" }
            assertNull(slot.captured.promptTemplateId) { "Linked prompt template should default to null" }
            assertNull(slot.captured.icon) { "Icon should default to null" }
        }
    }

    @Nested
    inner class UpdatePersona {

        @Test
        fun `should return 404 when updating nonexistent persona`() = runTest {
            every { personaStore.update("nonexistent",
                any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns null

            val response = controller.updatePersona(
                "nonexistent",
                UpdatePersonaRequest(name = "Updated"),
                adminExchange()
            )

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode) { "Should return 404 for missing persona" }
        }

        @Test
        fun `should return updated persona with correct fields`() = runTest {
            val now = Instant.parse("2026-02-08T12:00:00Z")
            every { personaStore.update("p-1", "Updated Name",
                null, null, null, null, null, null, null, null) } returns Persona(
                "p-1", "Updated Name", "original prompt", false,
                createdAt = now, updatedAt = now.plusSeconds(10)
            )

            val response = controller.updatePersona(
                "p-1",
                UpdatePersonaRequest(name = "Updated Name"),
                adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            assertEquals("Updated Name", (response.body as PersonaResponse).name) { "Name should be updated" }
        }

        @Test
        fun `should return 403 for non-admin user`() = runTest {
            val response = controller.updatePersona(
                "p-1",
                UpdatePersonaRequest(name = "Hacked"),
                userExchange()
            )

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403 for USER role" }
        }

        @Test
        fun `should pass isDefault change to store`() = runTest {
            val now = Instant.now()
            every { personaStore.update("p-1", null, null, true,
                null, null, null, null, null, null) } returns Persona(
                "p-1", "Name", "prompt", true, createdAt = now, updatedAt = now
            )

            val response = controller.updatePersona(
                "p-1",
                UpdatePersonaRequest(isDefault = true),
                adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            assertTrue((response.body as PersonaResponse).isDefault) { "Should be marked as default after update" }
        }

        @Test
        fun `should pass linked prompt template changes to store`() = runTest {
            val now = Instant.now()
            every { personaStore.update("p-1", null, null, null,
                null, null, null, null, "template-a", null) } returns Persona(
                id = "p-1",
                name = "Name",
                systemPrompt = "prompt",
                promptTemplateId = "template-a",
                createdAt = now,
                updatedAt = now
            )

            val response = controller.updatePersona(
                "p-1",
                UpdatePersonaRequest(promptTemplateId = "template-a"),
                adminExchange()
            )

            assertEquals(HttpStatus.OK, response.statusCode) { "Should return 200" }
            assertEquals("template-a", (response.body as PersonaResponse).promptTemplateId) {
                "Linked prompt template should be returned"
            }
        }
    }

    @Nested
    inner class DeletePersona {

        @Test
        fun `should return 204 on successful deletion for admin`() = runTest {
            every { personaStore.delete("p-1") } returns Unit

            val response = controller.deletePersona("p-1", adminExchange())

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) { "Should return 204 No Content" }
        }

        @Test
        fun `should return 403 for non-admin user`() = runTest {
            val response = controller.deletePersona("p-1", userExchange())

            assertEquals(HttpStatus.FORBIDDEN, response.statusCode) { "Should return 403 for USER role" }
        }

        @Test
        fun `should call store delete with correct personaId`() = runTest {
            every { personaStore.delete(any()) } returns Unit

            controller.deletePersona("target-persona", adminExchange())

            verify(exactly = 1) { personaStore.delete("target-persona") }
        }

        @Test
        fun `should return 204 even for nonexistent persona`() = runTest {
            every { personaStore.delete("nonexistent") } returns Unit

            val response = controller.deletePersona("nonexistent", adminExchange())

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode) { "DELETE should be idempotent" }
        }
    }
}
