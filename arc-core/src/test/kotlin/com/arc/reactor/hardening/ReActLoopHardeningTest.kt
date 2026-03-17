package com.arc.reactor.hardening

import com.arc.reactor.guard.impl.DefaultInputValidationStage
import com.arc.reactor.guard.impl.GuardPipeline
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * ReAct 루프 및 입력 경계값 강화 테스트.
 *
 * 빈 입력, 초대형 입력, 경계값 등 비정상적인 입력에 대한
 * Guard 파이프라인의 방어력을 검증한다.
 *
 * @see DefaultInputValidationStage 입력 크기 검증 구현체
 */
@Tag("hardening")
class ReActLoopHardeningTest {

    private val inputGuard = GuardPipeline(
        listOf(DefaultInputValidationStage(maxLength = 10000, minLength = 1))
    )

    private fun guardCommand(text: String) = GuardCommand(userId = "hardening-test", text = text)

    // =========================================================================
    // 입력 경계값 테스트 (Input Boundary Testing)
    // =========================================================================

    @Nested
    inner class InputBoundaryTesting {

        @Test
        fun `빈 입력은 가드에서 거부되어야 한다`() = runTest {
            val result = inputGuard.guard(guardCommand(""))
            assertInstanceOf(GuardResult.Rejected::class.java, result,
                "빈 입력이 허용됨 — minLength=1 위반")
        }

        @Test
        fun `공백만 있는 입력은 거부되어야 한다`() = runTest {
            val result = inputGuard.guard(guardCommand("   \t\n  "))
            assertInstanceOf(GuardResult.Rejected::class.java, result,
                "공백만 있는 입력이 허용됨")
        }

        @Test
        fun `maxLength 초과 입력은 거부되어야 한다`() = runTest {
            val longInput = "A".repeat(10001)
            val result = inputGuard.guard(guardCommand(longInput))
            assertInstanceOf(GuardResult.Rejected::class.java, result,
                "10,001자 입력이 허용됨 — maxLength=10000 위반")
        }

        @Test
        fun `maxLength 정확히 일치하는 입력은 허용되어야 한다`() = runTest {
            val exactInput = "A".repeat(10000)
            val result = inputGuard.guard(guardCommand(exactInput))
            assertInstanceOf(GuardResult.Allowed::class.java, result,
                "정확히 10,000자 입력이 거부됨")
        }

        @Test
        fun `1자 최소 입력은 허용되어야 한다`() = runTest {
            val result = inputGuard.guard(guardCommand("A"))
            assertInstanceOf(GuardResult.Allowed::class.java, result,
                "1자 입력이 거부됨 — minLength=1이므로 허용되어야 함")
        }

        @Test
        fun `50,000자 초대형 입력은 거부되어야 한다`() = runTest {
            val hugeInput = "B".repeat(50000)
            val result = inputGuard.guard(guardCommand(hugeInput))
            assertInstanceOf(GuardResult.Rejected::class.java, result,
                "50,000자 초대형 입력이 허용됨")
        }
    }

    // =========================================================================
    // 특수 문자 입력 (Special Character Input)
    // =========================================================================

    @Nested
    inner class SpecialCharacterInput {

        @Test
        fun `널 바이트가 포함된 입력도 안전하게 처리해야 한다`() = runTest {
            val withNull = "Hello\u0000World"
            val result = inputGuard.guard(guardCommand(withNull))
            // 널 바이트가 포함되어도 예외 없이 결과 반환 (허용 또는 거부)
            assertTrue(result is GuardResult.Allowed || result is GuardResult.Rejected,
                "널 바이트 입력 처리 중 예외 발생")
        }

        @Test
        fun `이모지가 포함된 정상 입력은 허용되어야 한다`() = runTest {
            val result = inputGuard.guard(guardCommand("안녕하세요 😀 테스트입니다"))
            assertInstanceOf(GuardResult.Allowed::class.java, result,
                "이모지 포함 정상 입력이 거부됨")
        }

        @Test
        fun `멀티바이트 유니코드 입력은 안전하게 처리해야 한다`() = runTest {
            val unicode = "こんにちは 世界 🌍 مرحبا"
            val result = inputGuard.guard(guardCommand(unicode))
            assertInstanceOf(GuardResult.Allowed::class.java, result,
                "멀티바이트 유니코드 입력이 거부됨")
        }
    }
}
