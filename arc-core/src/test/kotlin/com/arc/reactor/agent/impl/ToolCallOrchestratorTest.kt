package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.tool.ToolCallback
import io.mockk.every
import io.mockk.mockk
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
