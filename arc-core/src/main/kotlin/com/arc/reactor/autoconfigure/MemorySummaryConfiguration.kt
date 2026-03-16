package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.memory.summary.ConversationSummaryService
import com.arc.reactor.memory.summary.ConversationSummaryStore
import com.arc.reactor.memory.summary.InMemoryConversationSummaryStore
import com.arc.reactor.memory.summary.JdbcConversationSummaryStore
import com.arc.reactor.memory.summary.LlmConversationSummaryService
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 대화 메모리 요약 설정.
 *
 * `arc.reactor.memory.summary.enabled=true`일 때만 활성화된다.
 * 요약 저장소, 요약 서비스, LLM 기반 요약기를 등록한다.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.memory.summary", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class MemorySummaryConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun conversationSummaryStore(): ConversationSummaryStore = InMemoryConversationSummaryStore()

    @Bean
    @ConditionalOnMissingBean
    fun conversationSummaryService(
        chatModelProvider: ChatModelProvider,
        properties: AgentProperties
    ): ConversationSummaryService {
        val summaryProps = properties.memory.summary
        val chatClient = chatModelProvider.getChatClient(summaryProps.llmModel)
        return LlmConversationSummaryService(
            chatClient = chatClient,
            maxNarrativeTokens = summaryProps.maxNarrativeTokens
        )
    }
}

/**
 * JDBC-backed summary store (when JDBC is available and summary is enabled).
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
@ConditionalOnExpression("'\${spring.datasource.url:}'.trim().length() > 0")
class JdbcConversationSummaryStoreConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcConversationSummaryStore"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.memory.summary", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun jdbcConversationSummaryStore(
        jdbcTemplate: JdbcTemplate
    ): ConversationSummaryStore = JdbcConversationSummaryStore(jdbcTemplate)
}
