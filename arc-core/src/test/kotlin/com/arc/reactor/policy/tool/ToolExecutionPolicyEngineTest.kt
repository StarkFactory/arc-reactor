package com.arc.reactor.policy.tool

import com.arc.reactor.agent.config.ToolPolicyDynamicProperties
import com.arc.reactor.agent.config.ToolPolicyProperties
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [ToolExecutionPolicyEngine], [ToolPolicyProvider], [InMemoryToolPolicyStore] 단위 테스트.
 *
 * 정책 평가 로직과 캐시/저장소 동작을 검증한다.
 */
class ToolExecutionPolicyEngineTest {

    // -------------------------------------------------------------------------
    // 공통 헬퍼
    // -------------------------------------------------------------------------

    /** 정책이 활성화된 기본 properties를 생성한다 */
    private fun enabledProps(
        writeToolNames: Set<String> = setOf("jira_create_issue", "confluence_update_page"),
        denyWriteChannels: Set<String> = setOf("slack"),
        allowWriteToolNamesInDenyChannels: Set<String> = emptySet(),
        allowWriteToolNamesByChannel: Map<String, Set<String>> = emptyMap(),
        denyWriteMessage: String = "Error: 이 채널에서는 허용되지 않습니다"
    ) = ToolPolicyProperties(
        enabled = true,
        dynamic = ToolPolicyDynamicProperties(enabled = false),
        writeToolNames = writeToolNames,
        denyWriteChannels = denyWriteChannels,
        allowWriteToolNamesInDenyChannels = allowWriteToolNamesInDenyChannels,
        allowWriteToolNamesByChannel = allowWriteToolNamesByChannel,
        denyWriteMessage = denyWriteMessage
    )

    /** 정책이 비활성화된 properties를 생성한다 */
    private fun disabledProps() = ToolPolicyProperties(
        enabled = false,
        dynamic = ToolPolicyDynamicProperties(enabled = false),
        writeToolNames = setOf("jira_create_issue"),
        denyWriteChannels = setOf("slack")
    )

    /** InMemoryToolPolicyStore 기반 ToolPolicyProvider를 생성한다 */
    private fun providerWith(
        props: ToolPolicyProperties,
        stored: ToolPolicy? = null
    ): ToolPolicyProvider {
        val store = InMemoryToolPolicyStore(initial = stored)
        return ToolPolicyProvider(props, store)
    }

    /** 엔진을 생성한다 */
    private fun engineWith(props: ToolPolicyProperties, stored: ToolPolicy? = null) =
        ToolExecutionPolicyEngine(providerWith(props, stored))

    // =========================================================================
    // ToolExecutionPolicyEngine 테스트
    // =========================================================================

    @Nested
    inner class Evaluate {

        @Nested
        inner class 정책비활성화 {

            @Test
            fun `정책이 비활성화되면 모든 채널에서 Allow를 반환한다`() {
                val engine = engineWith(disabledProps())

                val result = engine.evaluate("slack", "jira_create_issue")

                result shouldBe ToolExecutionDecision.Allow
            }

            @Test
            fun `정책은 활성화이나 쓰기 도구 목록이 비어있으면 Allow를 반환한다`() {
                val engine = engineWith(enabledProps(writeToolNames = emptySet()))

                val result = engine.evaluate("slack", "jira_create_issue")

                result shouldBe ToolExecutionDecision.Allow
            }
        }

        @Nested
        inner class 채널없음 {

            @Test
            fun `채널이 null이면 Allow를 반환한다`() {
                val engine = engineWith(enabledProps())

                val result = engine.evaluate(null, "jira_create_issue")

                result shouldBe ToolExecutionDecision.Allow
            }

            @Test
            fun `채널이 빈 문자열이면 Allow를 반환한다`() {
                val engine = engineWith(enabledProps())

                val result = engine.evaluate("", "jira_create_issue")

                result shouldBe ToolExecutionDecision.Allow
            }

            @Test
            fun `채널이 공백만이면 Allow를 반환한다`() {
                val engine = engineWith(enabledProps())

                val result = engine.evaluate("   ", "jira_create_issue")

                result shouldBe ToolExecutionDecision.Allow
            }
        }

        @Nested
        inner class 거부채널아님 {

            @Test
            fun `채널이 거부 채널 목록에 없으면 쓰기 도구도 Allow를 반환한다`() {
                val engine = engineWith(enabledProps(denyWriteChannels = setOf("slack")))

                val result = engine.evaluate("web", "jira_create_issue")

                result shouldBe ToolExecutionDecision.Allow
            }

            @Test
            fun `채널 대소문자가 달라도 정규화 후 거부 채널과 비교한다`() {
                val engine = engineWith(enabledProps(denyWriteChannels = setOf("slack")))

                // 거부 채널이 "slack"이고 입력이 "SLACK" → 정규화 후 동일 → 거부
                val result = engine.evaluate("SLACK", "jira_create_issue")

                result.shouldBeInstanceOf<ToolExecutionDecision.Deny> { "SLACK은 slack과 동일하게 거부되어야 한다" }
            }
        }

        @Nested
        inner class 쓰기도구아님 {

            @Test
            fun `거부 채널이지만 쓰기 도구 목록에 없는 도구는 Allow를 반환한다`() {
                val engine = engineWith(enabledProps(writeToolNames = setOf("jira_create_issue")))

                val result = engine.evaluate("slack", "jira_search")

                result shouldBe ToolExecutionDecision.Allow
            }
        }

        @Nested
        inner class 거부 {

            @Test
            fun `거부 채널에서 쓰기 도구 호출은 Deny를 반환한다`() {
                val engine = engineWith(
                    enabledProps(
                        writeToolNames = setOf("jira_create_issue"),
                        denyWriteChannels = setOf("slack"),
                        denyWriteMessage = "슬랙에서 쓰기 도구는 허용되지 않습니다"
                    )
                )

                val result = engine.evaluate("slack", "jira_create_issue")

                result.shouldBeInstanceOf<ToolExecutionDecision.Deny> { "거부 채널에서 쓰기 도구는 Deny여야 한다" }
                (result as ToolExecutionDecision.Deny).reason shouldBe "슬랙에서 쓰기 도구는 허용되지 않습니다"
            }
        }

        @Nested
        inner class 전역예외도구 {

            @Test
            fun `allowWriteToolNamesInDenyChannels에 포함된 도구는 거부 채널에서도 Allow를 반환한다`() {
                val engine = engineWith(
                    enabledProps(
                        writeToolNames = setOf("jira_create_issue", "confluence_update_page"),
                        denyWriteChannels = setOf("slack"),
                        allowWriteToolNamesInDenyChannels = setOf("jira_create_issue")
                    )
                )

                val allowed = engine.evaluate("slack", "jira_create_issue")
                val denied = engine.evaluate("slack", "confluence_update_page")

                allowed shouldBe ToolExecutionDecision.Allow
                denied.shouldBeInstanceOf<ToolExecutionDecision.Deny> { "예외 목록에 없는 도구는 Deny여야 한다" }
            }
        }

        @Nested
        inner class 채널별예외도구 {

            @Test
            fun `allowWriteToolNamesByChannel에 포함된 도구는 해당 채널에서 Allow를 반환한다`() {
                val engine = engineWith(
                    enabledProps(
                        writeToolNames = setOf("jira_create_issue"),
                        denyWriteChannels = setOf("slack"),
                        allowWriteToolNamesByChannel = mapOf("slack" to setOf("jira_create_issue"))
                    )
                )

                val result = engine.evaluate("slack", "jira_create_issue")

                result shouldBe ToolExecutionDecision.Allow
            }

            @Test
            fun `채널별 예외도구가 다른 채널에는 적용되지 않는다`() {
                val engine = engineWith(
                    enabledProps(
                        writeToolNames = setOf("jira_create_issue"),
                        denyWriteChannels = setOf("slack", "teams"),
                        allowWriteToolNamesByChannel = mapOf("slack" to setOf("jira_create_issue"))
                    )
                )

                val slackResult = engine.evaluate("slack", "jira_create_issue")
                val teamsResult = engine.evaluate("teams", "jira_create_issue")

                slackResult shouldBe ToolExecutionDecision.Allow
                teamsResult.shouldBeInstanceOf<ToolExecutionDecision.Deny> { "teams 채널 예외가 slack에만 적용되어야 한다" }
            }
        }
    }

    // =========================================================================
    // isWriteTool 테스트
    // =========================================================================

    @Nested
    inner class IsWriteTool {

        @Test
        fun `정책에 등록된 쓰기 도구는 true를 반환한다`() {
            val engine = engineWith(enabledProps(writeToolNames = setOf("jira_create_issue")))

            val result = engine.isWriteTool("jira_create_issue")

            result shouldBe true
        }

        @Test
        fun `정책에 등록되지 않은 도구는 false를 반환한다`() {
            val engine = engineWith(enabledProps(writeToolNames = setOf("jira_create_issue")))

            val result = engine.isWriteTool("jira_search")

            result shouldBe false
        }

        @Test
        fun `정책이 비활성화되면 쓰기 도구도 false를 반환한다`() {
            val engine = engineWith(disabledProps())

            val result = engine.isWriteTool("jira_create_issue")

            result shouldBe false
        }

        @Nested
        inner class DryRun읽기전용 {

            @Test
            fun `work_action_items_to_jira에 dryRun=true이면 읽기 전용으로 판단한다`() {
                val engine = engineWith(enabledProps(writeToolNames = setOf("work_action_items_to_jira")))

                val result = engine.isWriteTool("work_action_items_to_jira", mapOf("dryRun" to true))

                result shouldBe false
            }

            @Test
            fun `work_action_items_to_jira에 dryRun=false이면 쓰기 도구로 판단한다`() {
                val engine = engineWith(enabledProps(writeToolNames = setOf("work_action_items_to_jira")))

                val result = engine.isWriteTool("work_action_items_to_jira", mapOf("dryRun" to false))

                result shouldBe true
            }

            @Test
            fun `work_action_items_to_jira에 dryRun 인수가 없으면 쓰기 도구로 판단한다`() {
                val engine = engineWith(enabledProps(writeToolNames = setOf("work_action_items_to_jira")))

                val result = engine.isWriteTool("work_action_items_to_jira", emptyMap())

                result shouldBe true
            }

            @Test
            fun `work_release_readiness_pack에 autoExecute=false이고 dryRun 미지정이면 읽기 전용이다`() {
                val engine = engineWith(enabledProps(writeToolNames = setOf("work_release_readiness_pack")))

                // autoExecute=false, dryRunActionItems=null → 읽기 전용
                val result = engine.isWriteTool("work_release_readiness_pack", emptyMap())

                result shouldBe false
            }

            @Test
            fun `work_release_readiness_pack에 autoExecute=true이면 쓰기 도구다`() {
                val engine = engineWith(enabledProps(writeToolNames = setOf("work_release_readiness_pack")))

                val result = engine.isWriteTool(
                    "work_release_readiness_pack",
                    mapOf("autoExecuteActionItems" to true)
                )

                result shouldBe true
            }

            @Test
            fun `work_release_readiness_pack에 dryRunActionItems=false이면 쓰기 도구다`() {
                val engine = engineWith(enabledProps(writeToolNames = setOf("work_release_readiness_pack")))

                val result = engine.isWriteTool(
                    "work_release_readiness_pack",
                    mapOf("autoExecuteActionItems" to false, "dryRunActionItems" to false)
                )

                result shouldBe true
            }
        }
    }

    // =========================================================================
    // InMemoryToolPolicyStore 테스트
    // =========================================================================

    @Nested
    inner class InMemoryToolPolicyStoreTest {

        private fun samplePolicy(enabled: Boolean = true) = ToolPolicy(
            enabled = enabled,
            writeToolNames = setOf("tool_a"),
            denyWriteChannels = setOf("slack"),
            allowWriteToolNamesInDenyChannels = emptySet(),
            allowWriteToolNamesByChannel = emptyMap(),
            denyWriteMessage = "거부"
        )

        @Test
        fun `초기값 없이 생성하면 getOrNull은 null을 반환한다`() {
            val store = InMemoryToolPolicyStore()

            val result = store.getOrNull()

            result shouldBe null
        }

        @Test
        fun `초기값을 제공하면 getOrNull이 해당 정책을 반환한다`() {
            val initial = samplePolicy()
            val store = InMemoryToolPolicyStore(initial = initial)

            val result = store.getOrNull()

            result shouldBe initial
        }

        @Test
        fun `save하면 저장된 정책을 반환하고 getOrNull로 조회된다`() {
            val store = InMemoryToolPolicyStore()
            val policy = samplePolicy()

            val saved = store.save(policy)
            val retrieved = store.getOrNull()

            saved.enabled shouldBe true
            retrieved?.enabled shouldBe true
        }

        @Test
        fun `save를 두 번 호출하면 createdAt은 유지되고 updatedAt은 갱신된다`() {
            val store = InMemoryToolPolicyStore()
            val first = store.save(samplePolicy())
            Thread.sleep(5) // updatedAt 차이 보장
            val second = store.save(samplePolicy(enabled = false))

            second.createdAt shouldBe first.createdAt
            // updatedAt은 같거나 이후여야 한다
            (second.updatedAt >= first.updatedAt) shouldBe true
        }

        @Test
        fun `delete 후 getOrNull은 null을 반환한다`() {
            val store = InMemoryToolPolicyStore(initial = samplePolicy())

            val deleted = store.delete()
            val result = store.getOrNull()

            deleted shouldBe true
            result shouldBe null
        }

        @Test
        fun `빈 저장소에서 delete는 false를 반환한다`() {
            val store = InMemoryToolPolicyStore()

            val result = store.delete()

            result shouldBe false
        }
    }

    // =========================================================================
    // ToolPolicyProvider 테스트
    // =========================================================================

    @Nested
    inner class ToolPolicyProviderTest {

        @Test
        fun `properties enabled=false이면 enabled=false인 정책을 반환한다`() {
            val props = disabledProps()
            val provider = providerWith(props)

            val policy = provider.current()

            policy.enabled shouldBe false
            policy.writeToolNames shouldBe emptySet()
        }

        @Test
        fun `동적 정책 비활성화이면 properties에서 정책을 직접 생성한다`() {
            val props = enabledProps(writeToolNames = setOf("tool_x"))
            val provider = providerWith(props)

            val policy = provider.current()

            policy.enabled shouldBe true
            policy.writeToolNames shouldBe setOf("tool_x")
        }

        @Test
        fun `동적 정책 활성화 시 저장소에 값이 있으면 저장소 정책을 반환한다`() {
            val stored = ToolPolicy(
                enabled = true,
                writeToolNames = setOf("stored_tool"),
                denyWriteChannels = setOf("slack"),
                allowWriteToolNamesInDenyChannels = emptySet(),
                allowWriteToolNamesByChannel = emptyMap(),
                denyWriteMessage = "저장소 메시지"
            )
            val props = ToolPolicyProperties(
                enabled = true,
                dynamic = ToolPolicyDynamicProperties(enabled = true, refreshMs = 60_000),
                writeToolNames = setOf("props_tool"),
                denyWriteChannels = setOf("slack")
            )
            val provider = providerWith(props, stored = stored)

            val policy = provider.current()

            policy.writeToolNames shouldBe setOf("stored_tool")
        }

        @Test
        fun `동적 정책 활성화이고 저장소가 비어있으면 properties로 폴백한다`() {
            val props = ToolPolicyProperties(
                enabled = true,
                dynamic = ToolPolicyDynamicProperties(enabled = true, refreshMs = 60_000),
                writeToolNames = setOf("props_tool"),
                denyWriteChannels = setOf("slack")
            )
            val provider = providerWith(props, stored = null)

            val policy = provider.current()

            policy.writeToolNames shouldBe setOf("props_tool")
        }

        @Test
        fun `invalidate 후 다음 호출에서 저장소를 다시 로드한다`() {
            val store = InMemoryToolPolicyStore()
            val props = ToolPolicyProperties(
                enabled = true,
                dynamic = ToolPolicyDynamicProperties(enabled = true, refreshMs = 60_000),
                writeToolNames = setOf("original_tool"),
                denyWriteChannels = setOf("slack")
            )
            val provider = ToolPolicyProvider(props, store)

            // 첫 로드 (store 없으므로 props 기반)
            val first = provider.current()
            first.writeToolNames shouldBe setOf("original_tool")

            // store에 새 정책 저장 후 invalidate
            store.save(
                ToolPolicy(
                    enabled = true,
                    writeToolNames = setOf("new_tool"),
                    denyWriteChannels = setOf("slack"),
                    allowWriteToolNamesInDenyChannels = emptySet(),
                    allowWriteToolNamesByChannel = emptyMap(),
                    denyWriteMessage = "거부"
                )
            )
            provider.invalidate()

            val second = provider.current()

            second.writeToolNames shouldBe setOf("new_tool")
        }

        @Nested
        inner class 정규화 {

            @Test
            fun `도구 이름의 앞뒤 공백이 제거된다`() {
                val props = enabledProps(writeToolNames = setOf("  tool_a  ", "tool_b"))
                val provider = providerWith(props)

                val policy = provider.current()

                policy.writeToolNames shouldBe setOf("tool_a", "tool_b")
            }

            @Test
            fun `빈 문자열 도구 이름은 필터링된다`() {
                val props = enabledProps(writeToolNames = setOf("tool_a", "", "   "))
                val provider = providerWith(props)

                val policy = provider.current()

                policy.writeToolNames shouldBe setOf("tool_a")
            }

            @Test
            fun `채널 이름은 소문자로 정규화된다`() {
                val props = enabledProps(denyWriteChannels = setOf("SLACK", "Teams"))
                val provider = providerWith(props)

                val policy = provider.current()

                policy.denyWriteChannels shouldBe setOf("slack", "teams")
            }

            @Test
            fun `채널 이름의 공백이 제거된다`() {
                val props = enabledProps(denyWriteChannels = setOf("  slack  "))
                val provider = providerWith(props)

                val policy = provider.current()

                policy.denyWriteChannels shouldBe setOf("slack")
            }

            @Test
            fun `거부 메시지가 빈 문자열이면 properties 기본값을 사용한다`() {
                val defaultMsg = "기본 거부 메시지"
                val props = ToolPolicyProperties(
                    enabled = true,
                    dynamic = ToolPolicyDynamicProperties(enabled = false),
                    denyWriteMessage = defaultMsg
                )
                val stored = ToolPolicy(
                    enabled = true,
                    writeToolNames = emptySet(),
                    denyWriteChannels = emptySet(),
                    allowWriteToolNamesInDenyChannels = emptySet(),
                    allowWriteToolNamesByChannel = emptyMap(),
                    denyWriteMessage = "   " // 공백만 → 폴백
                )
                val store = InMemoryToolPolicyStore(initial = stored)
                val provider = ToolPolicyProvider(props, store)

                val policy = provider.current()

                policy.denyWriteMessage shouldBe defaultMsg
            }

            @Test
            fun `채널별 허용 도구 맵의 키와 값도 정규화된다`() {
                val props = enabledProps(
                    allowWriteToolNamesByChannel = mapOf(
                        "SLACK" to setOf("  tool_a  ", ""),
                        "  " to setOf("tool_b") // 공백 키 → 제거
                    )
                )
                val provider = providerWith(props)

                val policy = provider.current()

                // 빈 값 맵 엔트리도 제거됨 (tool_a만 남음 → 비어있지 않음)
                policy.allowWriteToolNamesByChannel shouldBe mapOf("slack" to setOf("tool_a"))
            }
        }
    }

    // =========================================================================
    // ToolPolicy.fromProperties 테스트
    // =========================================================================

    @Nested
    inner class ToolPolicyFromProperties {

        @Test
        fun `fromProperties는 properties 값을 그대로 매핑한다`() {
            val props = enabledProps(
                writeToolNames = setOf("tool_a"),
                denyWriteChannels = setOf("slack"),
                denyWriteMessage = "거부 메시지"
            )

            val policy = ToolPolicy.fromProperties(props)

            policy.enabled shouldBe true
            policy.writeToolNames shouldBe setOf("tool_a")
            policy.denyWriteChannels shouldBe setOf("slack")
            policy.denyWriteMessage shouldBe "거부 메시지"
        }
    }
}
