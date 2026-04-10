package com.arc.reactor.autoconfigure

import com.arc.reactor.approval.ApprovalContextResolver
import com.arc.reactor.approval.AtlassianApprovalContextResolver
import com.arc.reactor.approval.ChainedApprovalContextResolver
import com.arc.reactor.approval.HeuristicApprovalContextResolver
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * R225/R227 atlassian-mcp-server 승인 리졸버 자동 구성.
 *
 * ## 활성화 조합 (우선순위 순)
 *
 * ### 조합 A — Atlassian + Heuristic 체인 (R227 권장 프로덕션 기본값)
 *
 * ```yaml
 * arc:
 *   reactor:
 *     approval:
 *       atlassian-resolver:
 *         enabled: true
 *       heuristic-fallback:
 *         enabled: true
 * ```
 *
 * 두 속성이 모두 `true`일 때, [ChainedApprovalContextResolver]가 자동으로 주입된다:
 * 1. [AtlassianApprovalContextResolver] (1순위) — `jira_*`/`confluence_*`/`bitbucket_*` 도구
 * 2. [HeuristicApprovalContextResolver] (2순위) — 그 외 모든 도구 (`delete_*`, `create_*` 등)
 *
 * R226 fail-open 동작으로 Atlassian resolver가 예외를 던져도 Heuristic fallback이 처리.
 *
 * ### 조합 B — Atlassian 단독 (R225 기존 동작)
 *
 * ```yaml
 * arc:
 *   reactor:
 *     approval:
 *       atlassian-resolver:
 *         enabled: true
 * ```
 *
 * `heuristic-fallback.enabled`가 설정되지 않았거나 `false`일 때, 기존 R225 동작 유지:
 * [AtlassianApprovalContextResolver] 단독 주입. atlassian-mcp-server 외 도구는 enrichment 없음.
 *
 * ### 조합 C — 사용자 커스텀 빈 (최우선)
 *
 * 사용자가 `@Bean ApprovalContextResolver`를 직접 등록하면 위 조합 A/B 모두 양보한다
 * (`@ConditionalOnMissingBean(ApprovalContextResolver::class)`).
 *
 * ### 조합 D — 비활성 (기본값)
 *
 * 두 속성이 모두 설정되지 않거나 `false`인 경우. 자동 구성은 아무 빈도 등록하지 않으며,
 * R221의 `ObjectProvider<ApprovalContextResolver>.ifAvailable`가 null을 반환한다 →
 * approval enrichment 없음 (기존 R221 동작).
 *
 * ## 빈 등록 평가 순서
 *
 * Spring은 같은 `@AutoConfiguration` 클래스 내의 `@Bean` 메서드를 선언 순서대로 평가한다.
 * 이 클래스는 다음 순서로 빈을 시도한다:
 *
 * 1. [approvalContextResolverChain] — 양쪽 속성 모두 true일 때 우선 등록
 * 2. [atlassianApprovalContextResolver] — 1이 등록되지 않았고 atlassian만 true일 때 등록
 *
 * 두 메서드 모두 `@ConditionalOnMissingBean(ApprovalContextResolver::class)`를 가지므로
 * 먼저 등록된 빈이 있으면 후속은 건너뛴다.
 *
 * ## MCP 호환성
 *
 * 리졸버는 atlassian-mcp-server 응답/인수를 전혀 수정하지 않는다. 오직 인수 맵에서
 * 읽어 메타데이터로 가공만 한다. R225/R226와 동일.
 *
 * @see AtlassianApprovalContextResolver R225 atlassian-mcp-server 전용 리졸버
 * @see HeuristicApprovalContextResolver R221 이름 기반 일반 휴리스틱
 * @see ChainedApprovalContextResolver R226 조합 유틸
 */
@AutoConfiguration
class AtlassianApprovalResolverConfiguration {

    /**
     * R227: Atlassian + Heuristic 체인 — 양쪽 속성이 모두 true일 때 등록.
     *
     * `@ConditionalOnProperty`는 `name` 리스트의 **모든** 속성이 `havingValue`와 일치해야
     * 활성화된다. 따라서 이 빈은 두 속성이 모두 `true`일 때만 등록된다.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "arc.reactor.approval",
        name = ["atlassian-resolver.enabled", "heuristic-fallback.enabled"],
        havingValue = "true"
    )
    @ConditionalOnMissingBean(ApprovalContextResolver::class)
    fun approvalContextResolverChain(): ApprovalContextResolver =
        ChainedApprovalContextResolver(
            AtlassianApprovalContextResolver(),
            HeuristicApprovalContextResolver()
        )

    /**
     * R225: atlassian-mcp-server 전용 리졸버 — 단독 활성화 경로.
     *
     * `atlassian-resolver.enabled=true`이지만 `heuristic-fallback.enabled`가 설정되지
     * 않았거나 `false`인 경우에만 등록된다 (chain 빈이 먼저 시도되고 실패한 뒤 이 빈이 차례).
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "arc.reactor.approval.atlassian-resolver",
        name = ["enabled"],
        havingValue = "true"
    )
    @ConditionalOnMissingBean(ApprovalContextResolver::class)
    fun atlassianApprovalContextResolver(): ApprovalContextResolver =
        AtlassianApprovalContextResolver()
}
