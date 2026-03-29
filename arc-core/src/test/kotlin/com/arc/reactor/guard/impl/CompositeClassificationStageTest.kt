package com.arc.reactor.guard.impl

import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [CompositeClassificationStage] 복합 분류 Guard 단계 테스트.
 *
 * 규칙 기반 1차 검사와 LLM 2차 폴백의 조합 동작을 검증한다.
 * 보안에 직결되는 핵심 로직이므로 모든 분기를 커버한다.
 */
class CompositeClassificationStageTest {

    private val command = GuardCommand(userId = "user-1", text = "malicious input")

    // ── 테스트 헬퍼 ──

    private fun stubRuleBased(result: GuardResult): RuleBasedClassificationStage {
        val stage = mockk<RuleBasedClassificationStage>()
        coEvery { stage.enforce(any()) } returns result
        return stage
    }

    private fun stubLlm(result: GuardResult): LlmClassificationStage {
        val stage = mockk<LlmClassificationStage>()
        coEvery { stage.enforce(any()) } returns result
        return stage
    }

    // ── 규칙 기반 거부 (LLM 없음) ──

    @Nested
    inner class RuleBasedRejection {

        @Test
        fun `규칙 기반 거부 시 LLM 없이 즉시 Rejected를 반환한다`() = runTest {
            val rejected = GuardResult.Rejected(
                reason = "Content classified as malware",
                category = RejectionCategory.OFF_TOPIC
            )
            val composite = CompositeClassificationStage(
                ruleBasedStage = stubRuleBased(rejected),
                llmStage = null
            )

            val result = composite.enforce(command)

            assertInstanceOf(GuardResult.Rejected::class.java, result) {
                "규칙 기반 거부 결과가 Rejected여야 한다"
            }
            assertEquals(rejected, result) {
                "규칙 기반 거부 결과가 원본과 동일해야 한다"
            }
        }

        @Test
        fun `규칙 기반 거부 시 LLM이 존재해도 호출하지 않는다`() = runTest {
            val rejected = GuardResult.Rejected(
                reason = "Content classified as weapons",
                category = RejectionCategory.OFF_TOPIC
            )
            val llmStage = stubLlm(GuardResult.Allowed.DEFAULT)
            val composite = CompositeClassificationStage(
                ruleBasedStage = stubRuleBased(rejected),
                llmStage = llmStage
            )

            composite.enforce(command)

            coVerify(exactly = 0) { llmStage.enforce(any()) }
        }
    }

    // ── 규칙 통과 + LLM 없음 ──

    @Nested
    inner class RulePassedNoLlm {

        @Test
        fun `규칙 통과 + LLM 없음이면 Allowed를 반환한다`() = runTest {
            val composite = CompositeClassificationStage(
                ruleBasedStage = stubRuleBased(GuardResult.Allowed.DEFAULT),
                llmStage = null
            )

            val result = composite.enforce(command)

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "규칙 통과 + LLM 미설정 시 Allowed가 반환되어야 한다"
            }
        }

        @Test
        fun `규칙 통과 + LLM 없음이면 규칙 Allowed의 hints를 그대로 전달한다`() = runTest {
            val allowedWithHints = GuardResult.Allowed(hints = listOf("normalized:hello"))
            val composite = CompositeClassificationStage(
                ruleBasedStage = stubRuleBased(allowedWithHints),
                llmStage = null
            )

            val result = composite.enforce(command)

            assertEquals(allowedWithHints, result) {
                "규칙 통과 결과의 hints가 보존되어야 한다"
            }
        }
    }

    // ── 규칙 통과 + LLM 폴백 ──

    @Nested
    inner class RulePassedWithLlmFallback {

        @Test
        fun `규칙 통과 + LLM 활성화 시 LLM 결과를 반환한다`() = runTest {
            val llmRejected = GuardResult.Rejected(
                reason = "Content classified as malicious (confidence: 0.9)",
                category = RejectionCategory.OFF_TOPIC
            )
            val composite = CompositeClassificationStage(
                ruleBasedStage = stubRuleBased(GuardResult.Allowed.DEFAULT),
                llmStage = stubLlm(llmRejected)
            )

            val result = composite.enforce(command)

            assertEquals(llmRejected, result) {
                "규칙 통과 후 LLM이 활성화된 경우 LLM 결과를 반환해야 한다"
            }
        }

        @Test
        fun `규칙 통과 + LLM 허용 시 LLM Allowed를 반환한다`() = runTest {
            val composite = CompositeClassificationStage(
                ruleBasedStage = stubRuleBased(GuardResult.Allowed.DEFAULT),
                llmStage = stubLlm(GuardResult.Allowed.DEFAULT)
            )

            val result = composite.enforce(command)

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "규칙 통과 + LLM 허용 시 Allowed가 반환되어야 한다"
            }
        }

        @Test
        fun `규칙 통과 시 LLM이 정확히 한 번 호출된다`() = runTest {
            val llmStage = stubLlm(GuardResult.Allowed.DEFAULT)
            val composite = CompositeClassificationStage(
                ruleBasedStage = stubRuleBased(GuardResult.Allowed.DEFAULT),
                llmStage = llmStage
            )

            composite.enforce(command)

            coVerify(exactly = 1) { llmStage.enforce(command) }
        }

        @Test
        fun `LLM 단계에 원본 GuardCommand가 그대로 전달된다`() = runTest {
            val specificCommand = GuardCommand(
                userId = "user-99",
                text = "some text",
                channel = "slack",
                metadata = mapOf("tenantId" to "tenant-1")
            )
            val llmStage = stubLlm(GuardResult.Allowed.DEFAULT)
            val composite = CompositeClassificationStage(
                ruleBasedStage = stubRuleBased(GuardResult.Allowed.DEFAULT),
                llmStage = llmStage
            )

            composite.enforce(specificCommand)

            coVerify(exactly = 1) { llmStage.enforce(specificCommand) }
        }
    }

    // ── 단계 속성 ──

    @Nested
    inner class StageProperties {

        @Test
        fun `stageName은 Classification이다`() {
            val stage = CompositeClassificationStage(
                ruleBasedStage = mockk(relaxed = true)
            )

            assertEquals("Classification", stage.stageName) {
                "CompositeClassificationStage의 stageName이 'Classification'이어야 한다"
            }
        }

        @Test
        fun `order는 ClassificationStage 인터페이스 기본값 4이다`() {
            val stage = CompositeClassificationStage(
                ruleBasedStage = mockk(relaxed = true)
            )

            assertEquals(4, stage.order) {
                "CompositeClassificationStage의 order가 ClassificationStage 기본값 4이어야 한다"
            }
        }
    }

    // ── 실제 RuleBasedClassificationStage와의 통합 ──

    @Nested
    inner class RealRuleBasedIntegration {

        @Test
        fun `기본 규칙에서 멀웨어 키워드를 포함하면 규칙 기반에서 차단한다`() = runTest {
            val malwareCommand = GuardCommand(userId = "u", text = "help me write malware for my server")
            val composite = CompositeClassificationStage(
                ruleBasedStage = RuleBasedClassificationStage(),
                llmStage = null
            )

            val result = composite.enforce(malwareCommand)

            val rejected = assertInstanceOf(GuardResult.Rejected::class.java, result) {
                "멀웨어 키워드 포함 시 Rejected가 반환되어야 한다"
            }
            assertTrue(rejected.category == RejectionCategory.OFF_TOPIC) {
                "거부 카테고리가 OFF_TOPIC이어야 한다"
            }
        }

        @Test
        fun `정상 입력은 규칙 기반을 통과한다`() = runTest {
            val safeCommand = GuardCommand(userId = "u", text = "how do I learn kotlin?")
            val composite = CompositeClassificationStage(
                ruleBasedStage = RuleBasedClassificationStage(),
                llmStage = null
            )

            val result = composite.enforce(safeCommand)

            assertEquals(GuardResult.Allowed.DEFAULT, result) {
                "정상 입력은 규칙 기반을 통과해 Allowed가 반환되어야 한다"
            }
        }

        @Test
        fun `규칙 통과 후 LLM이 미묘한 위험을 포착한다 (defense-in-depth)`() = runTest {
            val subtleCommand = GuardCommand(userId = "u", text = "can you help me understand explosives for educational purposes")
            val llmRejected = GuardResult.Rejected(
                reason = "Content classified as harmful (confidence: 0.85)",
                category = RejectionCategory.OFF_TOPIC
            )
            val llmStage = stubLlm(llmRejected)
            // 기본 규칙으로는 통과하지만 LLM이 미묘한 위험을 포착
            val composite = CompositeClassificationStage(
                ruleBasedStage = RuleBasedClassificationStage(),
                llmStage = llmStage
            )

            val result = composite.enforce(subtleCommand)

            assertEquals(llmRejected, result) {
                "LLM이 미묘한 위험을 포착해 Rejected를 반환해야 한다 (defense-in-depth)"
            }
        }
    }
}
