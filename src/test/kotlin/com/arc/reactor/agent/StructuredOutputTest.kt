package com.arc.reactor.agent

import com.arc.reactor.agent.config.*
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.ResponseFormat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.prompt.ChatOptions

class StructuredOutputTest {

    private lateinit var chatClient: ChatClient
    private lateinit var requestSpec: ChatClientRequestSpec
    private lateinit var responseSpec: CallResponseSpec
    private lateinit var properties: AgentProperties

    @BeforeEach
    fun setup() {
        chatClient = mockk()
        requestSpec = mockk(relaxed = true)
        responseSpec = mockk()
        properties = AgentProperties()

        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.messages(any<List<Message>>()) } returns requestSpec
        every { requestSpec.options(any<ChatOptions>()) } returns requestSpec
        every { requestSpec.call() } returns responseSpec
        every { responseSpec.content() } returns """{"result": "hello"}"""
        every { responseSpec.chatResponse() } returns null
    }

    @Test
    fun `TEXT format should not modify system prompt`() = runBlocking {
        val systemSlot = slot<String>()
        every { requestSpec.system(capture(systemSlot)) } returns requestSpec

        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

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

    @Test
    fun `JSON format should add JSON instruction to system prompt`() = runBlocking {
        val systemSlot = slot<String>()
        every { requestSpec.system(capture(systemSlot)) } returns requestSpec

        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

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
        every { requestSpec.system(capture(systemSlot)) } returns requestSpec

        val schema = """{"type": "object", "properties": {"name": {"type": "string"}, "age": {"type": "number"}}}"""

        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

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
    fun `streaming mode should reject JSON format`() = runBlocking {
        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

        val chunks = executor.executeStream(
            AgentCommand(
                systemPrompt = "You are helpful.",
                userPrompt = "Give me data",
                responseFormat = ResponseFormat.JSON
            )
        ).toList()

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.any { it.contains("not supported") || it.contains("error") },
            "Should emit error about JSON not being supported in streaming: $chunks")
    }

    @Test
    fun `JSON format should work with successful response`() = runBlocking {
        every { responseSpec.content() } returns """{"name": "Arc", "version": "1.0"}"""

        val executor = SpringAiAgentExecutor(chatClient = chatClient, properties = properties)

        val result = executor.execute(
            AgentCommand(
                systemPrompt = "You are helpful.",
                userPrompt = "What is your info?",
                responseFormat = ResponseFormat.JSON
            )
        )

        assertTrue(result.success)
        assertEquals("""{"name": "Arc", "version": "1.0"}""", result.content)
    }
}
