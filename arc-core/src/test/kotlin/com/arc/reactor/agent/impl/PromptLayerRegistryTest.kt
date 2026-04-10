package com.arc.reactor.agent.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [PromptLayerRegistry]의 분류 무결성 테스트.
 *
 * 이 테스트는 다음을 검증한다:
 * 1. 레지스트리가 비어있지 않음
 * 2. 모든 [PromptLayer] 값에 최소 1개 이상의 메서드가 할당됨
 * 3. 메인 경로와 계획 경로가 서로 겹치지 않음
 * 4. 알려진 메서드에 대해 올바른 계층을 반환함
 * 5. 알 수 없는 메서드에 대해 null을 반환함
 *
 * 이 테스트의 목적은 runtime 동작이 아니라 **분류 문서화의 완결성**을 강제하는 것이다.
 * 새 append 메서드를 추가할 때 레지스트리 업데이트를 잊지 않도록 돕는다.
 */
class PromptLayerRegistryTest {

    @Nested
    inner class BasicInvariants {

        @Test
        fun `레지스트리는 비어있지 않아야 한다`() {
            assertTrue(
                PromptLayerRegistry.allClassifiedMethods().isNotEmpty(),
                "PromptLayerRegistry는 append 메서드 분류를 하나 이상 포함해야 한다"
            )
        }

        @Test
        fun `메인 경로는 비어있지 않아야 한다`() {
            assertTrue(
                PromptLayerRegistry.mainPathMethods().isNotEmpty(),
                "메인 grounding 경로 분류가 비어있으면 안 된다"
            )
        }

        @Test
        fun `계획 경로는 비어있지 않아야 한다`() {
            assertTrue(
                PromptLayerRegistry.planningPathMethods().isNotEmpty(),
                "계획(PLAN_EXECUTE) 경로 분류가 비어있으면 안 된다"
            )
        }

        @Test
        fun `메인 경로와 계획 경로는 서로 겹치지 않아야 한다`() {
            val overlap = PromptLayerRegistry.mainPathMethods()
                .intersect(PromptLayerRegistry.planningPathMethods())
            assertTrue(
                overlap.isEmpty(),
                "메인 경로와 계획 경로는 독립적이어야 한다. 겹치는 메서드: $overlap"
            )
        }

        @Test
        fun `모든 분류 메서드는 메인 또는 계획 경로 중 하나에 속해야 한다`() {
            val all = PromptLayerRegistry.allClassifiedMethods()
            val union = PromptLayerRegistry.mainPathMethods() +
                PromptLayerRegistry.planningPathMethods()
            assertEquals(
                union,
                all,
                "allClassifiedMethods()는 mainPathMethods() + planningPathMethods()와 같아야 한다"
            )
        }
    }

    @Nested
    inner class LayerCoverage {

        @Test
        fun `모든 PromptLayer 값에 최소 1개 이상의 메서드가 할당되어야 한다`() {
            PromptLayer.values().forEach { layer ->
                val methods = PromptLayerRegistry.methodsInLayer(layer)
                assertTrue(
                    methods.isNotEmpty(),
                    "PromptLayer.$layer 에 할당된 메서드가 없다. Directive 원칙상 모든 계층이 사용되어야 한다"
                )
            }
        }
    }

    @Nested
    inner class KnownMethodClassification {

        @Test
        fun `appendLanguageRule은 IDENTITY 계층이어야 한다`() {
            assertEquals(
                PromptLayer.IDENTITY,
                PromptLayerRegistry.layerOf("appendLanguageRule"),
                "언어 규칙은 에이전트의 정체성 계층에 속한다"
            )
        }

        @Test
        fun `appendReadOnlyPolicy는 SAFETY 계층이어야 한다`() {
            assertEquals(
                PromptLayer.SAFETY,
                PromptLayerRegistry.layerOf("appendReadOnlyPolicy"),
                "읽기 전용 정책은 안전 계층에 속한다"
            )
        }

        @Test
        fun `appendMutationRefusal은 SAFETY 계층이어야 한다`() {
            assertEquals(
                PromptLayer.SAFETY,
                PromptLayerRegistry.layerOf("appendMutationRefusal"),
                "뮤테이션 거부는 안전 계층에 속한다"
            )
        }

        @Test
        fun `appendPreventReservedPhrasesFinalReminder는 SAFETY 계층이어야 한다`() {
            assertEquals(
                PromptLayer.SAFETY,
                PromptLayerRegistry.layerOf("appendPreventReservedPhrasesFinalReminder"),
                "예약 문구 금지 최종 재확인은 안전 계층에 속한다"
            )
        }

        @Test
        fun `appendConversationHistoryRule은 MEMORY_HINT 계층이어야 한다`() {
            assertEquals(
                PromptLayer.MEMORY_HINT,
                PromptLayerRegistry.layerOf("appendConversationHistoryRule"),
                "대화 이력 규칙은 메모리/컨텍스트 힌트 계층에 속한다"
            )
        }

        @Test
        fun `appendWorkspaceGroundingRules는 WORKSPACE_POLICY 계층이어야 한다`() {
            assertEquals(
                PromptLayer.WORKSPACE_POLICY,
                PromptLayerRegistry.layerOf("appendWorkspaceGroundingRules"),
                "워크스페이스 grounding은 워크스페이스 정책 계층에 속한다"
            )
        }

        @Test
        fun `appendGeneralGroundingRule은 WORKSPACE_POLICY 계층이어야 한다`() {
            assertEquals(
                PromptLayer.WORKSPACE_POLICY,
                PromptLayerRegistry.layerOf("appendGeneralGroundingRule"),
                "일반 grounding은 워크스페이스 분기의 대칭 케이스이므로 워크스페이스 정책 계층에 속한다"
            )
        }

        @Test
        fun `appendResponseQualityInstruction은 RESPONSE_STYLE 계층이어야 한다`() {
            assertEquals(
                PromptLayer.RESPONSE_STYLE,
                PromptLayerRegistry.layerOf("appendResponseQualityInstruction"),
                "응답 품질 지시는 응답 스타일 계층에 속한다"
            )
        }

        @Test
        fun `appendSourcesInstruction은 RESPONSE_STYLE 계층이어야 한다`() {
            assertEquals(
                PromptLayer.RESPONSE_STYLE,
                PromptLayerRegistry.layerOf("appendSourcesInstruction"),
                "출처 섹션 지시는 응답 스타일 계층에 속한다"
            )
        }

        @Test
        fun `모든 도구 호출 강제 메서드는 TOOL_POLICY 계층이어야 한다`() {
            val forcingMethods = listOf(
                "appendConfluenceToolForcing",
                "appendInternalDocSearchForcing",
                "appendTeamStatusForcing",
                "appendWorkToolForcing",
                "appendJiraToolForcing",
                "appendBitbucketToolForcing",
                "appendSwaggerToolForcing"
            )
            forcingMethods.forEach { name ->
                assertEquals(
                    PromptLayer.TOOL_POLICY,
                    PromptLayerRegistry.layerOf(name),
                    "$name 은 도구 호출 강제 메서드이므로 TOOL_POLICY 계층에 속해야 한다"
                )
            }
        }

        @Test
        fun `appendDuplicateToolCallPreventionHint는 TOOL_POLICY 계층이어야 한다`() {
            assertEquals(
                PromptLayer.TOOL_POLICY,
                PromptLayerRegistry.layerOf("appendDuplicateToolCallPreventionHint"),
                "중복 호출 방지 힌트는 도구 사용 정책 계층에 속한다"
            )
        }

        @Test
        fun `appendFewShotReadOnlyExamples는 TOOL_POLICY 계층이어야 한다`() {
            assertEquals(
                PromptLayer.TOOL_POLICY,
                PromptLayerRegistry.layerOf("appendFewShotReadOnlyExamples"),
                "Few-shot 예시는 도구 호출 패턴 학습이 주된 목적이므로 TOOL_POLICY 계층에 속한다"
            )
        }
    }

    @Nested
    inner class UnknownMethodHandling {

        @Test
        fun `등록되지 않은 메서드 이름에 대해 null을 반환해야 한다`() {
            assertNull(
                PromptLayerRegistry.layerOf("appendNonExistentMethod"),
                "등록되지 않은 메서드는 null을 반환해야 한다"
            )
        }

        @Test
        fun `빈 문자열에 대해 null을 반환해야 한다`() {
            assertNull(
                PromptLayerRegistry.layerOf(""),
                "빈 문자열은 null을 반환해야 한다"
            )
        }

        @Test
        fun `대소문자가 다른 이름에 대해 null을 반환해야 한다`() {
            assertNull(
                PromptLayerRegistry.layerOf("APPENDLANGUAGERULE"),
                "메서드 이름 매칭은 case-sensitive이므로 대문자는 null을 반환해야 한다"
            )
        }
    }

    @Nested
    inner class PlanningPathClassification {

        @Test
        fun `appendPlanningRole은 IDENTITY 계층이어야 한다`() {
            assertEquals(
                PromptLayer.IDENTITY,
                PromptLayerRegistry.layerOf("appendPlanningRole"),
                "계획 역할 선언은 플래너의 정체성 계층에 속한다"
            )
        }

        @Test
        fun `appendPlanningConstraints는 SAFETY 계층이어야 한다`() {
            assertEquals(
                PromptLayer.SAFETY,
                PromptLayerRegistry.layerOf("appendPlanningConstraints"),
                "계획 제약(실행 금지, 빈 배열 허용 등)은 안전 계층에 속한다"
            )
        }

        @Test
        fun `appendPlanningOutputSchema는 RESPONSE_STYLE 계층이어야 한다`() {
            assertEquals(
                PromptLayer.RESPONSE_STYLE,
                PromptLayerRegistry.layerOf("appendPlanningOutputSchema"),
                "계획 출력 스키마는 응답 스타일 계층에 속한다"
            )
        }
    }

    @Nested
    inner class NonNullContract {

        @Test
        fun `메인 경로의 모든 분류는 non-null 계층을 반환해야 한다`() {
            PromptLayerRegistry.mainPathMethods().forEach { name ->
                assertNotNull(
                    PromptLayerRegistry.layerOf(name),
                    "메인 경로 메서드 '$name'이 layerOf에서 null을 반환하면 안 된다"
                )
            }
        }

        @Test
        fun `계획 경로의 모든 분류는 non-null 계층을 반환해야 한다`() {
            PromptLayerRegistry.planningPathMethods().forEach { name ->
                assertNotNull(
                    PromptLayerRegistry.layerOf(name),
                    "계획 경로 메서드 '$name'이 layerOf에서 null을 반환하면 안 된다"
                )
            }
        }
    }
}
