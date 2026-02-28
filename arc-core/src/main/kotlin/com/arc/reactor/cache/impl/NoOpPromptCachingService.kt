package com.arc.reactor.cache.impl

import com.arc.reactor.cache.PromptCacheMetrics
import com.arc.reactor.cache.PromptCachingService
import org.springframework.ai.chat.prompt.ChatOptions

/**
 * No-op implementation of [PromptCachingService].
 *
 * Used when prompt caching is disabled or no provider-specific implementation
 * is available. All operations pass through without modification.
 */
class NoOpPromptCachingService : PromptCachingService {

    override fun applyCaching(
        options: ChatOptions,
        provider: String,
        estimatedSystemPromptTokens: Int
    ): ChatOptions = options

    override fun extractCacheMetrics(nativeUsage: Any?): PromptCacheMetrics? = null
}
