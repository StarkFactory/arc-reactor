package com.arc.reactor.agent

import com.arc.reactor.agent.config.*
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
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
        every { fixture.callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("""{"result": "hello"}""")
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
            assertFalse(capturedPrompt.contains("YAML"), "TEXT format should not add YAML instructions")
            assertEquals("You are helpful.", capturedPrompt)
        }
    }

    @Nested
    inner class JsonFormat {

        @Test
        fun `JSON format should add enhanced JSON instruction to system prompt`() = runBlocking {
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
            assertTrue(capturedPrompt.contains("Do NOT wrap"), "Should warn against markdown code blocks")
            assertTrue(
                capturedPrompt.contains("'{'") || capturedPrompt.contains("'{' or '['") || capturedPrompt.contains("start with"),
                "Should mention start/end constraints"
            )
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
        fun `valid JSON should pass through without repair`() = runBlocking {
            every { fixture.callResponseSpec.chatResponse() } returns
                AgentTestFixture.simpleChatResponse("""{"name": "Arc", "version": "1.0"}""")

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

        @Test
        fun `JSON wrapped in markdown code fence should be stripped`() = runBlocking {
            val wrappedJson = "```json\n{\"name\": \"Arc\"}\n```"
            every { fixture.callResponseSpec.chatResponse() } returns
                AgentTestFixture.simpleChatResponse(wrappedJson)

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Give me data",
                    responseFormat = ResponseFormat.JSON
                )
            )

            result.assertSuccess()
            assertEquals("""{"name": "Arc"}""", result.content, "Should strip markdown code fence")
        }

        @Test
        fun `invalid JSON should trigger repair and succeed if repair returns valid JSON`() = runBlocking {
            // First call returns invalid JSON
            every { fixture.callResponseSpec.chatResponse() } returns
                AgentTestFixture.simpleChatResponse("{invalid json here")

            // Repair call returns valid JSON (uses chatClient.prompt().user().call().chatResponse())
            val repairCallSpec = fixture.mockFinalResponse("""{"repaired": true}""")
            // The repair call goes through the same chatClient.prompt() chain
            // Since fixture already mocks prompt() → requestSpec → call() → callResponseSpec,
            // we need to make the second call return the repair response
            every { fixture.callResponseSpec.chatResponse() } returnsMany listOf(
                AgentTestFixture.simpleChatResponse("{invalid json here"),
                AgentTestFixture.simpleChatResponse("""{"repaired": true}""")
            )

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Give me data",
                    responseFormat = ResponseFormat.JSON
                )
            )

            result.assertSuccess()
            assertEquals("""{"repaired": true}""", result.content, "Should return repaired JSON")
        }

        @Test
        fun `invalid JSON that cannot be repaired should return INVALID_RESPONSE`() = runBlocking {
            // Both original and repair return invalid JSON
            every { fixture.callResponseSpec.chatResponse() } returnsMany listOf(
                AgentTestFixture.simpleChatResponse("this is not json at all"),
                AgentTestFixture.simpleChatResponse("still not json")
            )

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Give me data",
                    responseFormat = ResponseFormat.JSON
                )
            )

            result.assertFailure()
            result.assertErrorCode(AgentErrorCode.INVALID_RESPONSE)
        }

        @Test
        fun `JSON array should be valid`() = runBlocking {
            every { fixture.callResponseSpec.chatResponse() } returns
                AgentTestFixture.simpleChatResponse("""[{"id": 1}, {"id": 2}]""")

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "List items",
                    responseFormat = ResponseFormat.JSON
                )
            )

            result.assertSuccess()
            assertTrue(result.content!!.startsWith("["), "JSON array should be valid")
        }
    }

    @Nested
    inner class YamlFormat {

        @Test
        fun `YAML format should add YAML instruction to system prompt`() = runBlocking {
            val systemSlot = slot<String>()
            every { fixture.requestSpec.system(capture(systemSlot)) } returns fixture.requestSpec
            every { fixture.callResponseSpec.chatResponse() } returns
                AgentTestFixture.simpleChatResponse("name: Arc\nversion: 1.0")

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Give me data",
                    responseFormat = ResponseFormat.YAML
                )
            )

            val capturedPrompt = systemSlot.captured
            assertTrue(capturedPrompt.contains("valid YAML"), "Should include YAML instruction")
            assertTrue(capturedPrompt.contains("MUST"), "Should be emphatic about YAML requirement")
            assertTrue(capturedPrompt.contains("Do NOT wrap"), "Should warn against code fences")
        }

        @Test
        fun `YAML format with schema should include structure hint`() = runBlocking {
            val systemSlot = slot<String>()
            every { fixture.requestSpec.system(capture(systemSlot)) } returns fixture.requestSpec
            every { fixture.callResponseSpec.chatResponse() } returns
                AgentTestFixture.simpleChatResponse("name: Arc\nversion: 1.0")

            val schema = "name: string\nversion: string"

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Give me data",
                    responseFormat = ResponseFormat.YAML,
                    responseSchema = schema
                )
            )

            val capturedPrompt = systemSlot.captured
            assertTrue(capturedPrompt.contains(schema), "Should include the YAML structure hint")
            assertTrue(capturedPrompt.contains("Expected YAML structure"), "Should label it as expected structure")
        }

        @Test
        fun `valid YAML should pass through without repair`() = runBlocking {
            val yamlContent = "name: Arc\nversion: 1.0\nfeatures:\n  - agents\n  - tools"
            every { fixture.callResponseSpec.chatResponse() } returns
                AgentTestFixture.simpleChatResponse(yamlContent)

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Give me data",
                    responseFormat = ResponseFormat.YAML
                )
            )

            result.assertSuccess()
            assertEquals(yamlContent, result.content, "Valid YAML should pass through as-is")
        }

        @Test
        fun `YAML wrapped in code fence should be stripped`() = runBlocking {
            val wrappedYaml = "```yaml\nname: Arc\nversion: 1.0\n```"
            every { fixture.callResponseSpec.chatResponse() } returns
                AgentTestFixture.simpleChatResponse(wrappedYaml)

            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Give me data",
                    responseFormat = ResponseFormat.YAML
                )
            )

            result.assertSuccess()
            assertEquals("name: Arc\nversion: 1.0", result.content, "Should strip YAML code fence")
        }

        @Test
        fun `streaming mode should reject YAML format`() = runBlocking {
            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val chunks = executor.executeStream(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Give me data",
                    responseFormat = ResponseFormat.YAML
                )
            ).toList()

            assertTrue(chunks.isNotEmpty()) { "Expected streaming chunks to be non-empty" }
            assertTrue(
                chunks.any { it.contains("not supported") || it.contains("error") },
                "Should emit error about YAML not being supported in streaming: $chunks"
            )
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

        @Test
        fun `streaming mode should include format name in error message`() = runBlocking {
            val executor = SpringAiAgentExecutor(chatClient = fixture.chatClient, properties = properties)

            val jsonChunks = executor.executeStream(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Give me data",
                    responseFormat = ResponseFormat.JSON
                )
            ).toList()

            assertTrue(
                jsonChunks.any { it.contains("JSON") },
                "JSON streaming error should mention JSON: $jsonChunks"
            )

            val yamlChunks = executor.executeStream(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Give me data",
                    responseFormat = ResponseFormat.YAML
                )
            ).toList()

            assertTrue(
                yamlChunks.any { it.contains("YAML") },
                "YAML streaming error should mention YAML: $yamlChunks"
            )
        }
    }
}
