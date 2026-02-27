package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.audit.GuardAuditPublisher
import com.arc.reactor.guard.impl.CompositeClassificationStage
import com.arc.reactor.guard.impl.DefaultInjectionDetectionStage
import com.arc.reactor.guard.impl.DefaultInputValidationStage
import com.arc.reactor.guard.impl.DefaultRateLimitStage
import com.arc.reactor.guard.impl.GuardPipeline
import com.arc.reactor.guard.impl.LlmClassificationStage
import com.arc.reactor.guard.impl.RuleBasedClassificationStage
import com.arc.reactor.guard.impl.TopicDriftDetectionStage
import com.arc.reactor.guard.impl.UnicodeNormalizationStage
import com.arc.reactor.memory.MemoryStore
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Guard Configuration
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.guard", name = ["enabled"],
    havingValue = "true", matchIfMissing = true
)
class GuardConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["unicodeNormalizationStage"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.guard", name = ["unicode-normalization-enabled"],
        havingValue = "true", matchIfMissing = true
    )
    fun unicodeNormalizationStage(properties: AgentProperties): GuardStage =
        UnicodeNormalizationStage(maxZeroWidthRatio = properties.guard.maxZeroWidthRatio)

    @Bean
    @ConditionalOnMissingBean(name = ["rateLimitStage"])
    fun rateLimitStage(properties: AgentProperties): GuardStage =
        DefaultRateLimitStage(
            requestsPerMinute = properties.guard.rateLimitPerMinute,
            requestsPerHour = properties.guard.rateLimitPerHour,
            tenantRateLimits = properties.guard.tenantRateLimits
        )

    @Bean
    @ConditionalOnMissingBean(name = ["inputValidationStage"])
    fun inputValidationStage(properties: AgentProperties): GuardStage {
        return DefaultInputValidationStage(
            maxLength = properties.boundaries.inputMaxChars,
            minLength = properties.boundaries.inputMinChars,
            systemPromptMaxChars = properties.boundaries.systemPromptMaxChars
        )
    }

    @Bean
    @ConditionalOnMissingBean(name = ["injectionDetectionStage"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.guard", name = ["injection-detection-enabled"],
        havingValue = "true", matchIfMissing = true
    )
    fun injectionDetectionStage(): GuardStage = DefaultInjectionDetectionStage()

    @Bean
    @ConditionalOnMissingBean(name = ["classificationStage"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.guard", name = ["classification-enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun classificationStage(
        properties: AgentProperties,
        chatClient: ObjectProvider<ChatClient>
    ): GuardStage {
        val ruleBasedStage = RuleBasedClassificationStage()
        val llmStage = if (properties.guard.classificationLlmEnabled) {
            chatClient.ifAvailable?.let { LlmClassificationStage(it) }
        } else {
            null
        }
        return CompositeClassificationStage(ruleBasedStage, llmStage)
    }

    @Bean
    @ConditionalOnMissingBean(name = ["topicDriftDetectionStage"])
    @ConditionalOnProperty(
        prefix = "arc.reactor.guard", name = ["topic-drift-enabled"],
        havingValue = "true", matchIfMissing = false
    )
    fun topicDriftDetectionStage(
        memoryStore: ObjectProvider<MemoryStore>
    ): GuardStage = TopicDriftDetectionStage(memoryStore = memoryStore.ifAvailable)

    @Bean
    @ConditionalOnMissingBean
    fun requestGuard(
        stages: List<GuardStage>,
        auditPublisher: ObjectProvider<GuardAuditPublisher>
    ): RequestGuard = GuardPipeline(stages, auditPublisher.ifAvailable)
}
