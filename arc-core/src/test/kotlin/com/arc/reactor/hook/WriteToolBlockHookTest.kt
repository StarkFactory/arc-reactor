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
    fun `blocks configured write tool on slack channel`() = runTest {
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
    fun `allows write tool on non-deny channel`() = runTest {
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
}
