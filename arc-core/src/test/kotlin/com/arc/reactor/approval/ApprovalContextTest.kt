package com.arc.reactor.approval

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [ApprovalContext]와 [Reversibility]에 대한 단위 테스트.
 *
 * R221 Directive #1 Tool Approval 4단계 정보 구조화의 모델 레이어 검증.
 * 기본값, 불변성, hasAnyInformation 판별 로직을 테스트한다.
 */
class ApprovalContextTest {

    @Nested
    inner class ReversibilityEnum {

        @Test
        fun `네 가지 복구 가능성 값이 정의되어야 한다`() {
            val values = Reversibility.values()
            assertEquals(4, values.size) { "Reversibility는 정확히 4개 값을 가져야 한다" }
            assertTrue(Reversibility.REVERSIBLE in values) { "REVERSIBLE이 포함되어야 한다" }
            assertTrue(Reversibility.PARTIALLY_REVERSIBLE in values) { "PARTIALLY_REVERSIBLE이 포함되어야 한다" }
            assertTrue(Reversibility.IRREVERSIBLE in values) { "IRREVERSIBLE이 포함되어야 한다" }
            assertTrue(Reversibility.UNKNOWN in values) { "UNKNOWN이 포함되어야 한다" }
        }

        @Test
        fun `UNKNOWN이 기본값 역할이어야 한다`() {
            // ApprovalContext() 생성 시 reversibility 기본값이 UNKNOWN
            val ctx = ApprovalContext()
            assertEquals(Reversibility.UNKNOWN, ctx.reversibility) {
                "ApprovalContext 생성 시 reversibility 기본값은 UNKNOWN이어야 한다"
            }
        }
    }

    @Nested
    inner class DefaultConstruction {

        @Test
        fun `빈 ApprovalContext는 모든 필드가 기본값이어야 한다`() {
            val ctx = ApprovalContext()
            assertNull(ctx.reason) { "reason 기본값은 null이어야 한다" }
            assertNull(ctx.action) { "action 기본값은 null이어야 한다" }
            assertNull(ctx.impactScope) { "impactScope 기본값은 null이어야 한다" }
            assertEquals(Reversibility.UNKNOWN, ctx.reversibility) {
                "reversibility 기본값은 UNKNOWN이어야 한다"
            }
        }

        @Test
        fun `EMPTY 상수는 기본 생성자와 동등해야 한다`() {
            assertEquals(ApprovalContext(), ApprovalContext.EMPTY) {
                "ApprovalContext.EMPTY는 기본 생성자와 동등해야 한다"
            }
        }
    }

    @Nested
    inner class HasAnyInformation {

        @Test
        fun `빈 컨텍스트는 정보 없음으로 판별되어야 한다`() {
            assertFalse(ApprovalContext().hasAnyInformation()) {
                "모든 필드가 기본값이면 hasAnyInformation()은 false여야 한다"
            }
            assertFalse(ApprovalContext.EMPTY.hasAnyInformation()) {
                "EMPTY 상수는 hasAnyInformation()이 false여야 한다"
            }
        }

        @Test
        fun `reason만 설정해도 정보 있음으로 판별되어야 한다`() {
            val ctx = ApprovalContext(reason = "파괴적 작업")
            assertTrue(ctx.hasAnyInformation()) {
                "reason이 null이 아니면 hasAnyInformation()은 true여야 한다"
            }
        }

        @Test
        fun `action만 설정해도 정보 있음으로 판별되어야 한다`() {
            val ctx = ApprovalContext(action = "delete_order(42)")
            assertTrue(ctx.hasAnyInformation()) {
                "action이 null이 아니면 hasAnyInformation()은 true여야 한다"
            }
        }

        @Test
        fun `impactScope만 설정해도 정보 있음으로 판별되어야 한다`() {
            val ctx = ApprovalContext(impactScope = "주문 1건")
            assertTrue(ctx.hasAnyInformation()) {
                "impactScope가 null이 아니면 hasAnyInformation()은 true여야 한다"
            }
        }

        @Test
        fun `reversibility가 UNKNOWN이 아니면 정보 있음으로 판별되어야 한다`() {
            val ctx = ApprovalContext(reversibility = Reversibility.IRREVERSIBLE)
            assertTrue(ctx.hasAnyInformation()) {
                "reversibility가 UNKNOWN이 아니면 hasAnyInformation()은 true여야 한다"
            }
        }

        @Test
        fun `모든 필드를 설정하면 정보 있음으로 판별되어야 한다`() {
            val ctx = ApprovalContext(
                reason = "파괴적 작업",
                action = "delete_order(42)",
                impactScope = "주문 1건",
                reversibility = Reversibility.IRREVERSIBLE
            )
            assertTrue(ctx.hasAnyInformation()) {
                "모든 필드가 채워지면 hasAnyInformation()은 true여야 한다"
            }
        }
    }

    @Nested
    inner class Equality {

        @Test
        fun `동일 필드를 가진 두 인스턴스는 동등해야 한다`() {
            val c1 = ApprovalContext(
                reason = "r", action = "a", impactScope = "i",
                reversibility = Reversibility.REVERSIBLE
            )
            val c2 = c1.copy()
            assertEquals(c1, c2) { "copy()로 생성한 인스턴스는 원본과 동등해야 한다" }
        }

        @Test
        fun `copy로 일부 필드를 변경한 인스턴스는 다른 인스턴스여야 한다`() {
            val original = ApprovalContext(
                reason = "원본",
                reversibility = Reversibility.REVERSIBLE
            )
            val modified = original.copy(reversibility = Reversibility.IRREVERSIBLE)
            assertNotEquals(original, modified) {
                "reversibility가 다른 인스턴스는 다르게 간주되어야 한다"
            }
            assertEquals(original.reason, modified.reason) {
                "변경되지 않은 reason은 유지되어야 한다"
            }
        }
    }

    @Nested
    inner class IntegrationWithToolApprovalRequest {

        @Test
        fun `ToolApprovalRequest에 context를 부가할 수 있어야 한다`() {
            val ctx = ApprovalContext(
                reason = "파괴적 작업",
                action = "delete_order(42)",
                impactScope = "주문 1건",
                reversibility = Reversibility.IRREVERSIBLE
            )
            val request = ToolApprovalRequest(
                id = "req-001",
                runId = "run-abc",
                userId = "user-xyz",
                toolName = "delete_order",
                arguments = mapOf("orderId" to "42"),
                context = ctx
            )
            assertNotNull(request.context) { "context가 보존되어야 한다" }
            assertEquals("파괴적 작업", request.context?.reason) { "reason이 일치해야 한다" }
            assertEquals(Reversibility.IRREVERSIBLE, request.context?.reversibility) {
                "reversibility가 일치해야 한다"
            }
        }

        @Test
        fun `ToolApprovalRequest의 context 기본값은 null이어야 한다 (backward compat)`() {
            val request = ToolApprovalRequest(
                id = "req-002",
                runId = "run-abc",
                userId = "user-xyz",
                toolName = "legacy_tool",
                arguments = emptyMap()
            )
            assertNull(request.context) {
                "context 기본값은 null이어야 한다 (기존 생성자 호출 경로 호환)"
            }
        }

        @Test
        fun `ApprovalSummary에 context를 부가할 수 있어야 한다`() {
            val ctx = ApprovalContext(reason = "테스트")
            val summary = ApprovalSummary(
                id = "sum-001",
                runId = "run-abc",
                userId = "user-xyz",
                toolName = "delete_order",
                arguments = emptyMap(),
                requestedAt = java.time.Instant.now(),
                status = ApprovalStatus.PENDING,
                context = ctx
            )
            assertNotNull(summary.context) { "summary의 context가 보존되어야 한다" }
            assertEquals("테스트", summary.context?.reason) { "summary의 reason이 일치해야 한다" }
        }

        @Test
        fun `ApprovalSummary의 context 기본값은 null이어야 한다 (backward compat)`() {
            val summary = ApprovalSummary(
                id = "sum-002",
                runId = "run-abc",
                userId = "user-xyz",
                toolName = "legacy_tool",
                arguments = emptyMap(),
                requestedAt = java.time.Instant.now(),
                status = ApprovalStatus.PENDING
            )
            assertNull(summary.context) {
                "summary의 context 기본값은 null이어야 한다"
            }
        }
    }
}
