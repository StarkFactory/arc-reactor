package com.arc.reactor.autoconfigure

import com.arc.reactor.tool.summarize.RedactedToolResponseSummarizer
import com.arc.reactor.tool.summarize.ToolResponseSummarizer
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * R232 [RedactedToolResponseSummarizer] 자동 구성 — opt-in.
 *
 * R229 [ApprovalPiiRedactionConfiguration]와 **완전히 평행한 패턴**을 ACI 축에 적용한 것이다.
 * R231에서 도입한 PII 마스킹 데코레이터를 속성 하나로 자동 적용한다.
 *
 * `arc.reactor.tool.response.summarizer.pii-redaction.enabled=true`를 설정하면, 기존에
 * 등록된 [ToolResponseSummarizer] 빈(R223 Default/R230 Chained/사용자 커스텀)을 자동으로
 * `RedactedToolResponseSummarizer`로 감싸서 `@Primary`로 등록한다.
 *
 * ## 동작 원리
 *
 * 1. Spring은 기존 베이스 summarizer(예: [ToolResponseSummarizerConfiguration]의 Default
 *    또는 사용자 `@Bean`)를 먼저 등록한다
 * 2. 이 구성은 `@AutoConfigureAfter(ToolResponseSummarizerConfiguration::class)`로 뒤에
 *    평가되며, `@ConditionalOnBean(ToolResponseSummarizer::class)`으로 베이스 빈의 존재를
 *    요구한다
 * 3. `pii-redaction.enabled=true` 일 때 [redactedToolResponseSummarizer]가 실행되어 첫
 *    번째 non-redacted 베이스 summarizer를 찾아 감싼다
 * 4. 반환된 빈은 `@Primary`로 등록되어, `ToolResponseSummarizerHook` 등이 주입받을 때
 *    래핑된 버전을 사용한다
 *
 * ## 활성화 예
 *
 * ```yaml
 * arc:
 *   reactor:
 *     tool:
 *       response:
 *         summarizer:
 *           enabled: true
 *           pii-redaction:
 *             enabled: true  # R232 신규
 * ```
 *
 * 위 설정 결과:
 * 1. [ToolResponseSummarizerConfiguration]이 `DefaultToolResponseSummarizer` 등록
 * 2. R232가 이를 `RedactedToolResponseSummarizer`로 감싸 `@Primary`로 등록
 * 3. 실제 주입되는 빈: `Redacted(Default)`
 *
 * ## @Primary 패턴 선택 이유 (R229와 동일)
 *
 * - 원본 베이스 빈도 컨텍스트에 그대로 남아 디버깅 가능
 * - 사용자가 원하면 `@Qualifier`로 원본 빈을 직접 주입받을 수 있음
 * - Spring 표준 메커니즘이라 예측 가능
 *
 * ## Backward Compat
 *
 * - `pii-redaction.enabled` 미설정 또는 `false` → 이 구성은 빈을 등록하지 않음
 * - 베이스 summarizer 빈이 없으면 `@ConditionalOnBean`이 실패 → 빈 미등록
 * - 기존 R223/R230/R231 동작 완전 유지
 *
 * ## Self-Wrapping Prevention
 *
 * 사용자가 이미 `@Bean RedactedToolResponseSummarizer(...)`를 직접 등록한 상태에서
 * `pii-redaction.enabled=true`까지 설정하면, `List<ToolResponseSummarizer>`에는 Redacted
 * 인스턴스만 존재한다. 이 경우 [redactedToolResponseSummarizer]는 non-redacted 베이스를
 * 찾지 못하고 [IllegalStateException]을 던져 명확한 에러 메시지로 사용자에게 알린다.
 *
 * ## 3대 최상위 제약 준수
 *
 * - MCP: 도구 인수/응답 경로 전혀 미수정
 * - Redis 캐시: `systemPrompt` 미수정
 * - 컨텍스트 관리: `MemoryStore`/`Trimmer` 미수정
 *
 * @see RedactedToolResponseSummarizer R231 PII 마스킹 데코레이터
 * @see ToolResponseSummarizerConfiguration R223 베이스 summarizer 자동 구성
 * @see ApprovalPiiRedactionConfiguration R229 동일 패턴의 Approval 축 버전
 */
@AutoConfiguration(after = [ToolResponseSummarizerConfiguration::class])
class ToolResponseSummarizerPiiRedactionConfiguration {

    /**
     * 기존 [ToolResponseSummarizer] 베이스 빈을 [RedactedToolResponseSummarizer]로 감싼
     * `@Primary` 빈을 등록한다.
     *
     * `baseSummarizers` 파라미터는 컨텍스트에 이미 등록된 모든 [ToolResponseSummarizer] 빈을
     * 받는다. 이 메서드가 실행되는 시점에는 베이스 빈만 존재하고 Redacted 빈은 아직 생성되지
     * 않았으므로, 자기 자신을 재귀적으로 감싸는 상황은 발생하지 않는다. 그러나 안전을 위해
     * `RedactedToolResponseSummarizer` 타입은 명시적으로 필터링한다.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(
        prefix = "arc.reactor.tool.response.summarizer.pii-redaction",
        name = ["enabled"],
        havingValue = "true"
    )
    @ConditionalOnBean(ToolResponseSummarizer::class)
    fun redactedToolResponseSummarizer(
        baseSummarizers: List<ToolResponseSummarizer>
    ): ToolResponseSummarizer {
        val base = baseSummarizers.firstOrNull { it !is RedactedToolResponseSummarizer }
            ?: throw IllegalStateException(
                "Tool response summarizer PII redaction 활성화" +
                    "(arc.reactor.tool.response.summarizer.pii-redaction.enabled=true)" +
                    "되었지만 non-redacted ToolResponseSummarizer 베이스 빈이 없습니다. " +
                    "arc.reactor.tool.response.summarizer.enabled=true 또는 " +
                    "사용자 커스텀 @Bean을 먼저 등록하세요."
            )
        return RedactedToolResponseSummarizer(base)
    }
}
