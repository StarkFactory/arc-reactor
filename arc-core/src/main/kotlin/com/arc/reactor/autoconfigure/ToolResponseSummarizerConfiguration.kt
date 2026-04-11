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
 * ## R267: 활성화 매트릭스 (R264/R265 패턴 확장)
 *
 * 이 자동 구성은 3개의 빈을 등록하며, 각각의 활성화 조건이 미묘하게 다르다. R267에서
 * 활성화 매트릭스를 KDoc에 명시하고 [ApplicationContextRunner](Spring) 통합 테스트로 잠근다.
 *
 * | `enabled` | 사용자 커스텀 `ToolResponseSummarizer` 빈 | 결과 빈 종류 | Hook 등록 | 주의 |
 * |---|---|---|---|---|
 * | ❌ false (또는 미설정) | ❌ 없음 | [NoOpToolResponseSummarizer] | ❌ | 기본 fallback |
 * | ❌ false (또는 미설정) | ✅ `DefaultToolResponseSummarizer` | 사용자 Default | ❌ | 사용자 빈만 활성, Hook 없음 |
 * | ❌ false (또는 미설정) | ✅ 다른 구현체 | 사용자 구현 | ❌ | 사용자 빈만 활성, Hook 없음 |
 * | ✅ true | ❌ 없음 | [DefaultToolResponseSummarizer] (Configuration 등록) | ✅ | 정상 활성 시나리오 |
 * | ✅ true | ✅ `DefaultToolResponseSummarizer` | 사용자 Default | ✅ | Configuration 빈 미등록 (사용자 우선) |
 * | ✅ true | ✅ 다른 구현체 | **❌ 컨텍스트 기동 실패** | (Hook이 못 만들어짐) | **R267 발견: hard failure** |
 *
 * ### ❌ 6번째 행: 컨텍스트 기동 실패 (R267에서 발견)
 *
 * `defaultToolResponseSummarizer` 빈은 `@ConditionalOnMissingBean(DefaultToolResponseSummarizer::class)`
 * 조건만 가진다. **이는 인터페이스가 아닌 구체 클래스 [DefaultToolResponseSummarizer]를 검사**한다.
 * 즉 사용자가 자체 구현체(예: `MyCustomSummarizer : ToolResponseSummarizer`)를 제공하면 이
 * 조건이 false가 되지 않으므로 `DefaultToolResponseSummarizer` 빈도 함께 등록된다.
 *
 * 결과적으로 컨텍스트에는 [ToolResponseSummarizer] 타입의 빈이 2개 존재한다 — 사용자 구현체와
 * `DefaultToolResponseSummarizer`. [ToolResponseSummarizerHook]이 `summarizer: ToolResponseSummarizer`를
 * 주입 받을 때 `@Primary` 빈이 없으면 Spring은 `NoUniqueBeanDefinitionException`을 던지고
 * **컨텍스트 자체가 기동 실패**한다.
 *
 * R267 작성 중 R267EvaluationMetricsContextIntegration test에서 이 동작이 처음 명시적으로
 * 발견됐다. R267 이전에는 KDoc/테스트 모두에 명시되지 않은 silent foot-gun이었다.
 *
 * **해결 방법**:
 * 1. 사용자 빈에 `@Primary` 추가 — Hook이 사용자 빈을 자동 선택
 * 2. R232 [ToolResponseSummarizerPiiRedactionConfiguration] 활성화 — Redacted 래핑이 `@Primary`를 부여
 * 3. 사용자 빈을 `DefaultToolResponseSummarizer` 서브클래스로 만들어 `@ConditionalOnMissingBean`이 true
 *
 * **R232 역사적 맥락**:
 * R232 이전에는 `defaultToolResponseSummarizer`가 `@Primary`였으나, R232의 PII Redaction 자동 구성이
 * 자체 `@Primary` Redacted 래핑 빈을 등록하면서 충돌을 일으켰다. R232에서 `@Primary`를 제거했고,
 * `noOpToolResponseSummarizer`가 `@ConditionalOnMissingBean(ToolResponseSummarizer::class)` 조건으로
 * Default와 공존하지 않으므로(아래 노트 참조) Default가 유일한 빈으로 주입되어 `@Primary`가 불필요했다.
 *
 * ### `noOpToolResponseSummarizer`의 인터페이스 vs 구체 클래스 차이
 *
 * `noOpToolResponseSummarizer` 빈은 `@ConditionalOnMissingBean(ToolResponseSummarizer::class)` (인터페이스)를
 * 사용한다. 즉 어떤 [ToolResponseSummarizer] 구현체도 없을 때만 등록된다 — 이는 의도된 동작으로
 * "어떤 경우든 요약기 빈이 존재하도록 보장"하는 fallback 역할이다.
 *
 * 두 빈의 `@ConditionalOnMissingBean` 차이를 정리하면:
 *
 * | 빈 | `@ConditionalOnMissingBean` 검사 대상 | 효과 |
 * |---|---|---|
 * | `defaultToolResponseSummarizer` | `DefaultToolResponseSummarizer::class` (구체) | 사용자가 Default 서브클래스를 제공하지 않을 때만 등록 |
 * | `noOpToolResponseSummarizer` | `ToolResponseSummarizer::class` (인터페이스) | 다른 어떤 구현체도 없을 때만 등록 |
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
 * - [DefaultToolResponseSummarizer]가 등록되어 no-op을 대체 (Default 서브클래스가 없는 경우)
 * - [ToolResponseSummarizerHook]이 `AfterToolCallHook` 체인에 등록
 *
 * 사용자 커스텀 `@Bean ToolResponseSummarizer`를 제공하면 매트릭스의 6번째 행을 주의해서
 * `@Primary`를 함께 사용하거나 R232 PII Redaction 자동 구성을 활성화한다.
 *
 * ## 변경 시 주의 (잠금 사항)
 *
 * 다음 변경은 활성화 매트릭스를 깬다 — 의도된 변경이라면 R267 통합 테스트도 함께 갱신해야 한다:
 *
 * 1. `defaultToolResponseSummarizer`의 `@ConditionalOnMissingBean` 대상을 인터페이스로 바꾸면
 *    매트릭스 6번째 행이 사라지지만 R232 PII Redaction과 충돌 가능
 * 2. `defaultToolResponseSummarizer`에 `@Primary` 재추가하면 R232 PII Redaction 자동 wrap이 깨짐
 *    (R232 commit 이전 상태로 돌아감)
 * 3. `toolResponseSummarizerHook`에서 `@Primary`가 아닌 다중 빈 주입 시 명시적 fallback 제공
 *
 * ## 3대 최상위 제약 준수
 *
 * - MCP: 도구 응답 경로 전혀 미수정, 요약은 메타데이터에만 저장
 * - Redis 캐시: `systemPrompt` 미수정 → scopeFingerprint 불변
 * - 컨텍스트 관리: `MemoryStore`/`Trimmer` 미수정, 대화 이력에 요약 섞이지 않음
 *
 * @see ToolResponseSummarizerPiiRedactionConfiguration R232 PII 마스킹 자동 wrap (Redacted @Primary 등록)
 * @see EvaluationMetricsConfiguration R264 활성화 매트릭스 (자매 패턴)
 * @see ArcReactorSemanticCacheConfiguration R265 활성화 매트릭스 (자매 패턴)
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
