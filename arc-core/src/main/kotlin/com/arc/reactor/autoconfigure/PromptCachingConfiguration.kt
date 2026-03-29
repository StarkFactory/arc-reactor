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
 * Anthropic 프롬프트 캐싱 자동 설정.
 *
 * 다음 조건을 충족할 때 [AnthropicPromptCachingService]를 등록한다:
 * - `arc.reactor.llm.prompt-caching.enabled=true`
 * - `spring-ai-anthropic`이 클래스패스에 존재 (`AnthropicChatOptions` 클래스 존재)
 *
 * 조건이 충족되지 않으면 (예: 다른 프로바이더 또는 의존성 누락),
 * [PromptCachingService] 빈이 등록되지 않으며 호출자는 우아하게 폴백한다.
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
     * Anthropic 프롬프트 캐싱 서비스.
     *
     * Anthropic 프로바이더로 향하는 채팅 요청에
     * [org.springframework.ai.anthropic.AnthropicCacheStrategy]를 적용하고,
     * 응답에서 캐시 토큰 메트릭을 파싱한다.
     */
    @Bean
    @ConditionalOnMissingBean
    fun promptCachingService(properties: AgentProperties): PromptCachingService =
        AnthropicPromptCachingService(properties.llm.promptCaching)
}
