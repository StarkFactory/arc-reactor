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
 * [PromptCachingService]의 Anthropic 전용 구현체.
 *
 * 활성 설정에 따라 [AnthropicChatOptions]를 통해
 * [AnthropicCacheStrategy.SYSTEM_AND_TOOLS] 또는 [AnthropicCacheStrategy.SYSTEM_ONLY]를 적용한다.
 *
 * 캐시 메트릭([PromptCacheMetrics])은 Anthropic이 응답 메타데이터에 반환하는
 * [AnthropicApi.Usage]에서 추출한다.
 *
 * 이 클래스는 다음 조건에서만 인스턴스화된다:
 * - `arc.reactor.llm.prompt-caching.enabled=true`
 * - `spring-ai-anthropic`이 클래스패스에 존재
 */
class AnthropicPromptCachingService(
    private val properties: PromptCachingProperties
) : PromptCachingService {

    override fun applyCaching(
        options: ChatOptions,
        provider: String,
        estimatedSystemPromptTokens: Int
    ): ChatOptions {
        // Anthropic 프로바이더가 아니면 변경 없이 통과
        if (!isAnthropicProvider(provider)) return options
        // 최소 토큰 임계값 미달 시 캐싱 효과가 없으므로 건너뛴다
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

    /**
     * Anthropic API 응답에서 캐시 사용 메트릭을 추출한다.
     * AnthropicApi.Usage 타입이 아니면 null을 반환한다.
     */
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

    /** 설정에 따라 적절한 캐싱 전략을 결정한다. */
    private fun resolveStrategy(): AnthropicCacheStrategy = when {
        properties.cacheSystemPrompt && properties.cacheTools -> AnthropicCacheStrategy.SYSTEM_AND_TOOLS
        properties.cacheSystemPrompt -> AnthropicCacheStrategy.SYSTEM_ONLY
        properties.cacheTools -> AnthropicCacheStrategy.TOOLS_ONLY
        else -> AnthropicCacheStrategy.SYSTEM_ONLY
    }

    /** 프로바이더 이름이 Anthropic인지 대소문자 무시하여 확인 */
    private fun isAnthropicProvider(provider: String) =
        provider.equals(properties.provider, ignoreCase = true)
}
