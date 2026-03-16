package com.arc.reactor.controller

import com.arc.reactor.persona.Persona
import com.arc.reactor.persona.PersonaStore
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.prompt.PromptVersion
import com.arc.reactor.prompt.VersionStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SystemPromptResolverTest {

    private val personaStore: PersonaStore = mockk()
    private val promptTemplateStore: PromptTemplateStore = mockk()
    private val resolver = SystemPromptResolver(personaStore = personaStore, promptTemplateStore = promptTemplateStore)

    @Nested
    inner class BuildEffectivePrompt {

        @Test
        fun `responseGuideline is null일 때 return systemPrompt only해야 한다`() {
            every { personaStore.get("p-1") } returns Persona(
                id = "p-1",
                name = "Test",
                systemPrompt = "You are an assistant.",
                responseGuideline = null
            )

            val result = resolver.resolve("p-1", null, null)

            assertEquals("You are an assistant.", result) { "Should return systemPrompt without guideline" }
        }

        @Test
        fun `responseGuideline is blank일 때 return systemPrompt only해야 한다`() {
            every { personaStore.get("p-1") } returns Persona(
                id = "p-1",
                name = "Test",
                systemPrompt = "You are an assistant.",
                responseGuideline = "   "
            )

            val result = resolver.resolve("p-1", null, null)

            assertEquals("You are an assistant.", result) {
                "Should return systemPrompt without blank guideline"
            }
        }

        @Test
        fun `append responseGuideline to systemPrompt해야 한다`() {
            every { personaStore.get("p-1") } returns Persona(
                id = "p-1",
                name = "Test",
                systemPrompt = "You are an assistant.",
                responseGuideline = "Always respond in Korean."
            )

            val result = resolver.resolve("p-1", null, null)

            assertEquals(
                "You are an assistant.\n\nAlways respond in Korean.",
                result
            ) { "Should append guideline with double newline separator" }
        }

        @Test
        fun `persona is linked일 때 use linked prompt template content해야 한다`() {
            every { personaStore.get("p-1") } returns Persona(
                id = "p-1",
                name = "Templated",
                systemPrompt = "Fallback prompt.",
                responseGuideline = "Always respond in Korean.",
                promptTemplateId = "template-1"
            )
            every { promptTemplateStore.getActiveVersion("template-1") } returns PromptVersion(
                id = "version-1",
                templateId = "template-1",
                version = 3,
                content = "Template prompt.",
                status = VersionStatus.ACTIVE
            )

            val result = resolver.resolve("p-1", null, null)

            assertEquals(
                "Template prompt.\n\nAlways respond in Korean.",
                result
            ) { "Linked template content should be preferred over fallback persona prompt" }
        }

        @Test
        fun `linked template has no active version일 때 fall back to persona systemPrompt해야 한다`() {
            every { personaStore.get("p-1") } returns Persona(
                id = "p-1",
                name = "Templated",
                systemPrompt = "Fallback prompt.",
                responseGuideline = "Stay concise.",
                promptTemplateId = "template-1"
            )
            every { promptTemplateStore.getActiveVersion("template-1") } returns null

            val result = resolver.resolve("p-1", null, null)

            assertEquals(
                "Fallback prompt.\n\nStay concise.",
                result
            ) { "Fallback persona prompt should be used when no active template exists" }
        }

        @Test
        fun `default persona too에 대해 use buildEffectivePrompt해야 한다`() {
            every { personaStore.get(any()) } returns null
            every { personaStore.getDefault() } returns Persona(
                id = "default",
                name = "Default",
                systemPrompt = "You are helpful.",
                responseGuideline = "Be concise.",
                isDefault = true
            )

            val result = resolver.resolve(null, null, null)

            assertEquals(
                "You are helpful.\n\nBe concise.",
                result
            ) { "Default persona should also use buildEffectivePrompt" }
        }
    }

    @Nested
    inner class ResolutionPriority {

        @Test
        fun `prefer personaId over default persona해야 한다`() {
            every { personaStore.get("custom") } returns Persona(
                id = "custom",
                name = "Custom",
                systemPrompt = "Custom prompt."
            )
            every { promptTemplateStore.getActiveVersion(any()) } returns null

            val result = resolver.resolve("custom", null, "Direct override")

            assertEquals("Custom prompt.", result) { "Persona lookup should take priority" }
        }

        @Test
        fun `prefer personaId over direct promptTemplateId해야 한다`() {
            every { personaStore.get("custom") } returns Persona(
                id = "custom",
                name = "Custom",
                systemPrompt = "Persona prompt.",
                promptTemplateId = "persona-template"
            )
            every { promptTemplateStore.getActiveVersion("persona-template") } returns PromptVersion(
                id = "version-1",
                templateId = "persona-template",
                version = 1,
                content = "Linked persona prompt.",
                status = VersionStatus.ACTIVE
            )
            every { promptTemplateStore.getActiveVersion("request-template") } returns PromptVersion(
                id = "version-2",
                templateId = "request-template",
                version = 1,
                content = "Request template prompt.",
                status = VersionStatus.ACTIVE
            )

            val result = resolver.resolve("custom", "request-template", null)

            assertEquals("Linked persona prompt.", result) {
                "Persona-linked prompt should take priority over direct promptTemplateId"
            }
        }

        @Test
        fun `persona not found일 때 fallback to systemPrompt해야 한다`() {
            every { personaStore.get("missing") } returns null
            every { promptTemplateStore.getActiveVersion(any()) } returns null

            val result = resolver.resolve("missing", null, "Fallback prompt")

            assertEquals("Fallback prompt", result) { "Should fall back to direct systemPrompt" }
        }

        @Test
        fun `nothing else available일 때 fallback to hardcoded default해야 한다`() {
            every { personaStore.get(any()) } returns null
            every { personaStore.getDefault() } returns null

            val result = resolver.resolve(null, null, null)

            assertEquals(SystemPromptResolver.DEFAULT_SYSTEM_PROMPT, result) {
                "Should fall back to hardcoded default"
            }
        }

        @Test
        fun `skip inactive default persona해야 한다`() {
            every { personaStore.get(any()) } returns null
            every { personaStore.getDefault() } returns Persona(
                id = "inactive-default",
                name = "Disabled Default",
                systemPrompt = "Should not be used.",
                isDefault = true,
                isActive = false
            )

            val result = resolver.resolve(null, null, null)

            assertEquals(SystemPromptResolver.DEFAULT_SYSTEM_PROMPT, result) {
                "Inactive default persona should be skipped, falling back to hardcoded default"
            }
        }
    }
}
