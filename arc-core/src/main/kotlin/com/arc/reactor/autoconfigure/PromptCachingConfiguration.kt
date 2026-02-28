package com.arc.reactor.autoconfigure

import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.cache.PromptCachingService
import com.arc.reactor.cache.impl.AnthropicPromptCachingService
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for Anthropic prompt caching.
 *
 * Registers [AnthropicPromptCachingService] when:
 * - `arc.reactor.llm.prompt-caching.enabled=true`
 * - `spring-ai-anthropic` is on the classpath (`AnthropicChatOptions` class present)
 *
 * If the condition is not met (e.g., different provider or dependency missing),
 * no [PromptCachingService] bean is registered, and callers fall back gracefully.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "arc.reactor.llm.prompt-caching",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = false
)
@ConditionalOnClass(name = ["org.springframework.ai.anthropic.AnthropicChatOptions"])
class PromptCachingConfiguration {

    /**
     * Anthropic Prompt Caching Service.
     *
     * Applies [org.springframework.ai.anthropic.AnthropicCacheStrategy] to chat requests
     * directed at the Anthropic provider, and parses cache token metrics from the response.
     */
    @Bean
    @ConditionalOnMissingBean
    fun promptCachingService(properties: AgentProperties): PromptCachingService =
        AnthropicPromptCachingService(properties.llm.promptCaching)
}
