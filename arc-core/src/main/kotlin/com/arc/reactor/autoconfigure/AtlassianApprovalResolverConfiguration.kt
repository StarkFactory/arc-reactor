package com.arc.reactor.autoconfigure

import com.arc.reactor.approval.ApprovalContextResolver
import com.arc.reactor.approval.AtlassianApprovalContextResolver
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean

/**
 * R225 [AtlassianApprovalContextResolver] 자동 구성 — opt-in.
 *
 * ## 활성화 방법
 *
 * `application.yml`:
 *
 * ```yaml
 * arc:
 *   reactor:
 *     approval:
 *       atlassian-resolver:
 *         enabled: true
 * ```
 *
 * 이 속성이 `true`일 때, 사용자가 직접 `@Bean ApprovalContextResolver`를 등록하지
 * 않았다면 [AtlassianApprovalContextResolver]가 자동으로 주입된다. 사용자가 커스텀
 * 리졸버를 등록했다면 그것이 우선한다 (`@ConditionalOnMissingBean`).
 *
 * ## 동작 순서
 *
 * 1. 사용자가 `@Bean ApprovalContextResolver` 등록 → 사용자 빈 사용
 * 2. 없음 + `atlassian-resolver.enabled=true` → [AtlassianApprovalContextResolver] 사용
 * 3. 그 외 → R221의 `ObjectProvider<ApprovalContextResolver>.ifAvailable`가 null → enrichment 없음
 *
 * ## MCP 호환성
 *
 * 리졸버는 atlassian-mcp-server 응답/인수를 전혀 수정하지 않는다. 오직 인수 맵에서
 * 읽어 메타데이터로 가공만 한다.
 *
 * @see AtlassianApprovalContextResolver
 * @see ApprovalContextResolver
 */
@AutoConfiguration
class AtlassianApprovalResolverConfiguration {

    /**
     * atlassian-mcp-server 전용 리졸버 — 속성이 true이고 사용자 빈이 없을 때만 등록.
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
