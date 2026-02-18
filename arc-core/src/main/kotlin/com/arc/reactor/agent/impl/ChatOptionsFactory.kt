package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions

internal class ChatOptionsFactory(
    private val defaultTemperature: Double,
    private val maxOutputTokens: Int,
    private val googleSearchRetrievalEnabled: Boolean
) {

    fun create(command: AgentCommand, hasTools: Boolean, fallbackProvider: String): ChatOptions {
        val temperature = command.temperature ?: defaultTemperature
        val provider = command.model ?: fallbackProvider
        val isGemini = provider.equals("gemini", ignoreCase = true) || provider.equals("vertex", ignoreCase = true)

        if (isGemini) {
            return GoogleGenAiChatOptions.builder()
                .temperature(temperature)
                .maxOutputTokens(maxOutputTokens)
                .googleSearchRetrieval(googleSearchRetrievalEnabled)
                .internalToolExecutionEnabled(!hasTools)
                .build()
        }

        return if (hasTools) {
            ToolCallingChatOptions.builder()
                .temperature(temperature)
                .maxTokens(maxOutputTokens)
                .internalToolExecutionEnabled(false)
                .build()
        } else {
            ChatOptions.builder()
                .temperature(temperature)
                .maxTokens(maxOutputTokens)
                .build()
        }
    }
}
