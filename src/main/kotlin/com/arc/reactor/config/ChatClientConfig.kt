package com.arc.reactor.config

import com.arc.reactor.agent.config.AgentProperties
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * ChatClient bean configuration
 *
 * Creates a ChatClient using the default provider's ChatModel.
 * Resolves the multi-ChatModel ambiguity that occurs when multiple
 * LLM provider starters are on the classpath.
 */
@Configuration
class ChatClientConfig {

    @Bean
    @ConditionalOnMissingBean
    fun chatClient(
        chatModels: Map<String, ChatModel>,
        properties: AgentProperties
    ): ChatClient {
        val defaultBeanName = chatModels.keys.firstOrNull { beanName ->
            ChatModelProvider.resolveProviderName(beanName) == properties.llm.defaultProvider
        } ?: chatModels.keys.first()
        return ChatClient.create(chatModels[defaultBeanName]!!)
    }
}
