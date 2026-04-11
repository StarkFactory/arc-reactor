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
 * ## R267 + R268: 활성화 매트릭스 (R264/R265 패턴 + R268 production fix)
 *
 * 이 자동 구성은 3개의 빈을 등록하며, 각각의 활성화 조건이 미묘하게 다르다. R267에서
 * 활성화 매트릭스를 KDoc + ApplicationContextRunner 통합 테스트로 잠갔으며, R268에서
 * 6번째 행의 hard failure를 production code 변경으로 해결했다.
 *
 * | `enabled` | 사용자 커스텀 `ToolResponseSummarizer` 빈 | 결과 빈 종류 | Hook 등록 | 주의 |
 * |---|---|---|---|---|
 * | ❌ false (또는 미설정) | ❌ 없음 | [NoOpToolResponseSummarizer] | ❌ | 기본 fallback |
 * | ❌ false (또는 미설정) | ✅ Default 인스턴스 | 사용자 Default | ❌ | 사용자 빈만 활성, Hook 없음 |
 * | ❌ false (또는 미설정) | ✅ 다른 구현체 | 사용자 구현 | ❌ | 사용자 빈만 활성, Hook 없음 |
 * | ✅ true | ❌ 없음 | [DefaultToolResponseSummarizer] (Configuration 등록) | ✅ | 정상 활성 시나리오 |
 * | ✅ true | ✅ Default 인스턴스 | 사용자 Default | ✅ | Configuration 빈 미등록 (사용자 우선) |
 * | ✅ true | ✅ 다른 구현체 | **사용자 구현 (R268 fix)** ✅ | ✅ | **R268 이후 silent 위험 해소** |
 *
 * ### R268 fix: 6번째 행 silent foot-gun 해결
 *
 * **R267 이전 상태**: `defaultToolResponseSummarizer` 빈이 `@ConditionalOnMissingBean(DefaultToolResponseSummarizer::class)`
 * (구체 클래스)였다. 사용자가 자체 구현체(예: `class MyCustomSummarizer : ToolResponseSummarizer`)를
 * 제공하면 이 조건이 false가 되지 않아 `DefaultToolResponseSummarizer` 빈도 함께 등록되어
 * `ToolResponseSummarizer` 타입 빈이 2개가 됐다. [ToolResponseSummarizerHook] 주입 시
 * `NoUniqueBeanDefinitionException` → 컨텍스트 기동 실패. R267 작성 중 통합 테스트에서 처음
 * 발견됐고, R232 commit 이후 R267까지 어디에도 문서화/테스트되지 않은 silent foot-gun이었다.
 *
 * **R268 fix**: `@ConditionalOnMissingBean(ToolResponseSummarizer::class)`(인터페이스)로 변경.
 * 이제 사용자가 어떤 구현체든 등록하면 Configuration의 default 빈은 등록되지 않는다 →
 * 단일 빈 → Hook 정상 주입 → 컨텍스트 정상 기동.
 *
 * ### R232 PII Redaction과의 호환성 (R268 검증 완료)
 *
 * R232 [ToolResponseSummarizerPiiRedactionConfiguration]은 `@AutoConfiguration(after = [ToolResponseSummarizerConfiguration::class])`로
 * 평가 순서가 보장되므로, R232 PII Redaction이 활성화된 경우:
 *
 * 1. 사용자 빈(또는 Configuration default 빈) 먼저 등록 (1개)
 * 2. R232가 평가되며 첫 non-Redacted 베이스를 찾아 `RedactedToolResponseSummarizer`로 wrap
 *    하여 `@Primary`로 등록 (총 2개 빈, 그 중 Redacted가 @Primary)
 * 3. Hook 주입 시 `@Primary` 우선 → Redacted 사용
 *
 * R268 변경은 R232 wrapping 로직과 완전히 직교한다 — R232는 여전히 첫 non-Redacted 베이스를
 * 찾아 wrapping한다 (사용자 빈이든 Configuration default 빈이든 무관).
 *
 * ### `defaultToolResponseSummarizer`와 `noOpToolResponseSummarizer`의 관계 (R268 이후)
 *
 * R268 이후 두 빈 모두 `@ConditionalOnMissingBean(ToolResponseSummarizer::class)`(인터페이스)를
 * 사용하지만 평가 시점이 다르다:
 *
 * | 빈 | `@ConditionalOnProperty` | 효과 |
 * |---|---|---|
 * | `defaultToolResponseSummarizer` | `enabled=true` 필수 | enabled + 사용자 빈 없음 → 등록 |
 * | `noOpToolResponseSummarizer` | 없음 | 어떤 ToolResponseSummarizer도 없을 때만 등록 (fallback) |
 *
 * Spring은 두 조건을 독립적으로 평가하므로 enabled=true + 사용자 빈 없음 시:
 * 1. `defaultToolResponseSummarizer` 등록 (enabled 통과 + 빈 없음)
 * 2. `noOpToolResponseSummarizer` 미등록 (Default가 등록되어 인터페이스 빈 존재)
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
 * 사용자 커스텀 `@Bean ToolResponseSummarizer`를 제공하면 R268 fix 덕분에 추가 작업 없이
 * Hook 주입이 정상 동작한다 (R232 wrapping은 별도 구성).
 *
 * ## 변경 시 주의 (잠금 사항)
 *
 * 다음 변경은 활성화 매트릭스를 깬다 — 의도된 변경이라면 R267/R268 통합 테스트도 함께 갱신해야 한다:
 *
 * 1. `defaultToolResponseSummarizer`의 `@ConditionalOnMissingBean` 대상을 구체 클래스로 되돌리면
 *    R267 매트릭스 6행 hard failure가 재발 — R268 fix가 깨짐 (검증: R267 fixed test)
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
     *
     * R268 fix: 이전에는 `@ConditionalOnMissingBean(DefaultToolResponseSummarizer::class)`(구체 클래스)였으나,
     * 이는 사용자가 비-Default 구현체를 등록한 경우에도 default 빈을 함께 등록하여 다중 빈 후보 → Hook
     * 주입 시 `NoUniqueBeanDefinitionException` → 컨텍스트 기동 실패의 silent foot-gun을 만들었다.
     * R267에서 통합 테스트로 hard failure가 발견되어 R268에서 인터페이스 검사로 변경: 사용자가 어떤
     * `ToolResponseSummarizer` 구현체든 등록한 경우 default 빈을 등록하지 않는다.
     *
     * R232 PII Redaction 호환성: R232는 `@AutoConfiguration(after = [ToolResponseSummarizerConfiguration::class])`로
     * 평가 순서가 보장되므로, default 빈이 먼저 등록된 후 R232가 그 위를 wrapping한다. Option B 변경은
     * R232의 wrapping 로직과 직교한다 — R232는 여전히 첫 non-Redacted 베이스를 찾아 wrapping한다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "arc.reactor.tool.response.summarizer",
        name = ["enabled"],
        havingValue = "true"
    )
    @ConditionalOnMissingBean(ToolResponseSummarizer::class)
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
