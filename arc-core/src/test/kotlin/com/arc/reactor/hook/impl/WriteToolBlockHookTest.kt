package com.arc.reactor.hook.impl

import com.arc.reactor.agent.config.ToolPolicyProperties
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.policy.tool.InMemoryToolPolicyStore
import com.arc.reactor.policy.tool.ToolExecutionPolicyEngine
import com.arc.reactor.policy.tool.ToolPolicy
import com.arc.reactor.policy.tool.ToolPolicyProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * WriteToolBlockHook 단위 테스트.
 *
 * 쓰기 도구 차단 훅의 채널/정책/예외 시나리오를 검증한다.
 */
class WriteToolBlockHookTest {

    /** 기본 쓰기 도구 설정 헬퍼 */
    private fun buildHook(
        enabled: Boolean = true,
        writeToolNames: Set<String> = setOf("jira_create_issue"),
        denyWriteChannels: Set<String> = setOf("slack"),
        allowWriteToolNamesInDenyChannels: Set<String> = emptySet(),
        allowWriteToolNamesByChannel: Map<String, Set<String>> = emptyMap(),
        denyWriteMessage: String = "차단됨"
    ): WriteToolBlockHook {
        val props = ToolPolicyProperties(
            enabled = enabled,
            writeToolNames = writeToolNames,
            denyWriteChannels = denyWriteChannels,
            allowWriteToolNamesInDenyChannels = allowWriteToolNamesInDenyChannels,
            allowWriteToolNamesByChannel = allowWriteToolNamesByChannel,
            denyWriteMessage = denyWriteMessage
        )
        val provider = ToolPolicyProvider(props, InMemoryToolPolicyStore(ToolPolicy.fromProperties(props)))
        return WriteToolBlockHook(ToolExecutionPolicyEngine(provider))
    }

    /** 도구 호출 컨텍스트 생성 헬퍼 */
    private fun callContext(
        toolName: String,
        channel: String? = "slack",
        toolParams: Map<String, Any?> = emptyMap()
    ): ToolCallContext = ToolCallContext(
        agentContext = HookContext(
            runId = "run-1",
            userId = "user-1",
            userPrompt = "test",
            channel = channel
        ),
        toolName = toolName,
        toolParams = toolParams,
        callIndex = 0
    )

    @Nested
    inner class 훅_속성 {

        private lateinit var hook: WriteToolBlockHook

        @BeforeEach
        fun setup() {
            hook = buildHook()
        }

        @Test
        fun `order가 10이어야 한다`() {
            assertEquals(10, hook.order) { "WriteToolBlockHook의 order는 10이어야 한다" }
        }

        @Test
        fun `failOnError가 true여야 한다`() {
            assertTrue(hook.failOnError) { "정책 오류 시 fail-close 동작을 위해 failOnError는 true여야 한다" }
        }
    }

    @Nested
    inner class 정책_비활성화 {

        @Test
        fun `정책_비활성화시_거부채널_쓰기도구도_허용해야_한다`() = runTest {
            val hook = buildHook(enabled = false)
            val result = hook.beforeToolCall(callContext("jira_create_issue", channel = "slack"))
            assertInstanceOf(HookResult.Continue::class.java, result) {
                "정책이 비활성화되면 모든 도구를 허용해야 한다"
            }
        }

        @Test
        fun `쓰기도구목록_비어있으면_거부채널에서도_허용해야_한다`() = runTest {
            val hook = buildHook(writeToolNames = emptySet())
            val result = hook.beforeToolCall(callContext("jira_create_issue", channel = "slack"))
            assertInstanceOf(HookResult.Continue::class.java, result) {
                "쓰기 도구 목록이 비어있으면 차단할 대상이 없으므로 허용해야 한다"
            }
        }
    }

    @Nested
    inner class 채널_기반_허용 {

        @Test
        fun `null_채널에서는_쓰기도구도_허용해야_한다`() = runTest {
            val hook = buildHook()
            val result = hook.beforeToolCall(callContext("jira_create_issue", channel = null))
            assertInstanceOf(HookResult.Continue::class.java, result) {
                "채널 정보가 없으면 쓰기 도구를 차단하지 않아야 한다"
            }
        }

        @Test
        fun `거부_채널_아닌_web채널에서는_쓰기도구도_허용해야_한다`() = runTest {
            val hook = buildHook()
            val result = hook.beforeToolCall(callContext("jira_create_issue", channel = "web"))
            assertInstanceOf(HookResult.Continue::class.java, result) {
                "web 채널은 거부 채널이 아니므로 쓰기 도구를 허용해야 한다"
            }
        }

        @Test
        fun `거부채널에서_쓰기도구_아닌_도구는_허용해야_한다`() = runTest {
            val hook = buildHook(writeToolNames = setOf("jira_create_issue"))
            val result = hook.beforeToolCall(callContext("jira_search", channel = "slack"))
            assertInstanceOf(HookResult.Continue::class.java, result) {
                "slack 채널이어도 쓰기 도구가 아닌 경우 허용해야 한다"
            }
        }

        @Test
        fun `채널명_대소문자_무시하고_정확히_차단해야_한다`() = runTest {
            val hook = buildHook()
            val result = hook.beforeToolCall(callContext("jira_create_issue", channel = "SLACK"))
            assertInstanceOf(HookResult.Reject::class.java, result) {
                "채널명은 소문자 정규화 후 비교해야 한다 — SLACK도 slack과 동일하게 차단"
            }
        }
    }

    @Nested
    inner class 쓰기_도구_차단 {

        @Test
        fun `slack_채널에서_쓰기도구_차단해야_한다`() = runTest {
            val hook = buildHook(denyWriteMessage = "Slack에서 쓰기 금지")
            val result = hook.beforeToolCall(callContext("jira_create_issue", channel = "slack"))
            val reject = assertInstanceOf(HookResult.Reject::class.java, result) {
                "slack 채널에서 쓰기 도구는 Reject를 반환해야 한다"
            }
            assertEquals("Slack에서 쓰기 금지", reject.reason) {
                "거부 메시지가 정책의 denyWriteMessage와 일치해야 한다"
            }
        }

        @Test
        fun `여러_쓰기도구_모두_slack에서_차단해야_한다`() = runTest {
            val hook = buildHook(
                writeToolNames = setOf("jira_create_issue", "github_merge_pr", "deploy_prod")
            )
            val tools = listOf("jira_create_issue", "github_merge_pr", "deploy_prod")
            for (tool in tools) {
                val result = hook.beforeToolCall(callContext(tool, channel = "slack"))
                assertInstanceOf(HookResult.Reject::class.java, result) {
                    "$tool 은(는) slack 채널에서 차단되어야 한다"
                }
            }
        }
    }

    @Nested
    inner class 전역_예외_도구 {

        @Test
        fun `allowWriteToolNamesInDenyChannels에_등록된_도구는_slack에서도_허용해야_한다`() = runTest {
            val hook = buildHook(
                writeToolNames = setOf("jira_create_issue"),
                allowWriteToolNamesInDenyChannels = setOf("jira_create_issue")
            )
            val result = hook.beforeToolCall(callContext("jira_create_issue", channel = "slack"))
            assertInstanceOf(HookResult.Continue::class.java, result) {
                "전역 예외 목록에 있는 쓰기 도구는 거부 채널에서도 허용해야 한다"
            }
        }

        @Test
        fun `예외_도구가_아닌_다른_쓰기도구는_여전히_차단해야_한다`() = runTest {
            val hook = buildHook(
                writeToolNames = setOf("jira_create_issue", "deploy_prod"),
                allowWriteToolNamesInDenyChannels = setOf("jira_create_issue")
            )
            val result = hook.beforeToolCall(callContext("deploy_prod", channel = "slack"))
            assertInstanceOf(HookResult.Reject::class.java, result) {
                "전역 예외가 아닌 다른 쓰기 도구는 slack에서 차단되어야 한다"
            }
        }
    }

    @Nested
    inner class 채널별_허용_도구 {

        @Test
        fun `allowWriteToolNamesByChannel_slack에_등록된_도구는_허용해야_한다`() = runTest {
            val hook = buildHook(
                writeToolNames = setOf("jira_create_issue"),
                allowWriteToolNamesByChannel = mapOf("slack" to setOf("jira_create_issue"))
            )
            val result = hook.beforeToolCall(callContext("jira_create_issue", channel = "slack"))
            assertInstanceOf(HookResult.Continue::class.java, result) {
                "채널별 허용 목록에 등록된 쓰기 도구는 해당 채널에서 허용해야 한다"
            }
        }

        @Test
        fun `다른채널_허용목록은_slack에_적용되지_않아야_한다`() = runTest {
            val hook = buildHook(
                writeToolNames = setOf("jira_create_issue"),
                allowWriteToolNamesByChannel = mapOf("teams" to setOf("jira_create_issue"))
            )
            val result = hook.beforeToolCall(callContext("jira_create_issue", channel = "slack"))
            assertInstanceOf(HookResult.Reject::class.java, result) {
                "teams 채널용 허용 목록은 slack에 적용되지 않아야 한다"
            }
        }
    }

    @Nested
    inner class 읽기전용_미리보기 {

        @Test
        fun `work_action_items_to_jira_dryRun_true이면_허용해야_한다`() = runTest {
            val hook = buildHook(writeToolNames = setOf("work_action_items_to_jira"))
            val result = hook.beforeToolCall(
                callContext(
                    "work_action_items_to_jira",
                    channel = "slack",
                    toolParams = mapOf("dryRun" to true)
                )
            )
            assertInstanceOf(HookResult.Continue::class.java, result) {
                "dryRun=true인 work_action_items_to_jira는 읽기 전용이므로 허용해야 한다"
            }
        }

        @Test
        fun `work_action_items_to_jira_dryRun_false이면_차단해야_한다`() = runTest {
            val hook = buildHook(writeToolNames = setOf("work_action_items_to_jira"))
            val result = hook.beforeToolCall(
                callContext(
                    "work_action_items_to_jira",
                    channel = "slack",
                    toolParams = mapOf("dryRun" to false)
                )
            )
            assertInstanceOf(HookResult.Reject::class.java, result) {
                "dryRun=false인 work_action_items_to_jira는 실제 쓰기이므로 차단해야 한다"
            }
        }

        @Test
        fun `work_release_readiness_pack_미리보기_모드이면_허용해야_한다`() = runTest {
            val hook = buildHook(writeToolNames = setOf("work_release_readiness_pack"))
            val result = hook.beforeToolCall(
                callContext(
                    "work_release_readiness_pack",
                    channel = "slack",
                    toolParams = mapOf("dryRunActionItems" to true, "autoExecuteActionItems" to false)
                )
            )
            assertInstanceOf(HookResult.Continue::class.java, result) {
                "dryRunActionItems=true, autoExecuteActionItems=false이면 읽기 전용 미리보기이므로 허용해야 한다"
            }
        }

        @Test
        fun `work_release_readiness_pack_autoExecuteActionItems_true이면_차단해야_한다`() = runTest {
            val hook = buildHook(writeToolNames = setOf("work_release_readiness_pack"))
            val result = hook.beforeToolCall(
                callContext(
                    "work_release_readiness_pack",
                    channel = "slack",
                    toolParams = mapOf("autoExecuteActionItems" to true)
                )
            )
            assertInstanceOf(HookResult.Reject::class.java, result) {
                "autoExecuteActionItems=true이면 실제 쓰기 작업이므로 차단해야 한다"
            }
        }

        @Test
        fun `work_release_readiness_pack_dryRunActionItems_false이면_차단해야_한다`() = runTest {
            val hook = buildHook(writeToolNames = setOf("work_release_readiness_pack"))
            val result = hook.beforeToolCall(
                callContext(
                    "work_release_readiness_pack",
                    channel = "slack",
                    toolParams = mapOf("dryRunActionItems" to false, "autoExecuteActionItems" to false)
                )
            )
            assertInstanceOf(HookResult.Reject::class.java, result) {
                "dryRunActionItems=false이면 실제 쓰기이므로 차단해야 한다"
            }
        }
    }
}
