package com.arc.reactor.cache.impl

import com.arc.reactor.agent.config.PromptCachingProperties
import com.arc.reactor.cache.PromptCacheMetrics
import com.arc.reactor.cache.PromptCachingService
import mu.KotlinLogging
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.anthropic.api.AnthropicCacheOptions
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy
import org.springframework.ai.chat.prompt.ChatOptions

private val logger = KotlinLogging.logger {}

/**
 * Anthropic-specific implementation of [PromptCachingService].
 *
 * Applies [AnthropicCacheStrategy.SYSTEM_AND_TOOLS] or [AnthropicCacheStrategy.SYSTEM_ONLY]
 * via [AnthropicChatOptions] depending on the active configuration.
 *
 * Cache metrics ([PromptCacheMetrics]) are extracted from [AnthropicApi.Usage]
 * which Anthropic returns in the response metadata.
 *
 * This class is only instantiated when:
 * - `arc.reactor.llm.prompt-caching.enabled=true`
 * - `spring-ai-anthropic` is present on the classpath
 */
class AnthropicPromptCachingService(
    private val properties: PromptCachingProperties
) : PromptCachingService {

    override fun applyCaching(
        options: ChatOptions,
        provider: String,
        estimatedSystemPromptTokens: Int
    ): ChatOptions {
        if (!isAnthropicProvider(provider)) return options
        if (estimatedSystemPromptTokens < properties.minCacheableTokens) {
            logger.debug {
                "Skipping prompt cache: estimated $estimatedSystemPromptTokens tokens " +
                    "< minCacheableTokens=${properties.minCacheableTokens}"
            }
            return options
        }

        val strategy = resolveStrategy()
        val cacheOptions = AnthropicCacheOptions.builder()
            .strategy(strategy)
            .build()

        val temperature = options.temperature
        val maxTokens = options.maxTokens ?: 4096

        return AnthropicChatOptions.builder()
            .temperature(temperature)
            .maxTokens(maxTokens)
            .cacheOptions(cacheOptions)
            .build()
    }

    override fun extractCacheMetrics(nativeUsage: Any?): PromptCacheMetrics? {
        if (nativeUsage !is AnthropicApi.Usage) return null
        val metrics = PromptCacheMetrics(
            cacheCreationInputTokens = nativeUsage.cacheCreationInputTokens() ?: 0,
            cacheReadInputTokens = nativeUsage.cacheReadInputTokens() ?: 0,
            regularInputTokens = nativeUsage.inputTokens() ?: 0
        )
        logger.debug {
            "Anthropic cache usage — created=${metrics.cacheCreationInputTokens}, " +
                "read=${metrics.cacheReadInputTokens}, regular=${metrics.regularInputTokens}"
        }
        return metrics
    }

    private fun resolveStrategy(): AnthropicCacheStrategy = when {
        properties.cacheSystemPrompt && properties.cacheTools -> AnthropicCacheStrategy.SYSTEM_AND_TOOLS
        properties.cacheSystemPrompt -> AnthropicCacheStrategy.SYSTEM_ONLY
        properties.cacheTools -> AnthropicCacheStrategy.TOOLS_ONLY
        else -> AnthropicCacheStrategy.SYSTEM_ONLY
    }

    private fun isAnthropicProvider(provider: String) =
        provider.equals(properties.provider, ignoreCase = true)
}
