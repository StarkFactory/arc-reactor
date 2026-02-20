package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.impl.DefaultInjectionDetectionStage
import com.arc.reactor.guard.impl.DefaultInputValidationStage
import com.arc.reactor.guard.impl.DefaultRateLimitStage
import com.arc.reactor.guard.impl.GuardPipeline
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
    @ConditionalOnMissingBean(name = ["rateLimitStage"])
    fun rateLimitStage(properties: AgentProperties): GuardStage =
        DefaultRateLimitStage(
            requestsPerMinute = properties.guard.rateLimitPerMinute,
            requestsPerHour = properties.guard.rateLimitPerHour
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
    @ConditionalOnMissingBean
    fun requestGuard(stages: List<GuardStage>): RequestGuard = GuardPipeline(stages)
}
