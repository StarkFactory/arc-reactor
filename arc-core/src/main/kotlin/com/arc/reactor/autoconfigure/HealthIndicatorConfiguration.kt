package com.arc.reactor.autoconfigure

import com.arc.reactor.health.LlmProviderHealthIndicator
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/**
 * Auto-configuration for Arc Reactor health indicators.
 *
 * Registers [LlmProviderHealthIndicator] when Spring Boot Actuator is on the classpath.
 * The bean name `llmProviderHealthIndicator` causes Spring Boot to register it as the
 * "llmProvider" health component under `/actuator/health`.
 */
@ConditionalOnClass(name = ["org.springframework.boot.actuate.health.HealthIndicator"])
class HealthIndicatorConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun llmProviderHealthIndicator(environment: Environment): LlmProviderHealthIndicator =
        LlmProviderHealthIndicator(environment)
}
