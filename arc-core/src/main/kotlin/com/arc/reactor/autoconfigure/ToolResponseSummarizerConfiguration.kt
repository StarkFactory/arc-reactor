package com.arc.reactor.autoconfigure

import com.arc.reactor.tool.summarize.DefaultToolResponseSummarizer
import com.arc.reactor.tool.summarize.NoOpToolResponseSummarizer
import com.arc.reactor.tool.summarize.ToolResponseSummarizer
import com.arc.reactor.tool.summarize.ToolResponseSummarizerConfig
import com.arc.reactor.tool.summarize.ToolResponseSummarizerHook
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * R223 Directive #2 Agent-Computer Interface(ACI) 도구 출력 요약 계층 자동 설정.
 *
 * ## 기본 동작 (opt-in 미활성)
 *
 * - [ToolResponseSummarizer]: [NoOpToolResponseSummarizer] 주입 (오버헤드 0)
 * - [ToolResponseSummarizerHook]: 등록되지 않음
 *
 * ## 활성화 방법
 *
 * `application.yml`:
 *
 * ```yaml
 * arc:
 *   reactor:
 *     tool:
 *       response:
 *         summarizer:
 *           enabled: true
 * ```
 *
 * 이 속성이 `true`일 때:
 * - [DefaultToolResponseSummarizer]가 `@Primary`로 no-op을 대체
 * - [ToolResponseSummarizerHook]이 `AfterToolCallHook` 체인에 등록
 *
 * 사용자 커스텀 `@Bean ToolResponseSummarizer`를 제공하면 기본값을 대체한다.
 *
 * ## 3대 최상위 제약 준수
 *
 * - MCP: 도구 응답 경로 전혀 미수정, 요약은 메타데이터에만 저장
 * - Redis 캐시: `systemPrompt` 미수정 → scopeFingerprint 불변
 * - 컨텍스트 관리: `MemoryStore`/`Trimmer` 미수정, 대화 이력에 요약 섞이지 않음
 */
@AutoConfiguration
class ToolResponseSummarizerConfiguration {

    /**
     * 기본 휴리스틱 요약기 — `enabled=true` 일 때만 no-op을 대체.
     *
     * R232 이전에는 `@Primary`가 붙어있었지만, R232 `ToolResponseSummarizerPiiRedactionConfiguration`이
     * 래핑한 `@Primary` Redacted 빈과 충돌을 일으켰다. `noOpToolResponseSummarizer`는
     * `@ConditionalOnMissingBean(ToolResponseSummarizer::class)`로 Default와 공존하지 않으므로
     * @Primary 없이도 유일한 bean으로 주입된다. @Primary 제거는 안전하며 R232 래핑을 가능하게 한다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "arc.reactor.tool.response.summarizer",
        name = ["enabled"],
        havingValue = "true"
    )
    @ConditionalOnMissingBean(DefaultToolResponseSummarizer::class)
    fun defaultToolResponseSummarizer(): ToolResponseSummarizer =
        DefaultToolResponseSummarizer(ToolResponseSummarizerConfig())

    /**
     * 기본 no-op 요약기 — 어떤 경우든 요약기 빈이 존재하도록 보장한다.
     */
    @Bean
    @ConditionalOnMissingBean(ToolResponseSummarizer::class)
    fun noOpToolResponseSummarizer(): ToolResponseSummarizer =
        NoOpToolResponseSummarizer

    /**
     * 요약 Hook — `enabled=true` 일 때만 AfterToolCall 체인에 등록된다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "arc.reactor.tool.response.summarizer",
        name = ["enabled"],
        havingValue = "true"
    )
    @ConditionalOnMissingBean(ToolResponseSummarizerHook::class)
    fun toolResponseSummarizerHook(
        summarizer: ToolResponseSummarizer
    ): ToolResponseSummarizerHook = ToolResponseSummarizerHook(summarizer)
}
