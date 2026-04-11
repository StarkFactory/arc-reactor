package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.config.ChatModelProvider
import com.arc.reactor.intent.InMemoryIntentRegistry
import com.arc.reactor.intent.IntentClassifier
import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.IntentResolver
import com.arc.reactor.intent.impl.CompositeIntentClassifier
import com.arc.reactor.intent.impl.JdbcIntentRegistry
import com.arc.reactor.intent.impl.LlmIntentClassifier
import com.arc.reactor.intent.impl.RuleBasedIntentClassifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * 인텐트 분류 설정 (arc.reactor.intent.enabled=true일 때만)
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.intent", name = ["enabled"],
    havingValue = "true", matchIfMissing = false
)
class IntentConfiguration {

    /**
     * 인텐트 레지스트리 폴백 구현.
     *
     * [JdbcIntentRegistryConfiguration]이 DataSource 부재로 비활성화되면
     * 인메모리 구현으로 대체한다. 이 빈이 없으면 `arc.reactor.intent.enabled=true`이고
     * DB가 없는 환경에서 `NoSuchBeanDefinitionException`으로 애플리케이션 기동이 실패한다.
     *
     * R305 fix: IntentConfiguration이 intentRegistry를 요구하지만 JDBC만 제공하는 경로라
     * DataSource 없는 환경에서 silent startup failure가 발생했다.
     */
    @Bean
    @ConditionalOnMissingBean(IntentRegistry::class)
    fun intentRegistry(): IntentRegistry = InMemoryIntentRegistry()

    /**
     * 인텐트 분류기: 복합 (규칙 -> LLM 캐스케이딩)
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
     * 인텐트 해석기
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
 * JDBC 인텐트 레지스트리 (JDBC가 사용 가능하고 인텐트가 활성화된 경우)
 */
@Configuration
@ConditionalOnClass(name = ["org.springframework.jdbc.core.JdbcTemplate"])
@ConditionalOnExpression("'\${spring.datasource.url:}'.trim().length() > 0")
@ConditionalOnBean(DataSource::class)
class JdbcIntentRegistryConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcIntentRegistry"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.intent", name = ["enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun jdbcIntentRegistry(
        jdbcTemplate: JdbcTemplate
    ): IntentRegistry = JdbcIntentRegistry(jdbcTemplate)
}
