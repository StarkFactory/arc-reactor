package com.arc.reactor.hook

import com.arc.reactor.agent.config.ToolPolicyProperties
import com.arc.reactor.hook.impl.WriteToolBlockHook
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WriteToolBlockHookTest {

    @Test
    fun `blocks configured write tool on slack channel`() = runTest {
        val hook = WriteToolBlockHook(
            ToolPolicyProperties(
                enabled = true,
                writeToolNames = setOf("jira_create_issue"),
                denyWriteChannels = setOf("slack"),
                denyWriteMessage = "blocked"
            )
        )

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
        assertTrue(result is HookResult.Reject)
        assertEquals("blocked", (result as HookResult.Reject).reason)
    }

    @Test
    fun `allows write tool on non-deny channel`() = runTest {
        val hook = WriteToolBlockHook(
            ToolPolicyProperties(
                enabled = true,
                writeToolNames = setOf("jira_create_issue"),
                denyWriteChannels = setOf("slack")
            )
        )

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
        assertTrue(result is HookResult.Continue)
    }
}

