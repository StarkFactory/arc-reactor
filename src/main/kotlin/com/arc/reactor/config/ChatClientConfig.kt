package com.arc.reactor.config

import org.springframework.ai.chat.client.ChatClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * ChatClient 빈 설정
 *
 * Spring AI가 자동 구성하는 ChatClient.Builder를 사용하여 ChatClient를 생성합니다.
 * 이 빈이 있어야 AgentExecutor가 자동으로 생성됩니다.
 */
@Configuration
class ChatClientConfig {

    @Bean
    @ConditionalOnMissingBean
    fun chatClient(builder: ChatClient.Builder): ChatClient = builder.build()
}
