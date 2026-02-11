package com.arc.reactor.config

import mu.KotlinLogging
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel

private val logger = KotlinLogging.logger {}

/**
 * Registry that maps provider names to ChatModel beans.
 *
 * Enables runtime provider selection per request. When no provider
 * is specified, falls back to [defaultProvider].
 *
 * Provider names are resolved from Spring AI bean names:
 * - `openAiChatModel` -> "openai"
 * - `anthropicChatModel` -> "anthropic"
 * - `googleAiGeminiChatModel` -> "gemini"
 *
 * @param chatModels map of provider name to ChatModel
 * @param defaultProvider fallback provider name
 */
class ChatModelProvider(
    private val chatModels: Map<String, ChatModel>,
    private val defaultProvider: String
) {

    init {
        logger.info {
            "ChatModelProvider initialized with providers: " +
                "${chatModels.keys}, default=$defaultProvider"
        }
    }

    /**
     * Returns a ChatClient for the given provider name.
     * Falls back to [defaultProvider] when provider is null.
     */
    fun getChatClient(provider: String?): ChatClient {
        val name = provider ?: defaultProvider
        val model = chatModels[name]
            ?: throw IllegalArgumentException(
                "Unknown provider: $name. " +
                    "Available: ${chatModels.keys}"
            )
        return ChatClient.create(model)
    }

    /** Returns the set of available provider names. */
    fun availableProviders(): Set<String> = chatModels.keys

    /** Returns the default provider name. */
    fun defaultProvider(): String = defaultProvider

    companion object {
        private val BEAN_NAME_MAPPING = mapOf(
            "openAiChatModel" to "openai",
            "anthropicChatModel" to "anthropic",
            "googleAiGeminiChatModel" to "gemini",
            "googleGenAiChatModel" to "gemini",
            "vertexAiGeminiChatModel" to "vertex"
        )

        /**
         * Resolves a human-friendly provider name from a Spring AI
         * bean name. Falls back to the original bean name if unknown.
         */
        fun resolveProviderName(beanName: String): String {
            return BEAN_NAME_MAPPING[beanName] ?: beanName
        }
    }
}
