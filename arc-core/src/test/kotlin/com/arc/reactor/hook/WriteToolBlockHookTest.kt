package com.arc.reactor.hook

import com.arc.reactor.agent.config.ToolPolicyProperties
import com.arc.reactor.hook.impl.WriteToolBlockHook
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.policy.tool.InMemoryToolPolicyStore
import com.arc.reactor.policy.tool.ToolPolicy
import com.arc.reactor.policy.tool.ToolExecutionPolicyEngine
import com.arc.reactor.policy.tool.ToolPolicyProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class WriteToolBlockHookTest {

    @Test
    fun `configured write tool on slack channel를 차단한다`() = runTest {
        val props = ToolPolicyProperties(
            enabled = true,
            writeToolNames = setOf("jira_create_issue"),
            denyWriteChannels = setOf("slack"),
            denyWriteMessage = "blocked"
        )
        val provider = ToolPolicyProvider(props, InMemoryToolPolicyStore(ToolPolicy.fromProperties(props)))
        val hook = WriteToolBlockHook(ToolExecutionPolicyEngine(provider))

        val ctx = ToolCallContext(
            agentContext = HookContext(
                runId = "run-1",
                userId = "u1",
                userPrompt = "create issue",
                channel = "slack"
            ),
            toolName = "jira_create_issue",
            toolParams = emptyMap(),
            callIndex = 0
        )

        val result = hook.beforeToolCall(ctx)
        val reject = assertInstanceOf(HookResult.Reject::class.java, result) {
            "Slack write tool should be rejected"
        }
        assertEquals("blocked", reject.reason)
    }

    @Test
    fun `write tool on non-deny channel를 허용한다`() = runTest {
        val props = ToolPolicyProperties(
            enabled = true,
            writeToolNames = setOf("jira_create_issue"),
            denyWriteChannels = setOf("slack")
        )
        val provider = ToolPolicyProvider(props, InMemoryToolPolicyStore(ToolPolicy.fromProperties(props)))
        val hook = WriteToolBlockHook(ToolExecutionPolicyEngine(provider))

        val ctx = ToolCallContext(
            agentContext = HookContext(
                runId = "run-1",
                userId = "u1",
                userPrompt = "create issue",
                channel = "web"
            ),
            toolName = "jira_create_issue",
            toolParams = emptyMap(),
            callIndex = 0
        )

        val result = hook.beforeToolCall(ctx)
        assertInstanceOf(HookResult.Continue::class.java, result) {
            "Write tool on non-deny channel should continue"
        }
    }

    @Test
    fun `allows release readiness preview on slack because it은(는) read only이다`() = runTest {
        val props = ToolPolicyProperties(
            enabled = true,
            writeToolNames = setOf("work_release_readiness_pack"),
            denyWriteChannels = setOf("slack"),
            denyWriteMessage = "blocked"
        )
        val provider = ToolPolicyProvider(props, InMemoryToolPolicyStore(ToolPolicy.fromProperties(props)))
        val hook = WriteToolBlockHook(ToolExecutionPolicyEngine(provider))

        val ctx = ToolCallContext(
            agentContext = HookContext(
                runId = "run-1",
                userId = "u1",
                userPrompt = "release readiness pack",
                channel = "slack"
            ),
            toolName = "work_release_readiness_pack",
            toolParams = mapOf("dryRunActionItems" to true, "autoExecuteActionItems" to false),
            callIndex = 0
        )

        val result = hook.beforeToolCall(ctx)
        assertInstanceOf(HookResult.Continue::class.java, result) {
            "Preview-only release readiness pack should not be treated as a write tool"
        }
    }
}
