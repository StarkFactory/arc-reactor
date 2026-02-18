package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions

class ChatOptionsFactoryTest {

    private val factory = ChatOptionsFactory(
        defaultTemperature = 0.3,
        maxOutputTokens = 2048,
        googleSearchRetrievalEnabled = true
    )

    @Test
    fun `creates gemini options when provider resolves to gemini`() {
        val options = factory.create(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hello"),
            hasTools = false,
            fallbackProvider = "gemini"
        )

        assertInstanceOf(GoogleGenAiChatOptions::class.java, options)
        assertEquals(0.3, options.temperature)
        assertTrue(readBooleanOption(options, "googleSearchRetrieval"))
        assertTrue(readBooleanOption(options, "internalToolExecutionEnabled"))
    }

    @Test
    fun `uses command model and command temperature override`() {
        val options = factory.create(
            command = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "hello",
                model = "vertex",
                temperature = 0.9
            ),
            hasTools = true,
            fallbackProvider = "openai"
        )

        assertInstanceOf(GoogleGenAiChatOptions::class.java, options)
        assertEquals(0.9, options.temperature)
        assertFalse(readBooleanOption(options, "internalToolExecutionEnabled"))
    }

    @Test
    fun `creates tool calling options for non-gemini provider when tools exist`() {
        val options = factory.create(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hello", model = "openai"),
            hasTools = true,
            fallbackProvider = "gemini"
        )

        assertInstanceOf(ToolCallingChatOptions::class.java, options)
        assertEquals(2048, options.maxTokens)
        assertFalse(readBooleanOption(options, "internalToolExecutionEnabled"))
    }

    @Test
    fun `creates plain chat options when non-gemini provider has no tools`() {
        val options = factory.create(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hello", model = "anthropic"),
            hasTools = false,
            fallbackProvider = "gemini"
        )

        assertFalse(options is ToolCallingChatOptions)
        assertFalse(options is GoogleGenAiChatOptions)
        assertEquals(2048, options.maxTokens)
    }

    private fun readBooleanOption(options: ChatOptions, optionName: String): Boolean {
        val capitalized = optionName.replaceFirstChar { it.uppercase() }
        val getter = options::class.java.methods.firstOrNull {
            it.name == "get$capitalized" || it.name == "is$capitalized"
        }
        if (getter != null) {
            val value = getter.invoke(options)
            assertNotNull(value) { "Option '$optionName' getter returned null" }
            return value as Boolean
        }

        val field = options::class.java.declaredFields.firstOrNull { it.name == optionName }
        if (field != null) {
            field.isAccessible = true
            val value = field.get(options)
            assertNotNull(value) { "Option '$optionName' field returned null" }
            return value as Boolean
        }

        error("Could not read option '$optionName' from ${options::class.java.name}")
    }
}
