package com.arc.reactor.autoconfigure

import com.arc.reactor.approval.ApprovalContextResolver
import com.arc.reactor.approval.RedactedApprovalContextResolver
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * R229 [RedactedApprovalContextResolver] 자동 구성 — opt-in.
 *
 * R228에서 도입한 PII 마스킹 데코레이터를 속성 하나로 자동 적용한다.
 * `arc.reactor.approval.pii-redaction.enabled=true`를 설정하면, 기존에 등록된
 * [ApprovalContextResolver] 빈(R225/R226/R227 기반 또는 사용자 커스텀)을 자동으로
 * `RedactedApprovalContextResolver`로 감싸서 `@Primary`로 등록한다.
 *
 * ## 동작 원리
 *
 * 1. Spring은 기존 베이스 리졸버(예: [AtlassianApprovalResolverConfiguration]의 chain 또는
 *    단독 리졸버, 또는 사용자 `@Bean`)를 먼저 등록한다
 * 2. 이 구성은 `@AutoConfigureAfter(AtlassianApprovalResolverConfiguration::class)`로
 *    뒤에 평가되며, `@ConditionalOnBean(ApprovalContextResolver::class)`으로 베이스 빈의
 *    존재를 요구한다
 * 3. `pii-redaction.enabled=true` 일 때 [redactedApprovalContextResolver]가 실행되어
 *    첫 번째 non-redacted 베이스 리졸버를 찾아 감싼다
 * 4. 반환된 빈은 `@Primary`로 등록되어, R221의
 *    `ObjectProvider<ApprovalContextResolver>.ifAvailable`이 래핑된 버전을 주입받는다
 *
 * ## 활성화 예
 *
 * ```yaml
 * arc:
 *   reactor:
 *     approval:
 *       atlassian-resolver:
 *         enabled: true
 *       heuristic-fallback:
 *         enabled: true
 *       pii-redaction:
 *         enabled: true  # R229 신규
 * ```
 *
 * 위 설정 결과:
 * 1. R225/R227이 `ChainedApprovalContextResolver(Atlassian + Heuristic)` 베이스 빈 등록
 * 2. R229가 이를 `RedactedApprovalContextResolver`로 감싸서 `@Primary`로 등록
 * 3. 실제 주입되는 빈: `Redacted(Chained(Atlassian, Heuristic))`
 *
 * ## @Primary 패턴 선택 이유
 *
 * 대안으로 `BeanPostProcessor`로 빈을 "교체"할 수도 있지만, `@Primary` 패턴은:
 * - 원본 베이스 빈도 컨텍스트에 그대로 남아 디버깅 가능
 * - 사용자가 원하면 `@Qualifier`로 원본 빈을 직접 주입받을 수도 있음
 * - Spring 표준 메커니즘이라 이해하기 쉽고 예측 가능
 *
 * ## Backward Compat
 *
 * - `pii-redaction.enabled` 미설정 또는 `false` → 이 구성은 빈을 등록하지 않음
 * - 베이스 리졸버 빈이 없으면 `@ConditionalOnBean`이 실패 → 빈 미등록
 * - 기존 R225/R226/R227/R228 동작 완전 유지
 *
 * ## 3대 최상위 제약 준수
 *
 * - MCP: 도구 인수/응답 경로 전혀 미수정
 * - Redis 캐시: `systemPrompt` 미수정
 * - 컨텍스트 관리: `MemoryStore`/`Trimmer` 미수정
 *
 * @see RedactedApprovalContextResolver R228 PII 마스킹 데코레이터
 * @see AtlassianApprovalResolverConfiguration R225/R227 베이스 리졸버 자동 구성
 */
@AutoConfiguration(after = [AtlassianApprovalResolverConfiguration::class])
class ApprovalPiiRedactionConfiguration {

    /**
     * 기존 [ApprovalContextResolver] 베이스 빈을 [RedactedApprovalContextResolver]로 감싼
     * `@Primary` 빈을 등록한다.
     *
     * `baseResolvers` 파라미터는 컨텍스트에 이미 등록된 모든 [ApprovalContextResolver] 빈을
     * 받는다. 이 메서드가 실행되는 시점에는 베이스 빈만 존재하고 Redacted 빈은 아직 생성되지
     * 않았으므로, 자기 자신을 재귀적으로 감싸는 상황은 발생하지 않는다. 그러나 안전을 위해
     * `RedactedApprovalContextResolver` 타입은 명시적으로 필터링한다.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(
        prefix = "arc.reactor.approval.pii-redaction",
        name = ["enabled"],
        havingValue = "true"
    )
    @ConditionalOnBean(ApprovalContextResolver::class)
    fun redactedApprovalContextResolver(
        baseResolvers: List<ApprovalContextResolver>
    ): ApprovalContextResolver {
        val base = baseResolvers.firstOrNull { it !is RedactedApprovalContextResolver }
            ?: throw IllegalStateException(
                "PII redaction 활성화(pii-redaction.enabled=true)되었지만 " +
                    "non-redacted ApprovalContextResolver 베이스 빈이 없습니다. " +
                    "atlassian-resolver.enabled=true 또는 사용자 커스텀 @Bean을 먼저 등록하세요."
            )
        return RedactedApprovalContextResolver(base)
    }
}
