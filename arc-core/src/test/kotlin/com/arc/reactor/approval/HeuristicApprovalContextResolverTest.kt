package com.arc.reactor.approval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [HeuristicApprovalContextResolver]의 분류 규칙 테스트.
 *
 * R221 Directive #1 Tool Approval 4단계 정보 구조화의 샘플 구현체 검증.
 * 도구 이름 prefix/keyword 에서 복구 가능성을 추정하는 휴리스틱을 테스트한다.
 */
class HeuristicApprovalContextResolverTest {

    private val resolver = HeuristicApprovalContextResolver()

    @Nested
    inner class ReversibilityClassification {

        @Test
        fun `delete prefix는 IRREVERSIBLE로 분류되어야 한다`() {
            assertEquals(
                Reversibility.IRREVERSIBLE,
                resolver.classifyReversibility("delete_order"),
                "delete_ prefix 도구는 IRREVERSIBLE로 분류되어야 한다"
            )
        }

        @Test
        fun `remove prefix는 IRREVERSIBLE로 분류되어야 한다`() {
            assertEquals(
                Reversibility.IRREVERSIBLE,
                resolver.classifyReversibility("remove_user"),
                "remove_ prefix 도구는 IRREVERSIBLE로 분류되어야 한다"
            )
        }

        @Test
        fun `drop prefix는 IRREVERSIBLE로 분류되어야 한다`() {
            assertEquals(
                Reversibility.IRREVERSIBLE,
                resolver.classifyReversibility("drop_table"),
                "drop_ prefix 도구는 IRREVERSIBLE로 분류되어야 한다"
            )
        }

        @Test
        fun `purge prefix는 IRREVERSIBLE로 분류되어야 한다`() {
            assertEquals(
                Reversibility.IRREVERSIBLE,
                resolver.classifyReversibility("purge_cache"),
                "purge_ prefix 도구는 IRREVERSIBLE로 분류되어야 한다"
            )
        }

        @Test
        fun `금전 관련 키워드는 IRREVERSIBLE로 분류되어야 한다`() {
            val moneyTools = listOf(
                "process_refund", "charge_customer", "transfer_funds", "pay_invoice", "withdraw_balance"
            )
            moneyTools.forEach { tool ->
                assertEquals(
                    Reversibility.IRREVERSIBLE,
                    resolver.classifyReversibility(tool),
                    "$tool 은 금전 관련 키워드를 포함하므로 IRREVERSIBLE로 분류되어야 한다"
                )
            }
        }

        @Test
        fun `create prefix는 REVERSIBLE로 분류되어야 한다`() {
            assertEquals(
                Reversibility.REVERSIBLE,
                resolver.classifyReversibility("create_issue"),
                "create_ prefix 도구는 REVERSIBLE로 분류되어야 한다 (생성은 되돌릴 수 있음)"
            )
        }

        @Test
        fun `add prefix는 REVERSIBLE로 분류되어야 한다`() {
            assertEquals(
                Reversibility.REVERSIBLE,
                resolver.classifyReversibility("add_comment"),
                "add_ prefix 도구는 REVERSIBLE로 분류되어야 한다"
            )
        }

        @Test
        fun `update prefix는 PARTIALLY_REVERSIBLE로 분류되어야 한다`() {
            assertEquals(
                Reversibility.PARTIALLY_REVERSIBLE,
                resolver.classifyReversibility("update_record"),
                "update_ prefix 도구는 원본 값을 알아야만 복원 가능하므로 PARTIALLY_REVERSIBLE"
            )
        }

        @Test
        fun `modify prefix는 PARTIALLY_REVERSIBLE로 분류되어야 한다`() {
            assertEquals(
                Reversibility.PARTIALLY_REVERSIBLE,
                resolver.classifyReversibility("modify_config"),
                "modify_ prefix 도구는 PARTIALLY_REVERSIBLE로 분류되어야 한다"
            )
        }

        @Test
        fun `transition prefix는 REVERSIBLE로 분류되어야 한다`() {
            assertEquals(
                Reversibility.REVERSIBLE,
                resolver.classifyReversibility("transition_issue"),
                "상태 전이는 되돌릴 수 있으므로 REVERSIBLE로 분류되어야 한다"
            )
        }

        @Test
        fun `알 수 없는 이름은 UNKNOWN으로 분류되어야 한다`() {
            assertEquals(
                Reversibility.UNKNOWN,
                resolver.classifyReversibility("jira_search_issues"),
                "분류 규칙에 매칭되지 않는 도구는 UNKNOWN으로 분류되어야 한다"
            )
        }

        @Test
        fun `대소문자가 섞여도 분류되어야 한다`() {
            assertEquals(
                Reversibility.IRREVERSIBLE,
                resolver.classifyReversibility("DELETE_Order"),
                "대소문자 차이에 관계없이 같은 규칙이 적용되어야 한다"
            )
        }
    }

    @Nested
    inner class ImpactScopeExtraction {

        @Test
        fun `issueKey가 있으면 우선순위 최상위로 선택되어야 한다`() {
            val args = mapOf(
                "issueKey" to "JAR-42",
                "id" to "ignored",
                "name" to "ignored"
            )
            assertEquals(
                "JAR-42",
                resolver.extractImpactScope(args),
                "issueKey가 있으면 최우선으로 선택되어야 한다"
            )
        }

        @Test
        fun `issueKey가 없으면 projectKey가 선택되어야 한다`() {
            val args = mapOf(
                "projectKey" to "JAR",
                "target" to "ignored"
            )
            assertEquals(
                "JAR",
                resolver.extractImpactScope(args),
                "issueKey가 없으면 projectKey가 선택되어야 한다"
            )
        }

        @Test
        fun `pullRequestId가 있으면 우선순위 순서대로 선택되어야 한다`() {
            val args = mapOf(
                "pullRequestId" to "42",
                "name" to "ignored"
            )
            assertEquals(
                "42",
                resolver.extractImpactScope(args),
                "pullRequestId가 있으면 name보다 우선해야 한다"
            )
        }

        @Test
        fun `id fallback이 동작해야 한다`() {
            val args = mapOf("id" to "abc-123")
            assertEquals(
                "abc-123",
                resolver.extractImpactScope(args),
                "id는 기본 fallback 키여야 한다"
            )
        }

        @Test
        fun `빈 맵이면 null을 반환해야 한다`() {
            assertNull(
                resolver.extractImpactScope(emptyMap()),
                "빈 arguments는 null을 반환해야 한다"
            )
        }

        @Test
        fun `매칭되는 키가 없으면 null을 반환해야 한다`() {
            val args = mapOf("unknownKey" to "value")
            assertNull(
                resolver.extractImpactScope(args),
                "알려진 키가 없으면 null을 반환해야 한다"
            )
        }

        @Test
        fun `빈 문자열 값은 무시되어야 한다`() {
            val args = mapOf(
                "id" to "",
                "name" to "actualValue"
            )
            assertEquals(
                "actualValue",
                resolver.extractImpactScope(args),
                "빈 문자열 값은 건너뛰고 다음 우선순위 키를 사용해야 한다"
            )
        }
    }

    @Nested
    inner class EndToEndResolve {

        @Test
        fun `delete 도구에 대해 IRREVERSIBLE 컨텍스트를 생성해야 한다`() {
            val ctx = resolver.resolve(
                "delete_order",
                mapOf("orderId" to "42")
            )
            assertNotNull(ctx) { "resolve는 null이 아닌 컨텍스트를 반환해야 한다" }
            assertEquals(Reversibility.IRREVERSIBLE, ctx.reversibility) {
                "delete_ 도구는 IRREVERSIBLE이어야 한다"
            }
            assertEquals("42", ctx.impactScope) {
                "impactScope는 orderId 값을 포함해야 한다"
            }
            assertTrue(ctx.reason?.contains("파괴적") == true) {
                "reason은 '파괴적' 키워드를 포함해야 한다"
            }
            assertTrue(ctx.action?.contains("delete_order") == true) {
                "action은 toolName을 포함해야 한다"
            }
        }

        @Test
        fun `update 도구에 대해 PARTIALLY_REVERSIBLE 컨텍스트를 생성해야 한다`() {
            val ctx = resolver.resolve(
                "update_user",
                mapOf("userId" to "user-42")
            )
            assertEquals(Reversibility.PARTIALLY_REVERSIBLE, ctx.reversibility) {
                "update_ 도구는 PARTIALLY_REVERSIBLE이어야 한다"
            }
            assertTrue(ctx.reason?.contains("부분적") == true) {
                "reason은 '부분적' 키워드를 포함해야 한다"
            }
        }

        @Test
        fun `알 수 없는 도구에 대해서도 non-null 컨텍스트를 반환해야 한다`() {
            val ctx = resolver.resolve(
                "custom_unknown_tool",
                mapOf("target" to "x")
            )
            assertNotNull(ctx) { "resolve는 항상 non-null 컨텍스트를 반환해야 한다" }
            assertEquals(Reversibility.UNKNOWN, ctx.reversibility) {
                "알려지지 않은 도구는 UNKNOWN이어야 한다"
            }
            assertTrue(ctx.hasAnyInformation()) {
                "action과 impactScope는 채워지므로 hasAnyInformation이 true여야 한다"
            }
        }

        @Test
        fun `모든 컨텍스트 필드가 채워져야 한다`() {
            val ctx = resolver.resolve(
                "delete_issue",
                mapOf("issueKey" to "JAR-99")
            )
            assertNotNull(ctx.reason) { "reason이 채워져야 한다" }
            assertNotNull(ctx.action) { "action이 채워져야 한다" }
            assertNotNull(ctx.impactScope) { "impactScope가 채워져야 한다" }
            assertEquals(Reversibility.IRREVERSIBLE, ctx.reversibility) {
                "reversibility가 분류되어야 한다"
            }
        }

        @Test
        fun `인수가 비어있어도 컨텍스트를 생성해야 한다`() {
            val ctx = resolver.resolve("delete_all", emptyMap())
            assertNotNull(ctx) { "빈 arguments에 대해서도 컨텍스트를 반환해야 한다" }
            assertEquals(Reversibility.IRREVERSIBLE, ctx.reversibility) {
                "reversibility는 여전히 분류되어야 한다"
            }
            assertNull(ctx.impactScope) {
                "arguments가 비어있으면 impactScope는 null이어야 한다"
            }
        }
    }
}
