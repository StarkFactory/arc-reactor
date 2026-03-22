package com.arc.reactor.agent.plan

import com.arc.reactor.approval.ToolApprovalPolicy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [DefaultPlanValidator]의 단위 테스트.
 *
 * 유효/무효 계획, 빈 계획, 승인 정책 경고 등 경계 조건을 검증한다.
 */
class PlanValidatorTest {

    private lateinit var validator: PlanValidator

    @BeforeEach
    fun setup() {
        validator = DefaultPlanValidator()
    }

    @Test
    fun `유효한 계획은 검증을 통과해야 한다`() = runTest {
        val steps = listOf(
            PlanStep("tool_a", mapOf("key" to "val"), "A 실행"),
            PlanStep("tool_b", emptyMap(), "B 실행")
        )
        val available = setOf("tool_a", "tool_b", "tool_c")

        val result = validator.validate(steps, available)

        assertTrue(result.valid, "유효한 계획은 valid=true여야 한다")
        assertTrue(result.errors.isEmpty(), "유효한 계획은 에러가 없어야 한다")
        assertEquals(2, result.steps.size, "원본 단계 수가 보존되어야 한다")
    }

    @Test
    fun `존재하지 않는 도구가 포함된 계획은 검증 실패해야 한다`() = runTest {
        val steps = listOf(
            PlanStep("real_tool", emptyMap(), "실제 도구"),
            PlanStep("fake_tool", emptyMap(), "가짜 도구")
        )
        val available = setOf("real_tool")

        val result = validator.validate(steps, available)

        assertFalse(result.valid, "존재하지 않는 도구 포함 시 valid=false여야 한다")
        assertEquals(1, result.errors.size, "에러가 정확히 1개여야 한다")
        assertTrue(
            result.errors[0].contains("fake_tool"),
            "에러 메시지에 잘못된 도구 이름이 포함되어야 한다"
        )
    }

    @Test
    fun `빈 도구 이름이 포함된 계획은 검증 실패해야 한다`() = runTest {
        val steps = listOf(
            PlanStep("", emptyMap(), "빈 도구명")
        )
        val available = setOf("tool_a")

        val result = validator.validate(steps, available)

        assertFalse(result.valid, "빈 도구명 포함 시 valid=false여야 한다")
        assertTrue(
            result.errors[0].contains("비어 있습니다"),
            "에러 메시지에 빈 도구명 사유가 포함되어야 한다"
        )
    }

    @Test
    fun `공백만 있는 도구 이름도 빈 것으로 처리해야 한다`() = runTest {
        val steps = listOf(
            PlanStep("   ", emptyMap(), "공백 도구명")
        )
        val available = setOf("tool_a")

        val result = validator.validate(steps, available)

        assertFalse(result.valid, "공백 도구명은 valid=false여야 한다")
        assertTrue(
            result.errors[0].contains("비어 있습니다"),
            "공백 도구명도 빈 도구명과 동일하게 처리되어야 한다"
        )
    }

    @Test
    fun `빈 계획은 검증 실패해야 한다`() = runTest {
        val steps = emptyList<PlanStep>()
        val available = setOf("tool_a")

        val result = validator.validate(steps, available)

        assertFalse(result.valid, "빈 계획은 valid=false여야 한다")
        assertTrue(
            result.errors[0].contains("비어 있습니다"),
            "빈 계획 에러 메시지가 포함되어야 한다"
        )
    }

    @Test
    fun `여러 도구가 모두 존재하지 않으면 각각 에러를 보고해야 한다`() = runTest {
        val steps = listOf(
            PlanStep("ghost_a", emptyMap(), "유령 A"),
            PlanStep("ghost_b", emptyMap(), "유령 B")
        )
        val available = setOf("real_tool")

        val result = validator.validate(steps, available)

        assertFalse(result.valid, "모두 무효하면 valid=false여야 한다")
        assertEquals(
            2, result.errors.size,
            "각 무효 단계마다 에러가 있어야 한다"
        )
    }

    @Test
    fun `승인 필요 도구는 검증을 통과하되 로그로 경고해야 한다`() = runTest {
        val steps = listOf(
            PlanStep("delete_order", mapOf("id" to 1), "주문 삭제")
        )
        val available = setOf("delete_order")
        val policy = mockk<ToolApprovalPolicy>()
        every {
            policy.requiresApproval("delete_order", mapOf("id" to 1))
        } returns true

        val result = validator.validate(steps, available, policy)

        assertTrue(
            result.valid,
            "승인 필요 도구는 검증 단계에서는 통과해야 한다 (실행 시점에 차단)"
        )
        assertTrue(result.errors.isEmpty(), "승인 경고는 에러가 아니다")
    }

    @Test
    fun `승인 정책이 null이면 승인 검사를 건너뛰어야 한다`() = runTest {
        val steps = listOf(
            PlanStep("any_tool", emptyMap(), "아무 도구")
        )
        val available = setOf("any_tool")

        val result = validator.validate(steps, available, null)

        assertTrue(result.valid, "정책 null이면 승인 검사 없이 통과해야 한다")
    }

    @Test
    fun `유효한 도구와 무효한 도구가 혼합되면 무효한 것만 에러해야 한다`() = runTest {
        val steps = listOf(
            PlanStep("valid_tool", emptyMap(), "유효"),
            PlanStep("invalid_tool", emptyMap(), "무효"),
            PlanStep("another_valid", emptyMap(), "유효2")
        )
        val available = setOf("valid_tool", "another_valid")

        val result = validator.validate(steps, available)

        assertFalse(result.valid, "무효 도구가 하나라도 있으면 valid=false")
        assertEquals(
            1, result.errors.size,
            "무효한 도구만 에러에 포함되어야 한다"
        )
        assertTrue(
            result.errors[0].contains("invalid_tool"),
            "에러에 무효 도구명이 포함되어야 한다"
        )
    }
}
