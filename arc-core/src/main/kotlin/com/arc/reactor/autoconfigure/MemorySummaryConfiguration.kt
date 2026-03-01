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
 * Conversation memory summary configuration.
 *
 * Only activated when `arc.reactor.memory.summary.enabled=true`.
 * Registers the summary store, summary service, and LLM-based summarizer.
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
    @ConditionalOnProperty(
        prefix = "arc.reactor.memory.summary", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun jdbcConversationSummaryStore(
        jdbcTemplate: JdbcTemplate
    ): ConversationSummaryStore = JdbcConversationSummaryStore(jdbcTemplate)
}
