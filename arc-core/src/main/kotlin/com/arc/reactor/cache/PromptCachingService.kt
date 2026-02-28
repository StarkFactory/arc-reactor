package com.arc.reactor.cache

import org.springframework.ai.chat.prompt.ChatOptions

/**
 * Service for applying Anthropic prompt caching to LLM requests.
 *
 * Marks the system prompt and/or tool definitions with Anthropic's
 * `cache_control: {"type": "ephemeral"}` directive so repeated content
 * is served from the Anthropic cache rather than re-processed.
 *
 * This can reduce prompt token costs by 80-90% for enterprise workloads
 * where the same system prompt (e.g., company policies, org charts) is
 * sent with every request.
 *
 * ## Provider Support
 * Only the `anthropic` provider is supported. Calls from other providers
 * pass through unchanged.
 *
 * ## Minimum Token Threshold
 * Anthropic requires a minimum number of tokens before caching is beneficial.
 * See [PromptCachingProperties.minCacheableTokens].
 */
interface PromptCachingService {

    /**
     * Apply Anthropic caching options to the given [ChatOptions].
     *
     * When the provider is `anthropic` and caching is enabled, returns a new
     * [ChatOptions] instance with cache directives applied to the system prompt
     * and/or tool definitions as configured.
     *
     * @param options Base chat options to decorate
     * @param provider The LLM provider name (e.g., "anthropic", "gemini")
     * @param estimatedSystemPromptTokens Estimated token count of the system prompt
     * @return Decorated options with cache directives, or the original options unchanged
     */
    fun applyCaching(
        options: ChatOptions,
        provider: String,
        estimatedSystemPromptTokens: Int
    ): ChatOptions

    /**
     * Extract cache usage metrics from a raw usage object returned by the Anthropic API.
     *
     * @param nativeUsage The native usage object from [ChatResponseMetadata.usage.nativeUsage]
     * @return Parsed cache metrics, or null if the object is not an Anthropic usage type
     */
    fun extractCacheMetrics(nativeUsage: Any?): PromptCacheMetrics?
}

/**
 * Token usage breakdown for Anthropic prompt caching.
 *
 * @param cacheCreationInputTokens Tokens written to the cache on this request (billed at 1.25x)
 * @param cacheReadInputTokens Tokens served from the cache on this request (billed at 0.1x)
 * @param regularInputTokens Tokens that were not cached
 */
data class PromptCacheMetrics(
    val cacheCreationInputTokens: Int = 0,
    val cacheReadInputTokens: Int = 0,
    val regularInputTokens: Int = 0
)
