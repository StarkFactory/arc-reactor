package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.intent.IntentClassifier
import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.intent.impl.CompositeIntentClassifier
import com.arc.reactor.intent.impl.JdbcIntentRegistry
import com.arc.reactor.intent.impl.LlmIntentClassifier
import com.arc.reactor.intent.impl.RuleBasedIntentClassifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Intent Classification Configuration (only when arc.reactor.intent.enabled=true)
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.intent", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class IntentConfiguration {

    /**
     * Intent Classifier: Composite (Rule -> LLM cascading)
     */
    @Bean
    @ConditionalOnMissingBean
    fun intentClassifier(
        intentRegistry: IntentRegistry,
        chatModelProvider: ChatModelProvider,
        properties: AgentProperties
    ): IntentClassifier {
        val intentProps = properties.intent
        val chatClient = chatModelProvider.getChatClient(intentProps.llmModel)
        val ruleClassifier = RuleBasedIntentClassifier(intentRegistry)
        val llmClassifier = LlmIntentClassifier(
            chatClient = chatClient,
            registry = intentRegistry,
            maxExamplesPerIntent = intentProps.maxExamplesPerIntent,
            maxConversationTurns = intentProps.maxConversationTurns
        )
        return CompositeIntentClassifier(
            ruleClassifier = ruleClassifier,
            llmClassifier = llmClassifier,
            ruleConfidenceThreshold = intentProps.ruleConfidenceThreshold
        )
    }

    /**
     * Intent Resolver
     */
    @Bean
    @ConditionalOnMissingBean
    fun intentResolver(
        intentClassifier: IntentClassifier,
        intentRegistry: IntentRegistry,
        properties: AgentProperties
    ): IntentResolver = IntentResolver(
        classifier = intentClassifier,
        registry = intentRegistry,
        confidenceThreshold = properties.intent.confidenceThreshold
    )
}

/**
 * JDBC Intent Registry (when JDBC is available and intent is enabled)
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
@ConditionalOnProperty(prefix = "spring.datasource", name = ["url"])
class JdbcIntentRegistryConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(
        prefix = "arc.reactor.intent", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun jdbcIntentRegistry(
        jdbcTemplate: JdbcTemplate
    ): IntentRegistry = JdbcIntentRegistry(jdbcTemplate)
}
