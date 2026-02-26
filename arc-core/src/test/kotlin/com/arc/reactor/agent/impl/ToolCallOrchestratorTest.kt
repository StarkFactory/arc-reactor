package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.approval.ToolApprovalResponse
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.tool.ToolCallback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import java.util.concurrent.atomic.AtomicInteger

class ToolCallOrchestratorTest {

    private val hookContext = HookContext(
        runId = "run-1",
        userId = "user-1",
        userPrompt = "prompt"
    )

    @Test
    fun `should block tool call when not in allowlist`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() }
        )
        val toolCall = toolCall(id = "id-1", name = "search")

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = emptyList(),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = setOf("calculator")
        )

        assertEquals(1, responses.size)
        assertTrue(responses[0].responseData().contains("not allowed"))
    }

    @Test
    fun `should fail closed when approval required but store is missing`() = runBlocking {
        val approvalPolicy = mockk<ToolApprovalPolicy>()
        val tool = mockk<ToolCallback>()
        val toolCall = toolCall(id = "id-1", name = "danger_tool")

        every { approvalPolicy.requiresApproval("danger_tool", any()) } returns true
        every { tool.name } returns "danger_tool"
        every { tool.description } returns "danger"
        every { tool.inputSchema } returns """{"type":"object","properties":{}}"""
        coEvery { tool.call(any()) } returns "ok"

        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = approvalPolicy,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("q" to "arc") }
        )

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(tool)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertTrue(responses[0].responseData().contains("approval store unavailable", ignoreCase = true))
        coVerify(exactly = 0) { tool.call(any()) }
    }

    @Test
    fun `should fail closed when approval check throws`() = runBlocking {
        val approvalPolicy = mockk<ToolApprovalPolicy>()
        val pendingApprovalStore = mockk<PendingApprovalStore>()
        val tool = mockk<ToolCallback>()
        val toolCall = toolCall(id = "id-1", name = "danger_tool")

        every { approvalPolicy.requiresApproval("danger_tool", any()) } returns true
        every { tool.name } returns "danger_tool"
        every { tool.description } returns "danger"
        every { tool.inputSchema } returns """{"type":"object","properties":{}}"""
        coEvery { tool.call(any()) } returns "ok"
        coEvery {
            pendingApprovalStore.requestApproval(
                runId = any(),
                userId = any(),
                toolName = any(),
                arguments = any(),
                timeoutMs = any()
            )
        } throws IllegalStateException("approval backend down")

        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = approvalPolicy,
            pendingApprovalStore = pendingApprovalStore,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("q" to "arc") }
        )

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(tool)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertTrue(responses[0].responseData().contains("Tool call blocked"))
        assertTrue(responses[0].responseData().contains("Approval check failed"))
        coVerify(exactly = 0) { tool.call(any()) }
    }

    @Test
    fun `should execute after hook when approval rejected by human`() = runBlocking {
        val approvalPolicy = mockk<ToolApprovalPolicy>()
        val pendingApprovalStore = mockk<PendingApprovalStore>()
        val hookExecutor = mockk<HookExecutor>()
        val tool = mockk<ToolCallback>()
        val toolCall = toolCall(id = "id-1", name = "danger_tool")
        val context = HookContext(runId = "run-1", userId = "user-1", userPrompt = "prompt")
        val capturedContext = slot<ToolCallContext>()
        val capturedResult = slot<ToolCallResult>()

        every { approvalPolicy.requiresApproval("danger_tool", any()) } returns true
        every { tool.name } returns "danger_tool"
        every { tool.description } returns "danger"
        every { tool.inputSchema } returns """{"type":"object","properties":{}}"""
        coEvery { tool.call(any()) } returns "ok"
        coEvery {
            pendingApprovalStore.requestApproval(
                runId = any(),
                userId = any(),
                toolName = any(),
                arguments = any(),
                timeoutMs = any()
            )
        } returns ToolApprovalResponse(approved = false, reason = "manual rejection")
        coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue
        coEvery { hookExecutor.executeAfterToolCall(capture(capturedContext), capture(capturedResult)) } returns Unit

        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = hookExecutor,
            toolApprovalPolicy = approvalPolicy,
            pendingApprovalStore = pendingApprovalStore,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("q" to "arc") }
        )

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(tool)),
            hookContext = context,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size, "Should return one tool response")
        assertTrue(
            responses[0].responseData().contains("Tool call rejected by human", ignoreCase = true),
            "Response should indicate HITL rejection"
        )
        coVerify(exactly = 0) { tool.call(any()) }
        coVerify(exactly = 1) { hookExecutor.executeAfterToolCall(any(), any()) }
        assertEquals(false, capturedResult.captured.success, "Rejected approval should be reported as failure")
        assertEquals(
            "Tool call rejected by human: manual rejection",
            capturedResult.captured.errorMessage,
            "After hook should receive rejection reason as errorMessage"
        )
        assertEquals(0, capturedContext.captured.callIndex, "First tool call should have callIndex=0")
        assertTrue(
            context.metadata.containsKey("hitlWaitMs_danger_tool_0"),
            "HITL wait metadata should include callIndex suffix"
        )
        assertTrue(
            context.metadata.containsKey("hitlApproved_danger_tool_0"),
            "HITL approval metadata should include callIndex suffix"
        )
        assertTrue(
            context.metadata.containsKey("hitlRejectionReason_danger_tool_0"),
            "HITL rejection metadata should include callIndex suffix"
        )
    }

    @Test
    fun `should execute adapter and append toolsUsed`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("q" to "arc") }
        )
        val toolCall = toolCall(id = "id-1", name = "search", arguments = """{"q":"arc"}""")
        val toolsUsed = mutableListOf<String>()
        val callback = object : ToolCallback {
            override val name: String = "search"
            override val description: String = "Search tool"
            override suspend fun call(arguments: Map<String, Any?>): Any = "ok-${arguments["q"]}"
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = toolsUsed,
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertEquals("ok-arc", responses[0].responseData())
        assertEquals(listOf("search"), toolsUsed)
    }

    @Test
    fun `should stop when max tool calls reached`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() }
        )
        val toolCall = toolCall(id = "id-1", name = "search")

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = emptyList(),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(1),
            maxToolCalls = 1,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertTrue(responses[0].responseData().contains("Maximum tool call limit"))
    }

    private fun toolCall(id: String, name: String, arguments: String = "{}"): AssistantMessage.ToolCall {
        val toolCall = mockk<AssistantMessage.ToolCall>()
        every { toolCall.id() } returns id
        every { toolCall.name() } returns name
        every { toolCall.arguments() } returns arguments
        return toolCall
    }
}
