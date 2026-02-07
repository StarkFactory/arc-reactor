package com.arc.reactor.agent

import com.arc.reactor.agent.config.*
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.ResponseFormat
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StructuredOutputTest {

    private lateinit var fixture: AgentTestFixture
    private lateinit var properties: AgentProperties

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
        every { fixture.callResponseSpec.content() } returns """{"result": "hello"}"""
        every { fixture.callResponseSpec.chatResponse() } returns null
        properties = AgentProperties()
    }

    @Nested
    inner class TextFormat {

        @Test
        fun `TEXT format should not modify system prompt`() = runBlocking {
            val systemSlot = slot<String>()
            every { fixture.requestSpec.system(capture(systemSlot)) } returns fixture.requestSpec

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Hello!",
                    responseFormat = ResponseFormat.TEXT
                )
            )

            val capturedPrompt = systemSlot.captured
            assertFalse(capturedPrompt.contains("JSON"), "TEXT format should not add JSON instructions")
            assertEquals("You are helpful.", capturedPrompt)
        }
    }

    @Nested
    inner class JsonFormat {

        @Test
        fun `JSON format should add JSON instruction to system prompt`() = runBlocking {
            val systemSlot = slot<String>()
            every { fixture.requestSpec.system(capture(systemSlot)) } returns fixture.requestSpec

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Give me data",
                    responseFormat = ResponseFormat.JSON
                )
            )

            val capturedPrompt = systemSlot.captured
            assertTrue(capturedPrompt.contains("You are helpful."), "Should include original prompt")
            assertTrue(capturedPrompt.contains("valid JSON"), "Should include JSON instruction")
            assertTrue(capturedPrompt.contains("MUST"), "Should be emphatic about JSON requirement")
        }

        @Test
        fun `JSON format with schema should include schema in system prompt`() = runBlocking {
            val systemSlot = slot<String>()
            every { fixture.requestSpec.system(capture(systemSlot)) } returns fixture.requestSpec

            val schema = """{"type": "object", "properties": {"name": {"type": "string"}, "age": {"type": "number"}}}"""

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Give me user data",
                    responseFormat = ResponseFormat.JSON,
                    responseSchema = schema
                )
            )

            val capturedPrompt = systemSlot.captured
            assertTrue(capturedPrompt.contains("valid JSON"), "Should include JSON instruction")
            assertTrue(capturedPrompt.contains(schema), "Should include the schema")
            assertTrue(capturedPrompt.contains("schema"), "Should mention schema")
        }

        @Test
        fun `JSON format should work with successful response`() = runBlocking {
            every { fixture.callResponseSpec.content() } returns """{"name": "Arc", "version": "1.0"}"""

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "What is your info?",
                    responseFormat = ResponseFormat.JSON
                )
            )

            result.assertSuccess()
            assertEquals("""{"name": "Arc", "version": "1.0"}""", result.content)
        }
    }

    @Nested
    inner class StreamingMode {

        @Test
        fun `streaming mode should reject JSON format`() = runBlocking {
            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val chunks = executor.executeStream(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Give me data",
                    responseFormat = ResponseFormat.JSON
                )
            ).toList()

            assertTrue(chunks.isNotEmpty()) { "Expected streaming chunks to be non-empty, got: ${chunks.size} chunks" }
            assertTrue(chunks.any { it.contains("not supported") || it.contains("error") },
                "Should emit error about JSON not being supported in streaming: $chunks")
        }
    }
}
