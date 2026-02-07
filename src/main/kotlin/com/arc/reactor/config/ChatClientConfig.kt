package com.arc.reactor.config

import org.springframework.ai.chat.client.ChatClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * ChatClient bean configuration
 *
 * Creates a ChatClient using the ChatClient.Builder auto-configured by Spring AI.
 * This bean must be present for AgentExecutor to be auto-created.
 */
@Configuration
class ChatClientConfig {

    @Bean
    @ConditionalOnMissingBean
    fun chatClient(builder: ChatClient.Builder): ChatClient = builder.build()
}
