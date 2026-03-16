package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.guard.canary.CanarySystemPromptPostProcessor
import com.arc.reactor.guard.canary.CanaryTokenProvider
import com.arc.reactor.guard.canary.SystemPromptPostProcessor
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.impl.SystemPromptLeakageOutputGuard
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Canary Token Configuration
 *
 * 카나리 토큰을 통한 시스템 프롬프트 유출 탐지를 활성화한다.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.guard", name = ["canary-token-enabled"],
    havingValue = "true", matchIfMissing = false
)
class CanaryConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun canaryTokenProvider(properties: AgentProperties): CanaryTokenProvider =
        CanaryTokenProvider(properties.guard.canarySeed)

    @Bean
    @ConditionalOnMissingBean
    fun systemPromptPostProcessor(canaryTokenProvider: CanaryTokenProvider): SystemPromptPostProcessor =
        CanarySystemPromptPostProcessor(canaryTokenProvider)

    @Bean
    @ConditionalOnMissingBean(name = ["systemPromptLeakageOutputGuard"])
    fun systemPromptLeakageOutputGuard(canaryTokenProvider: CanaryTokenProvider): OutputGuardStage =
        SystemPromptLeakageOutputGuard(canaryTokenProvider)
}
