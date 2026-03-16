package com.arc.reactor.cache

import org.springframework.ai.chat.prompt.ChatOptions

/**
 * Anthropic 프롬프트 캐싱을 LLM 요청에 적용하는 서비스.
 *
 * 시스템 프롬프트 및/또는 도구 정의에 Anthropic의
 * `cache_control: {"type": "ephemeral"}` 지시어를 표시하여
 * 반복되는 콘텐츠가 재처리 대신 Anthropic 캐시에서 제공되도록 한다.
 *
 * 같은 시스템 프롬프트(예: 회사 정책, 조직도)가 매 요청마다 전송되는
 * 엔터프라이즈 워크로드에서 프롬프트 토큰 비용을 80-90% 절감할 수 있다.
 *
 * ## 프로바이더 지원
 * `anthropic` 프로바이더만 지원된다. 다른 프로바이더의 호출은 변경 없이 통과한다.
 *
 * ## 최소 토큰 임계값
 * Anthropic은 캐싱이 유효하려면 최소 토큰 수가 필요하다.
 * [PromptCachingProperties.minCacheableTokens] 참고.
 */
interface PromptCachingService {

    /**
     * 주어진 [ChatOptions]에 Anthropic 캐싱 옵션을 적용한다.
     *
     * 프로바이더가 `anthropic`이고 캐싱이 활성화되면, 설정에 따라
     * 시스템 프롬프트 및/또는 도구 정의에 캐시 지시어가 적용된 새
     * [ChatOptions] 인스턴스를 반환한다.
     *
     * @param options 데코레이트할 기본 Chat 옵션
     * @param provider LLM 프로바이더 이름 (예: "anthropic", "gemini")
     * @param estimatedSystemPromptTokens 시스템 프롬프트의 추정 토큰 수
     * @return 캐시 지시어가 적용된 옵션, 또는 변경 없는 원본 옵션
     */
    fun applyCaching(
        options: ChatOptions,
        provider: String,
        estimatedSystemPromptTokens: Int
    ): ChatOptions

    /**
     * Anthropic API가 반환하는 원시 사용량 객체에서 캐시 사용 메트릭을 추출한다.
     *
     * @param nativeUsage [ChatResponseMetadata.usage.nativeUsage]의 네이티브 사용량 객체
     * @return 파싱된 캐시 메트릭, 또는 Anthropic 사용량 타입이 아닌 경우 null
     */
    fun extractCacheMetrics(nativeUsage: Any?): PromptCacheMetrics?
}

/**
 * Anthropic 프롬프트 캐싱의 토큰 사용량 분석.
 *
 * @param cacheCreationInputTokens 이 요청에서 캐시에 기록된 토큰 (1.25배 과금)
 * @param cacheReadInputTokens 이 요청에서 캐시에서 제공된 토큰 (0.1배 과금)
 * @param regularInputTokens 캐싱되지 않은 토큰
 */
data class PromptCacheMetrics(
    val cacheCreationInputTokens: Int = 0,
    val cacheReadInputTokens: Int = 0,
    val regularInputTokens: Int = 0
)
