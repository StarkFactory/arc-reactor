package com.arc.reactor.cache.impl

import com.arc.reactor.cache.PromptCacheMetrics
import com.arc.reactor.cache.PromptCachingService
import org.springframework.ai.chat.prompt.ChatOptions

/**
 * [PromptCachingService]의 No-op 구현체.
 *
 * 프롬프트 캐싱이 비활성화되어 있거나 프로바이더별 구현이 없을 때 사용된다.
 * 모든 작업이 수정 없이 통과한다.
 */
class NoOpPromptCachingService : PromptCachingService {

    override fun applyCaching(
        options: ChatOptions,
        provider: String,
        estimatedSystemPromptTokens: Int
    ): ChatOptions = options

    override fun extractCacheMetrics(nativeUsage: Any?): PromptCacheMetrics? = null
}
