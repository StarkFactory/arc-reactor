package com.arc.reactor.autoconfigure

import com.arc.reactor.approval.ApprovalContextResolver
import com.arc.reactor.approval.AtlassianApprovalContextResolver
import com.arc.reactor.approval.ChainedApprovalContextResolver
import com.arc.reactor.approval.HeuristicApprovalContextResolver
import com.arc.reactor.approval.Reversibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * [AtlassianApprovalResolverConfiguration]의 @ConditionalOnProperty 조합 동작 검증.
 *
 * R225(Atlassian 단독) + R227(Atlassian + Heuristic 체인)의 4가지 활성화 조합을
 * `ApplicationContextRunner`로 실제 스프링 컨텍스트에서 검증한다.
 */
class AtlassianApprovalResolverConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(AtlassianApprovalResolverConfiguration::class.java)
        )

    @Nested
    inner class CombinationA_ChainBoth {

        @Test
        fun `양쪽 속성 true이면 ChainedApprovalContextResolver가 등록되어야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.heuristic-fallback.enabled=true"
                )
                .run { context ->
                    val resolver = context.getBean(ApprovalContextResolver::class.java)
                    assertNotNull(resolver) { "ApprovalContextResolver 빈이 등록되어야 한다" }
                    assertInstanceOf(
                        ChainedApprovalContextResolver::class.java,
                        resolver
                    ) { "체인 리졸버가 주입되어야 한다" }
                    val chain = resolver as ChainedApprovalContextResolver
                    assertEquals(2, chain.size()) { "체인은 2개 리졸버를 포함해야 한다" }
                    assertFalse(chain.isEmpty()) { "빈 체인이 아니어야 한다" }
                }
        }

        @Test
        fun `체인 리졸버는 Atlassian 도구를 우선 처리해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.heuristic-fallback.enabled=true"
                )
                .run { context ->
                    val resolver = context.getBean(ApprovalContextResolver::class.java)
                    val ctx = resolver.resolve("jira_get_issue", mapOf("issueKey" to "HRFW-42"))
                    assertNotNull(ctx) { "Jira 도구는 컨텍스트를 받아야 한다" }
                    assertEquals("HRFW-42", ctx!!.impactScope) {
                        "AtlassianResolver가 issueKey를 impactScope로 추출"
                    }
                    assertTrue(ctx.reason?.contains("Jira") == true) {
                        "Atlassian resolver의 reason 사용"
                    }
                    assertEquals(Reversibility.REVERSIBLE, ctx.reversibility)
                }
        }

        @Test
        fun `체인 리졸버는 atlassian 외 도구에 Heuristic fallback을 적용해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.heuristic-fallback.enabled=true"
                )
                .run { context ->
                    val resolver = context.getBean(ApprovalContextResolver::class.java)
                    val ctx = resolver.resolve("delete_order", mapOf("orderId" to "42"))
                    assertNotNull(ctx) { "delete_order도 처리되어야 한다 (Heuristic)" }
                    assertEquals(Reversibility.IRREVERSIBLE, ctx!!.reversibility) {
                        "delete_* 는 Heuristic이 IRREVERSIBLE로 분류"
                    }
                }
        }
    }

    @Nested
    inner class CombinationB_AtlassianOnly {

        @Test
        fun `atlassian-resolver만 true이면 AtlassianApprovalContextResolver 단독 등록`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true"
                )
                .run { context ->
                    val resolver = context.getBean(ApprovalContextResolver::class.java)
                    assertNotNull(resolver) { "빈이 등록되어야 한다" }
                    assertInstanceOf(
                        AtlassianApprovalContextResolver::class.java,
                        resolver
                    ) { "Atlassian 단독 리졸버가 주입되어야 한다 (체인 아님)" }
                }
        }

        @Test
        fun `heuristic-fallback이 false이면 체인이 아닌 단독 리졸버가 등록되어야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.heuristic-fallback.enabled=false"
                )
                .run { context ->
                    val resolver = context.getBean(ApprovalContextResolver::class.java)
                    assertInstanceOf(
                        AtlassianApprovalContextResolver::class.java,
                        resolver
                    ) { "heuristic-fallback=false이므로 단독 리졸버" }
                }
        }

        @Test
        fun `단독 리졸버는 atlassian 외 도구를 처리하지 않아야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true"
                )
                .run { context ->
                    val resolver = context.getBean(ApprovalContextResolver::class.java)
                    // delete_order는 atlassian 아니므로 AtlassianResolver가 null 반환
                    val ctx = resolver.resolve("delete_order", mapOf("orderId" to "42"))
                    assertEquals(null, ctx) {
                        "Atlassian 단독 리졸버는 atlassian 외 도구에 null을 반환해야 한다"
                    }
                }
        }
    }

    @Nested
    inner class CombinationC_UserBeanOverride {

        @Test
        fun `사용자 커스텀 빈이 있으면 atlassian 속성이 true여도 양보해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true"
                )
                .withUserConfiguration(CustomUserResolverConfig::class.java)
                .run { context ->
                    val resolver = context.getBean(ApprovalContextResolver::class.java)
                    assertInstanceOf(
                        CustomUserResolver::class.java,
                        resolver
                    ) {
                        "사용자 커스텀 빈이 우선해야 한다"
                    }
                }
        }

        @Test
        fun `사용자 커스텀 빈이 있으면 양쪽 속성이 true여도 양보해야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.heuristic-fallback.enabled=true"
                )
                .withUserConfiguration(CustomUserResolverConfig::class.java)
                .run { context ->
                    val resolver = context.getBean(ApprovalContextResolver::class.java)
                    assertInstanceOf(
                        CustomUserResolver::class.java,
                        resolver
                    ) {
                        "사용자 커스텀 빈이 체인보다 우선해야 한다"
                    }
                }
        }
    }

    @Nested
    inner class CombinationD_Disabled {

        @Test
        fun `속성 없으면 빈이 등록되지 않아야 한다`() {
            contextRunner.run { context ->
                val beans = context.getBeansOfType(ApprovalContextResolver::class.java)
                assertTrue(beans.isEmpty()) {
                    "속성 미설정 시 빈이 등록되지 않아야 한다"
                }
            }
        }

        @Test
        fun `atlassian-resolver가 false이면 빈이 등록되지 않아야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=false"
                )
                .run { context ->
                    val beans = context.getBeansOfType(ApprovalContextResolver::class.java)
                    assertTrue(beans.isEmpty()) {
                        "atlassian-resolver=false이면 단독 빈도 등록 안 됨"
                    }
                }
        }

        @Test
        fun `heuristic-fallback만 true이고 atlassian이 false이면 빈이 등록되지 않아야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=false",
                    "arc.reactor.approval.heuristic-fallback.enabled=true"
                )
                .run { context ->
                    val beans = context.getBeansOfType(ApprovalContextResolver::class.java)
                    assertTrue(beans.isEmpty()) {
                        "atlassian=false이면 체인/단독 둘 다 등록 안 됨"
                    }
                }
        }

        @Test
        fun `heuristic-fallback만 true여도 atlassian 설정 없이는 활성화되지 않아야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.heuristic-fallback.enabled=true"
                )
                .run { context ->
                    val beans = context.getBeansOfType(ApprovalContextResolver::class.java)
                    assertTrue(beans.isEmpty()) {
                        "atlassian 속성이 없으면 chain 조건이 충족되지 않음"
                    }
                }
        }
    }

    @Nested
    inner class SingletonInstance {

        @Test
        fun `체인 리졸버는 단일 인스턴스여야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true",
                    "arc.reactor.approval.heuristic-fallback.enabled=true"
                )
                .run { context ->
                    val beans = context.getBeansOfType(ApprovalContextResolver::class.java)
                    assertEquals(1, beans.size) { "정확히 1개의 리졸버 빈" }
                }
        }

        @Test
        fun `단독 리졸버도 단일 인스턴스여야 한다`() {
            contextRunner
                .withPropertyValues(
                    "arc.reactor.approval.atlassian-resolver.enabled=true"
                )
                .run { context ->
                    val beans = context.getBeansOfType(ApprovalContextResolver::class.java)
                    assertEquals(1, beans.size) { "정확히 1개의 리졸버 빈" }
                }
        }
    }

    /** 테스트용 사용자 정의 리졸버 (커스텀 빈 우선순위 검증용). */
    class CustomUserResolver : ApprovalContextResolver {
        override fun resolve(toolName: String, arguments: Map<String, Any?>) = null
    }

    /** 사용자 커스텀 빈 주입용 테스트 구성. */
    @Configuration
    class CustomUserResolverConfig {
        @Bean
        fun customResolver(): ApprovalContextResolver = CustomUserResolver()
    }
}
