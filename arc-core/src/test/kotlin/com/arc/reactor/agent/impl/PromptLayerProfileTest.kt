package com.arc.reactor.agent.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [PromptLayerProfile] 및 [PromptLayerRegistry] 확장 함수 단위 테스트.
 *
 * R235: 프로파일 데이터 모델의 불변식과 introspection 동작을 검증한다.
 * 핵심: R220 Golden snapshot은 여전히 불변 (프로파일은 passive).
 */
class PromptLayerProfileTest {

    @Nested
    inner class Constants {

        @Test
        fun `ALL_LAYERS는 6개 계층을 모두 포함해야 한다`() {
            assertEquals(6, PromptLayerProfile.ALL_LAYERS.enabledLayers.size) {
                "6개 계층 모두"
            }
            PromptLayer.values().forEach { layer ->
                assertTrue(PromptLayerProfile.ALL_LAYERS.isEnabled(layer)) {
                    "$layer 가 ALL_LAYERS에 포함되어야 한다"
                }
            }
        }

        @Test
        fun `MINIMAL은 정체성+안전+메모리만 포함해야 한다`() {
            val minimal = PromptLayerProfile.MINIMAL
            assertEquals(3, minimal.enabledLayers.size) { "3개 계층" }
            assertTrue(minimal.isEnabled(PromptLayer.IDENTITY))
            assertTrue(minimal.isEnabled(PromptLayer.SAFETY))
            assertTrue(minimal.isEnabled(PromptLayer.MEMORY_HINT))
            assertFalse(minimal.isEnabled(PromptLayer.TOOL_POLICY))
            assertFalse(minimal.isEnabled(PromptLayer.WORKSPACE_POLICY))
            assertFalse(minimal.isEnabled(PromptLayer.RESPONSE_STYLE))
        }

        @Test
        fun `WORKSPACE_FOCUSED는 5개 계층을 포함해야 한다 (MEMORY_HINT 제외)`() {
            val ws = PromptLayerProfile.WORKSPACE_FOCUSED
            assertEquals(5, ws.enabledLayers.size)
            assertTrue(ws.isEnabled(PromptLayer.IDENTITY))
            assertTrue(ws.isEnabled(PromptLayer.SAFETY))
            assertTrue(ws.isEnabled(PromptLayer.TOOL_POLICY))
            assertTrue(ws.isEnabled(PromptLayer.WORKSPACE_POLICY))
            assertTrue(ws.isEnabled(PromptLayer.RESPONSE_STYLE))
            assertFalse(ws.isEnabled(PromptLayer.MEMORY_HINT)) {
                "MEMORY_HINT는 WORKSPACE_FOCUSED에서 제외"
            }
        }

        @Test
        fun `SAFETY_ONLY는 2개 계층만 포함해야 한다`() {
            val safety = PromptLayerProfile.SAFETY_ONLY
            assertEquals(2, safety.enabledLayers.size)
            assertTrue(safety.isEnabled(PromptLayer.IDENTITY))
            assertTrue(safety.isEnabled(PromptLayer.SAFETY))
            assertFalse(safety.isEnabled(PromptLayer.TOOL_POLICY))
            assertFalse(safety.isEnabled(PromptLayer.MEMORY_HINT))
        }

        @Test
        fun `EMPTY는 어떤 계층도 포함하지 않아야 한다`() {
            assertTrue(PromptLayerProfile.EMPTY.isEmpty())
            assertEquals(0, PromptLayerProfile.EMPTY.enabledLayers.size)
            PromptLayer.values().forEach { layer ->
                assertFalse(PromptLayerProfile.EMPTY.isEnabled(layer)) {
                    "EMPTY는 $layer 를 포함하지 않아야 한다"
                }
            }
        }
    }

    @Nested
    inner class Equality {

        @Test
        fun `동일 계층 집합을 가진 두 프로파일은 동등해야 한다`() {
            val p1 = PromptLayerProfile(
                enabledLayers = setOf(PromptLayer.IDENTITY, PromptLayer.SAFETY)
            )
            val p2 = PromptLayerProfile(
                enabledLayers = setOf(PromptLayer.SAFETY, PromptLayer.IDENTITY)
            )
            assertEquals(p1, p2) { "Set 순서는 무관하므로 동등" }
        }

        @Test
        fun `SAFETY_ONLY와 수동 구성된 동일 프로파일은 동등해야 한다`() {
            val manual = PromptLayerProfile(
                enabledLayers = setOf(PromptLayer.IDENTITY, PromptLayer.SAFETY)
            )
            assertEquals(PromptLayerProfile.SAFETY_ONLY, manual)
        }

        @Test
        fun `다른 계층 집합을 가진 두 프로파일은 동등하지 않아야 한다`() {
            assertNotEquals(
                PromptLayerProfile.MINIMAL,
                PromptLayerProfile.WORKSPACE_FOCUSED
            )
        }
    }

    @Nested
    inner class BuilderMethods {

        @Test
        fun `withLayer는 이미 있는 계층을 추가해도 안전해야 한다`() {
            val profile = PromptLayerProfile.MINIMAL.withLayer(PromptLayer.IDENTITY)
            assertEquals(PromptLayerProfile.MINIMAL, profile) {
                "이미 있는 계층 추가는 no-op"
            }
        }

        @Test
        fun `withLayer는 새 계층을 추가할 수 있어야 한다`() {
            val added = PromptLayerProfile.MINIMAL.withLayer(PromptLayer.TOOL_POLICY)
            assertEquals(4, added.enabledLayers.size) { "3 + 1 = 4" }
            assertTrue(added.isEnabled(PromptLayer.TOOL_POLICY))
        }

        @Test
        fun `withoutLayer는 포함된 계층을 제거할 수 있어야 한다`() {
            val removed = PromptLayerProfile.ALL_LAYERS.withoutLayer(PromptLayer.RESPONSE_STYLE)
            assertEquals(5, removed.enabledLayers.size)
            assertFalse(removed.isEnabled(PromptLayer.RESPONSE_STYLE))
        }

        @Test
        fun `withoutLayer는 포함되지 않은 계층 제거도 안전해야 한다`() {
            val removed = PromptLayerProfile.MINIMAL.withoutLayer(PromptLayer.TOOL_POLICY)
            assertEquals(PromptLayerProfile.MINIMAL, removed) {
                "포함되지 않은 계층 제거는 no-op"
            }
        }

        @Test
        fun `withLayers는 여러 계층을 한 번에 추가할 수 있어야 한다`() {
            val expanded = PromptLayerProfile.SAFETY_ONLY.withLayers(
                PromptLayer.TOOL_POLICY,
                PromptLayer.WORKSPACE_POLICY
            )
            assertEquals(4, expanded.enabledLayers.size) { "2 + 2 = 4" }
            assertTrue(expanded.isEnabled(PromptLayer.TOOL_POLICY))
            assertTrue(expanded.isEnabled(PromptLayer.WORKSPACE_POLICY))
        }

        @Test
        fun `withoutLayers는 여러 계층을 한 번에 제거할 수 있어야 한다`() {
            val reduced = PromptLayerProfile.ALL_LAYERS.withoutLayers(
                PromptLayer.RESPONSE_STYLE,
                PromptLayer.MEMORY_HINT
            )
            assertEquals(4, reduced.enabledLayers.size) { "6 - 2 = 4" }
            assertFalse(reduced.isEnabled(PromptLayer.RESPONSE_STYLE))
            assertFalse(reduced.isEnabled(PromptLayer.MEMORY_HINT))
        }

        @Test
        fun `빌더 메서드는 불변 — 원본 프로파일이 변경되지 않아야 한다`() {
            val original = PromptLayerProfile.MINIMAL
            original.withLayer(PromptLayer.TOOL_POLICY)
            assertEquals(3, original.enabledLayers.size) {
                "원본 프로파일은 여전히 3개 계층"
            }
        }
    }

    @Nested
    inner class DisabledLayersMethod {

        @Test
        fun `ALL_LAYERS의 disabledLayers는 빈 집합이어야 한다`() {
            assertTrue(PromptLayerProfile.ALL_LAYERS.disabledLayers().isEmpty())
        }

        @Test
        fun `EMPTY의 disabledLayers는 6개 계층 전체여야 한다`() {
            assertEquals(6, PromptLayerProfile.EMPTY.disabledLayers().size)
        }

        @Test
        fun `MINIMAL의 disabledLayers는 3개여야 한다`() {
            val disabled = PromptLayerProfile.MINIMAL.disabledLayers()
            assertEquals(3, disabled.size)
            assertTrue(disabled.contains(PromptLayer.TOOL_POLICY))
            assertTrue(disabled.contains(PromptLayer.WORKSPACE_POLICY))
            assertTrue(disabled.contains(PromptLayer.RESPONSE_STYLE))
        }

        @Test
        fun `enabled + disabled 합집합은 항상 6개여야 한다`() {
            val profiles = listOf(
                PromptLayerProfile.ALL_LAYERS,
                PromptLayerProfile.MINIMAL,
                PromptLayerProfile.WORKSPACE_FOCUSED,
                PromptLayerProfile.SAFETY_ONLY,
                PromptLayerProfile.EMPTY
            )
            profiles.forEach { profile ->
                val union = profile.enabledLayers + profile.disabledLayers()
                assertEquals(6, union.size) {
                    "enabled + disabled는 6개 계층 전체여야 한다: $profile"
                }
            }
        }
    }

    @Nested
    inner class StateChecks {

        @Test
        fun `ALL_LAYERS는 isFullyEnabled true여야 한다`() {
            assertTrue(PromptLayerProfile.ALL_LAYERS.isFullyEnabled())
            assertFalse(PromptLayerProfile.ALL_LAYERS.isEmpty())
        }

        @Test
        fun `EMPTY는 isEmpty true여야 한다`() {
            assertTrue(PromptLayerProfile.EMPTY.isEmpty())
            assertFalse(PromptLayerProfile.EMPTY.isFullyEnabled())
        }

        @Test
        fun `MINIMAL은 isFullyEnabled도 isEmpty도 false여야 한다`() {
            assertFalse(PromptLayerProfile.MINIMAL.isFullyEnabled())
            assertFalse(PromptLayerProfile.MINIMAL.isEmpty())
        }
    }

    @Nested
    inner class RegistryFilterExtension {

        @Test
        fun `ALL_LAYERS 필터는 모든 분류된 메서드를 반환해야 한다`() {
            val filtered = PromptLayerRegistry.filterMethodsByProfile(
                PromptLayerProfile.ALL_LAYERS
            )
            assertEquals(
                PromptLayerRegistry.allClassifiedMethods(),
                filtered
            ) { "ALL_LAYERS == allClassifiedMethods" }
        }

        @Test
        fun `EMPTY 프로파일 필터는 빈 집합이어야 한다`() {
            val filtered = PromptLayerRegistry.filterMethodsByProfile(
                PromptLayerProfile.EMPTY
            )
            assertTrue(filtered.isEmpty()) { "EMPTY → no methods" }
        }

        @Test
        fun `MINIMAL 필터는 IDENTITY+SAFETY+MEMORY_HINT 메서드만 포함해야 한다`() {
            val filtered = PromptLayerRegistry.filterMethodsByProfile(
                PromptLayerProfile.MINIMAL
            )
            assertTrue(filtered.isNotEmpty()) { "최소 프로파일도 여러 메서드 포함" }

            // 모든 반환 메서드는 허용된 계층이어야 함
            filtered.forEach { name ->
                val layer = PromptLayerRegistry.layerOf(name)
                assertTrue(
                    layer == PromptLayer.IDENTITY ||
                        layer == PromptLayer.SAFETY ||
                        layer == PromptLayer.MEMORY_HINT
                ) {
                    "메서드 '$name'의 계층 $layer 이 MINIMAL에 속해야 한다"
                }
            }
        }

        @Test
        fun `MINIMAL은 특정 핵심 메서드를 포함해야 한다`() {
            val filtered = PromptLayerRegistry.filterMethodsByProfile(
                PromptLayerProfile.MINIMAL
            )
            // IDENTITY
            assertTrue("appendLanguageRule" in filtered)
            // MEMORY_HINT
            assertTrue("appendConversationHistoryRule" in filtered)
            // SAFETY
            assertTrue("appendReadOnlyPolicy" in filtered)
            assertTrue("appendMutationRefusal" in filtered)
            assertTrue("appendPreventReservedPhrasesFinalReminder" in filtered)
        }

        @Test
        fun `MINIMAL은 TOOL_POLICY 메서드를 포함하지 않아야 한다`() {
            val filtered = PromptLayerRegistry.filterMethodsByProfile(
                PromptLayerProfile.MINIMAL
            )
            // TOOL_POLICY에 해당하는 메서드는 제외되어야 함
            assertFalse("appendDuplicateToolCallPreventionHint" in filtered)
            assertFalse("appendConfluenceToolForcing" in filtered)
            assertFalse("appendJiraToolForcing" in filtered)
        }

        @Test
        fun `WORKSPACE_FOCUSED는 TOOL_POLICY 메서드를 포함해야 한다`() {
            val filtered = PromptLayerRegistry.filterMethodsByProfile(
                PromptLayerProfile.WORKSPACE_FOCUSED
            )
            assertTrue("appendDuplicateToolCallPreventionHint" in filtered)
            assertTrue("appendJiraToolForcing" in filtered)
            assertTrue("appendResponseQualityInstruction" in filtered)
            // 하지만 MEMORY_HINT는 제외
            assertFalse("appendConversationHistoryRule" in filtered) {
                "WORKSPACE_FOCUSED는 MEMORY_HINT 메서드 제외"
            }
        }

        @Test
        fun `계층 조합의 필터는 각 계층의 합집합이어야 한다`() {
            val identityOnly = PromptLayerProfile(setOf(PromptLayer.IDENTITY))
            val safetyOnly = PromptLayerProfile(setOf(PromptLayer.SAFETY))
            val both = PromptLayerProfile.SAFETY_ONLY  // IDENTITY + SAFETY

            val identityFiltered = PromptLayerRegistry.filterMethodsByProfile(identityOnly)
            val safetyFiltered = PromptLayerRegistry.filterMethodsByProfile(safetyOnly)
            val bothFiltered = PromptLayerRegistry.filterMethodsByProfile(both)

            assertEquals(
                identityFiltered + safetyFiltered,
                bothFiltered
            ) { "합집합 불변식" }
        }
    }

    @Nested
    inner class RegistryIsMethodEnabledExtension {

        @Test
        fun `ALL_LAYERS에서는 모든 등록 메서드가 활성화되어야 한다`() {
            PromptLayerRegistry.allClassifiedMethods().forEach { name ->
                assertTrue(
                    PromptLayerRegistry.isMethodEnabled(name, PromptLayerProfile.ALL_LAYERS)
                ) {
                    "$name 이 ALL_LAYERS에서 활성화되어야 한다"
                }
            }
        }

        @Test
        fun `EMPTY에서는 모든 메서드가 비활성화되어야 한다`() {
            PromptLayerRegistry.allClassifiedMethods().forEach { name ->
                assertFalse(
                    PromptLayerRegistry.isMethodEnabled(name, PromptLayerProfile.EMPTY)
                ) {
                    "$name 이 EMPTY에서 비활성화되어야 한다"
                }
            }
        }

        @Test
        fun `알 수 없는 메서드는 어떤 프로파일에서도 false를 반환해야 한다`() {
            assertFalse(
                PromptLayerRegistry.isMethodEnabled(
                    "appendNonExistentMethod",
                    PromptLayerProfile.ALL_LAYERS
                )
            )
        }

        @Test
        fun `MINIMAL에서 appendLanguageRule은 활성화되어야 한다`() {
            assertTrue(
                PromptLayerRegistry.isMethodEnabled(
                    "appendLanguageRule",
                    PromptLayerProfile.MINIMAL
                )
            ) { "IDENTITY 계층은 MINIMAL에 포함" }
        }

        @Test
        fun `MINIMAL에서 appendJiraToolForcing은 비활성화되어야 한다`() {
            assertFalse(
                PromptLayerRegistry.isMethodEnabled(
                    "appendJiraToolForcing",
                    PromptLayerProfile.MINIMAL
                )
            ) { "TOOL_POLICY 계층은 MINIMAL에 미포함" }
        }
    }

    @Nested
    inner class ByteIdenticalInvariance {

        /**
         * 핵심 불변식: PromptLayerProfile을 도입해도 R220 Golden snapshot은 여전히 불변이어야
         * 한다. 이 테스트는 프로파일이 passive 임을 명시적으로 선언한다.
         *
         * 실제 byte-identical 검증은 `SystemPromptBuilderGoldenSnapshotTest`가 수행한다.
         * 여기서는 PromptLayerProfile이 SystemPromptBuilder를 건드리지 않음을 문서화한다.
         */
        @Test
        fun `프로파일은 passive — PromptLayerRegistry는 수정되지 않아야 한다`() {
            // 임의 프로파일로 필터링해도 레지스트리 자체는 불변
            PromptLayerRegistry.filterMethodsByProfile(PromptLayerProfile.MINIMAL)
            PromptLayerRegistry.filterMethodsByProfile(PromptLayerProfile.EMPTY)

            // 레지스트리의 allClassifiedMethods는 여전히 동일
            val expectedSize = PromptLayerRegistry.mainPathMethods().size +
                PromptLayerRegistry.planningPathMethods().size
            assertEquals(expectedSize, PromptLayerRegistry.allClassifiedMethods().size) {
                "레지스트리 크기는 프로파일 필터링 호출 후에도 동일해야 한다"
            }
        }
    }
}
