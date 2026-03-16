package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    private val factoryWithSampling = ChatOptionsFactory(
        defaultTemperature = 0.3,
        maxOutputTokens = 2048,
        googleSearchRetrievalEnabled = true,
        topP = 0.9,
        frequencyPenalty = 0.5,
        presencePenalty = 0.3
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
        assertTrue(readBooleanOption(options, "googleSearchRetrieval"), "googleSearchRetrieval should be enabled for Gemini")
        assertTrue(readBooleanOption(options, "internalToolExecutionEnabled"), "internalToolExecutionEnabled should be enabled for Gemini with tools")
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
        assertFalse(readBooleanOption(options, "internalToolExecutionEnabled"), "internalToolExecutionEnabled should be disabled when command overrides temperature")
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
        assertFalse(readBooleanOption(options, "internalToolExecutionEnabled"), "internalToolExecutionEnabled should be disabled for non-Gemini provider")
    }

    @Test
    fun `creates plain chat options when non-gemini provider has no tools`() {
        val options = factory.create(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hello", model = "anthropic"),
            hasTools = false,
            fallbackProvider = "gemini"
        )

        assertFalse(options is ToolCallingChatOptions, "Non-Gemini provider without tools should not produce ToolCallingChatOptions")
        assertFalse(options is GoogleGenAiChatOptions, "Non-Gemini provider should not produce GoogleGenAiChatOptions")
        assertEquals(2048, options.maxTokens)
    }

    @Test
    fun `applies sampling parameters for gemini provider`() {
        val options = factoryWithSampling.create(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hello"),
            hasTools = false,
            fallbackProvider = "gemini"
        )

        assertInstanceOf(GoogleGenAiChatOptions::class.java, options)
        assertEquals(0.9, options.topP) { "topP should be set for Gemini" }
        assertEquals(0.5, options.frequencyPenalty) { "frequencyPenalty should be set for Gemini" }
        assertEquals(0.3, options.presencePenalty) { "presencePenalty should be set for Gemini" }
    }

    @Test
    fun `applies sampling parameters for non-gemini provider with tools`() {
        val options = factoryWithSampling.create(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hello", model = "openai"),
            hasTools = true,
            fallbackProvider = "gemini"
        )

        assertInstanceOf(ToolCallingChatOptions::class.java, options)
        assertEquals(0.9, options.topP) { "topP should be set for ToolCallingChatOptions" }
        assertEquals(0.5, options.frequencyPenalty) { "frequencyPenalty should be set for ToolCallingChatOptions" }
        assertEquals(0.3, options.presencePenalty) { "presencePenalty should be set for ToolCallingChatOptions" }
    }

    @Test
    fun `applies sampling parameters for non-gemini provider without tools`() {
        val options = factoryWithSampling.create(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hello", model = "anthropic"),
            hasTools = false,
            fallbackProvider = "gemini"
        )

        assertEquals(0.9, options.topP) { "topP should be set for plain ChatOptions" }
        assertEquals(0.5, options.frequencyPenalty) { "frequencyPenalty should be set for plain ChatOptions" }
        assertEquals(0.3, options.presencePenalty) { "presencePenalty should be set for plain ChatOptions" }
    }

    @Test
    fun `leaves sampling parameters null when not configured`() {
        val options = factory.create(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hello", model = "openai"),
            hasTools = false,
            fallbackProvider = "openai"
        )

        assertNull(options.topP) { "topP should be null when not configured" }
        assertNull(options.frequencyPenalty) { "frequencyPenalty should be null when not configured" }
        assertNull(options.presencePenalty) { "presencePenalty should be null when not configured" }
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
