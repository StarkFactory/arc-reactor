package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.impl.DefaultInjectionDetectionStage
import com.arc.reactor.guard.impl.DefaultInputValidationStage
import com.arc.reactor.guard.impl.DefaultRateLimitStage
import com.arc.reactor.guard.impl.GuardPipeline
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

private val logger = KotlinLogging.logger {}

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
    fun inputValidationStage(
        properties: AgentProperties,
        environment: Environment
    ): GuardStage {
        val hasBoundariesInputMax = environment.containsProperty("arc.reactor.boundaries.input-max-chars")
        val hasLegacyGuardInputMax = environment.containsProperty("arc.reactor.guard.max-input-length")

        val effectiveInputMaxChars = when {
            hasBoundariesInputMax -> properties.boundaries.inputMaxChars
            hasLegacyGuardInputMax -> {
                logger.warn {
                    "Deprecated config detected: arc.reactor.guard.max-input-length. " +
                        "Use arc.reactor.boundaries.input-max-chars instead."
                }
                @Suppress("DEPRECATION")
                properties.guard.maxInputLength
            }
            else -> properties.boundaries.inputMaxChars
        }

        return DefaultInputValidationStage(
            maxLength = effectiveInputMaxChars,
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
