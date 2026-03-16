package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.ToolResultCacheProperties
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.approval.PendingApprovalStore
import com.arc.reactor.approval.ToolApprovalPolicy
import com.arc.reactor.approval.ToolApprovalResponse
import com.arc.reactor.guard.tool.ToolOutputSanitizer
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.response.ToolResponseSignal
import com.arc.reactor.tool.LocalTool
import com.arc.reactor.tool.ToolCallback
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata
import org.springframework.ai.tool.annotation.Tool
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * ToolCallOrchestrator에 대한 테스트.
 *
 * 도구 호출 오케스트레이션 로직을 검증합니다.
 */
class ToolCallOrchestratorTest {

    private val objectMapper = jacksonObjectMapper()
    private val hookContext = HookContext(
        runId = "run-1",
        userId = "user-1",
        userPrompt = "prompt"
    )

    @Test
    fun `not in allowlist일 때 block tool call해야 한다`() = runBlocking {
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
        assertTrue(responses[0].responseData().contains("not allowed")) {
            "Blocked tool response should contain 'not allowed'"
        }
    }

    @Test
    fun `approval required but store is missing일 때 fail closed해야 한다`() = runBlocking {
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
        assertTrue(
            responses[0].responseData().contains("approval store unavailable", ignoreCase = true)
        ) { "Response should indicate approval store is unavailable" }
        coVerify(exactly = 0) { tool.call(any()) }
    }

    @Test
    fun `approval check throws일 때 fail closed해야 한다`() = runBlocking {
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
        assertTrue(responses[0].responseData().contains("Tool call blocked")) {
            "Response should indicate tool call was blocked"
        }
        assertTrue(responses[0].responseData().contains("Approval check failed")) {
            "Response should indicate approval check failed"
        }
        coVerify(exactly = 0) { tool.call(any()) }
    }

    @Test
    fun `approval rejected by human일 때 execute after hook해야 한다`() = runBlocking {
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
    fun `execute adapter and append toolsUsed해야 한다`() = runBlocking {
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
    fun `execute direct tool call and append toolsUsed해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("query" to "PAY-123") }
        )
        val toolsUsed = mutableListOf<String>()
        val callback = object : ToolCallback {
            override val name: String = "work_owner_lookup"
            override val description: String = "Resolve owner"
            override suspend fun call(arguments: Map<String, Any?>): Any = "owner=${arguments["query"]}"
        }

        val result = orchestrator.executeDirectToolCall(
            toolName = "work_owner_lookup",
            toolParams = mapOf("query" to "PAY-123"),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = toolsUsed
        )

        assertTrue(result.success, "Direct tool call should succeed for registered tool")
        assertEquals("owner=PAY-123", result.output)
        assertEquals(listOf("work_owner_lookup"), toolsUsed)
    }

    @Test
    fun `direct requester-aware work tool에 대해 inject requesterEmail해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() },
            requesterAwareToolNames = setOf("work_personal_focus_plan")
        )
        val toolsUsed = mutableListOf<String>()
        val context = HookContext(
            runId = "run-direct",
            userId = "user-direct",
            userPrompt = "오늘 개인 focus plan을 만들어줘",
            metadata = java.util.concurrent.ConcurrentHashMap(
                mapOf("requesterEmail" to "dig04059@gmail.com")
            )
        )
        val callback = object : ToolCallback {
            override val name: String = "work_personal_focus_plan"
            override val description: String = "Personal focus plan"
            override suspend fun call(arguments: Map<String, Any?>): Any = arguments["requesterEmail"] ?: "none"
        }

        val result = orchestrator.executeDirectToolCall(
            toolName = "work_personal_focus_plan",
            toolParams = emptyMap(),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = context,
            toolsUsed = toolsUsed
        )

        assertTrue(result.success) { "Direct requester-aware tool should succeed" }
        assertEquals("dig04059@gmail.com", result.output)
        assertEquals(listOf("work_personal_focus_plan"), toolsUsed)
    }

    @Test
    fun `capture verified sources from tool output해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() }
        )
        val toolCall = toolCall(id = "id-1", name = "confluence_answer_question")
        val context = HookContext(runId = "run-2", userId = "user-2", userPrompt = "policy")
        val callback = object : ToolCallback {
            override val name: String = "confluence_answer_question"
            override val description: String = "Knowledge tool"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                return """
                    {
                      "grounded": true,
                      "answerMode": "knowledge",
                      "retrievedAt": "2026-03-07T00:00:00Z",
                      "freshness": {"mode": "live_confluence", "sourceType": "confluence"},
                      "sources": [{"title":"Policy","url":"https://example.atlassian.net/wiki/spaces/DEV/pages/1"}]
                    }
                """.trimIndent()
            }
        }

        orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = context,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, context.verifiedSources.size)
        assertEquals("Policy", context.verifiedSources.first().title)
        assertEquals("knowledge", context.metadata["answerMode"])
        assertEquals(true, context.metadata["grounded"])
        assertEquals("2026-03-07T00:00:00Z", context.metadata["retrievedAt"])
    }

    @Test
    fun `sanitizing tool output 전에 capture verified sources해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() },
            toolOutputSanitizer = ToolOutputSanitizer()
        )
        val toolCall = toolCall(id = "id-1", name = "jira_list_projects")
        val context = HookContext(runId = "run-3", userId = "user-3", userPrompt = "projects")
        val callback = object : ToolCallback {
            override val name: String = "jira_list_projects"
            override val description: String = "Project tool"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                return """
                    {
                      "ok": true,
                      "grounded": true,
                      "answerMode": "operational",
                      "retrievedAt": "2026-03-07T00:00:00Z",
                      "freshness": {"mode": "live_atlassian", "sourceType": "jira"},
                      "sources": [{"title":"DEV - Development","url":"https://example.atlassian.net/issues/?jql=project%20%3D%20%22DEV%22"}]
                    }
                """.trimIndent()
            }
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = context,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size, "Should return one tool response")
        assertTrue(
            responses.first().responseData().contains("--- BEGIN TOOL DATA (jira_list_projects) ---"),
            "Sanitized tool output should still be wrapped for the model"
        )
        assertEquals(1, context.verifiedSources.size, "Verified sources should be extracted before sanitizing")
        assertEquals(
            "https://example.atlassian.net/issues/?jql=project%20%3D%20%22DEV%22",
            context.verifiedSources.first().url,
            "Extracted verified source URL should be preserved"
        )
        assertEquals("operational", context.metadata["answerMode"], "Operational answer mode should be preserved")
        assertEquals(true, context.metadata["grounded"], "Grounding metadata should still be captured")
    }

    @Test
    fun `merge parallel tool captures once in tool call order해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() }
        )
        val context = HookContext(runId = "run-4", userId = "user-4", userPrompt = "parallel")
        val toolsUsed = mutableListOf<String>()
        val firstToolCall = toolCall(id = "id-1", name = "jira_search")
        val secondToolCall = toolCall(id = "id-2", name = "confluence_answer_question")
        val firstCallback = object : ToolCallback {
            override val name: String = "jira_search"
            override val description: String = "Search jira"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                return """
                    {
                      "grounded": true,
                      "answerMode": "spec_summary",
                      "retrievedAt": "2026-03-07T00:00:00Z",
                      "sources": [
                        {"title":"Shared","url":"https://example.com/shared"},
                        {"title":"Jira","url":"https://example.com/jira"}
                      ]
                    }
                """.trimIndent()
            }
        }
        val secondCallback = object : ToolCallback {
            override val name: String = "confluence_answer_question"
            override val description: String = "Search confluence"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                return """
                    {
                      "grounded": true,
                      "answerMode": "spec_detail",
                      "retrievedAt": "2026-03-08T00:00:00Z",
                      "sources": [
                        {"title":"Shared duplicate","url":"https://example.com/shared"},
                        {"title":"Confluence","url":"https://example.com/confluence"}
                      ]
                    }
                """.trimIndent()
            }
        }

        orchestrator.executeInParallel(
            toolCalls = listOf(firstToolCall, secondToolCall),
            tools = listOf(ArcToolCallbackAdapter(firstCallback), ArcToolCallbackAdapter(secondCallback)),
            hookContext = context,
            toolsUsed = toolsUsed,
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(listOf("jira_search", "confluence_answer_question"), toolsUsed, "toolsUsed should follow tool call order")
        assertEquals(
            listOf("https://example.com/shared", "https://example.com/jira", "https://example.com/confluence"),
            context.verifiedSources.map { it.url },
            "Verified sources should be merged once in tool call order"
        )
        @Suppress("UNCHECKED_CAST")
        val toolSignals = context.metadata[ToolCallOrchestrator.TOOL_SIGNALS_METADATA_KEY] as? List<ToolResponseSignal>
        assertEquals(2, toolSignals?.size, "Both successful tool signals should be retained")
        assertEquals(
            listOf("jira_search", "confluence_answer_question"),
            toolSignals?.map { it.toolName },
            "Tool signals should preserve tool call order"
        )
        assertEquals("spec_detail", context.metadata["answerMode"], "Last merged signal should win metadata projection")
        assertEquals("2026-03-08T00:00:00Z", context.metadata["retrievedAt"], "Last merged signal should win retrievedAt")
    }

    @Test
    fun `assignee is missing일 때 inject requesterEmail for personal jira tool해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("project" to "DEV") },
            requesterAwareToolNames = setOf("jira_my_open_issues")
        )
        val context = HookContext(
            runId = "run-1",
            userId = "user-1",
            userPrompt = "prompt",
            metadata = java.util.concurrent.ConcurrentHashMap(mapOf("requesterEmail" to "alice@example.com"))
        )
        val toolCall = toolCall(id = "id-1", name = "jira_my_open_issues", arguments = """{"project":"DEV"}""")
        val callback = object : ToolCallback {
            override val name: String = "jira_my_open_issues"
            override val description: String = "Personal Jira tool"
            override suspend fun call(arguments: Map<String, Any?>): Any = arguments["requesterEmail"] ?: "none"
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = context,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertEquals("alice@example.com", responses[0].responseData())
    }

    @Test
    fun `assigneeAccountId is already provided일 때 not inject requesterEmail해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("assigneeAccountId" to "acct-1") },
            requesterAwareToolNames = setOf("jira_daily_briefing")
        )
        val context = HookContext(
            runId = "run-1",
            userId = "user-1",
            userPrompt = "prompt",
            metadata = java.util.concurrent.ConcurrentHashMap(mapOf("requesterEmail" to "alice@example.com"))
        )
        val toolCall = toolCall(
            id = "id-1",
            name = "jira_daily_briefing",
            arguments = """{"assigneeAccountId":"acct-1"}"""
        )
        val callback = object : ToolCallback {
            override val name: String = "jira_daily_briefing"
            override val description: String = "Personal Jira tool"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                val hasRequester = arguments.containsKey("requesterEmail")
                val assignee = arguments["assigneeAccountId"]?.toString().orEmpty()
                return "$assignee|$hasRequester"
            }
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = context,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertEquals("acct-1|false", responses[0].responseData())
    }

    @Test
    fun `assignee is missing일 때 inject requesterEmail for requester-aware work tool해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("jiraProject" to "DEV") },
            requesterAwareToolNames = setOf("work_personal_focus_plan")
        )
        val context = HookContext(
            runId = "run-1",
            userId = "user-1",
            userPrompt = "prompt",
            metadata = java.util.concurrent.ConcurrentHashMap(mapOf("requesterEmail" to "dig04059@gmail.com"))
        )
        val toolCall = toolCall(id = "id-1", name = "work_personal_focus_plan", arguments = """{"jiraProject":"DEV"}""")
        val callback = object : ToolCallback {
            override val name: String = "work_personal_focus_plan"
            override val description: String = "Personal work tool"
            override suspend fun call(arguments: Map<String, Any?>): Any = arguments["requesterEmail"] ?: "none"
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = context,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertEquals("dig04059@gmail.com", responses[0].responseData())
    }

    @Test
    fun `assignee is missing일 때 inject assigneeAccountId for requester-aware work tool해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("jiraProject" to "DEV") },
            requesterAwareToolNames = setOf("work_personal_focus_plan")
        )
        val context = HookContext(
            runId = "run-1",
            userId = "user-1",
            userPrompt = "prompt",
            metadata = java.util.concurrent.ConcurrentHashMap(mapOf("requesterAccountId" to "acct-998877"))
        )
        val toolCall = toolCall(id = "id-1", name = "work_personal_focus_plan", arguments = """{"jiraProject":"DEV"}""")
        val callback = object : ToolCallback {
            override val name: String = "work_personal_focus_plan"
            override val description: String = "Personal work tool"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                val hasRequester = arguments.containsKey("requesterEmail")
                val assignee = arguments["assigneeAccountId"]?.toString().orEmpty()
                return "$assignee|$hasRequester"
            }
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = context,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size, "One tool call result should be returned")
        assertEquals("acct-998877|false", responses[0].responseData(), "assigneeAccountId should be injected from requesterAccountId")
    }

    @Test
    fun `assignee injection에 대해 prefer requesterAccountId over requesterEmail해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("jiraProject" to "DEV") },
            requesterAwareToolNames = setOf("work_personal_focus_plan")
        )
        val context = HookContext(
            runId = "run-1",
            userId = "user-1",
            userPrompt = "prompt",
            metadata = java.util.concurrent.ConcurrentHashMap(
                mapOf(
                    "requesterEmail" to "alice@example.com",
                    "requesterAccountId" to "acct-998877"
                )
            )
        )
        val toolCall = toolCall(id = "id-1", name = "work_personal_focus_plan", arguments = """{"jiraProject":"DEV"}""")
        val callback = object : ToolCallback {
            override val name: String = "work_personal_focus_plan"
            override val description: String = "Personal work tool"
            override suspend fun call(arguments: Map<String, Any?>): Any = arguments["assigneeAccountId"] ?: ""
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = context,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size, "One tool call result should be returned")
        assertEquals("acct-998877", responses[0].responseData(), "requesterAccountId should be used in preference to requesterEmail")
    }

    @Test
    fun `reviewer is missing일 때 inject requesterEmail for requester-aware bitbucket tool해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("repo" to "dev") },
            requesterAwareToolNames = setOf("bitbucket_review_queue")
        )
        val context = HookContext(
            runId = "run-1",
            userId = "user-1",
            userPrompt = "prompt",
            metadata = java.util.concurrent.ConcurrentHashMap(mapOf("requesterEmail" to "dig04059@gmail.com"))
        )
        val toolCall = toolCall(id = "id-1", name = "bitbucket_review_queue", arguments = """{"repo":"dev"}""")
        val callback = object : ToolCallback {
            override val name: String = "bitbucket_review_queue"
            override val description: String = "Personal review queue"
            override suspend fun call(arguments: Map<String, Any?>): Any = arguments["requesterEmail"] ?: "none"
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = context,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertEquals("dig04059@gmail.com", responses[0].responseData())
    }

    @Test
    fun `requester-aware authored bitbucket tool에 대해 inject requesterEmail해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() },
            requesterAwareToolNames = setOf("bitbucket_my_authored_prs")
        )
        val context = HookContext(
            runId = "run-1",
            userId = "user-1",
            userPrompt = "prompt",
            metadata = java.util.concurrent.ConcurrentHashMap(mapOf("requesterEmail" to "dig04059@gmail.com"))
        )
        val toolCall = toolCall(id = "id-1", name = "bitbucket_my_authored_prs", arguments = "{}")
        val callback = object : ToolCallback {
            override val name: String = "bitbucket_my_authored_prs"
            override val description: String = "My authored pull requests"
            override suspend fun call(arguments: Map<String, Any?>): Any = arguments["requesterEmail"] ?: "none"
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = context,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertEquals("dig04059@gmail.com", responses[0].responseData())
    }

    @Test
    fun `execute LocalTool annotated method and append toolsUsed해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("text" to "arc") }
        )
        val toolCall = toolCall(id = "id-1", name = "echo_text", arguments = """{"text":"arc"}""")
        val toolsUsed = mutableListOf<String>()
        val localTool = EchoLocalTool()

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(localTool),
            hookContext = hookContext,
            toolsUsed = toolsUsed,
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertEquals("echo:arc", responses[0].responseData())
        assertEquals(listOf("echo_text"), toolsUsed)
    }

    @Test
    fun `identical local tool set에 대해 reuse cached spring callback resolution해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("text" to "arc") }
        )
        val localTool = EchoLocalTool()
        val firstCall = toolCall(id = "id-1", name = "echo_text", arguments = """{"text":"arc"}""")
        val secondCall = toolCall(id = "id-2", name = "echo_text", arguments = """{"text":"arc"}""")

        orchestrator.executeInParallel(
            toolCalls = listOf(firstCall),
            tools = listOf(localTool),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )
        orchestrator.executeInParallel(
            toolCalls = listOf(secondCall),
            tools = listOf(localTool),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, orchestrator.springToolCallbackCacheEntryCount(), "Local tool callback resolution should be cached per tool set")
    }

    @Test
    fun `execute explicit spring callback and normalize quoted output해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("text" to "arc") }
        )
        val toolCall = toolCall(id = "id-1", name = "spring_echo", arguments = """{"text":"arc"}""")
        val toolsUsed = mutableListOf<String>()
        val springCallback = FakeSpringToolCallback("spring_echo") { input ->
            if (input.contains("\"text\":\"arc\"")) "\"spring:arc\"" else "\"spring:unknown\""
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(springCallback),
            hookContext = hookContext,
            toolsUsed = toolsUsed,
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertEquals("spring:arc", responses[0].responseData())
        assertEquals(listOf("spring_echo"), toolsUsed)
    }

    @Test
    fun `prefer explicit spring callback over reflected LocalTool callback on name collision해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("text" to "arc") }
        )
        val toolCall = toolCall(id = "id-1", name = "spring_echo", arguments = """{"text":"arc"}""")
        val toolsUsed = mutableListOf<String>()
        val localTool = SpringEchoLocalTool()
        val explicitCallback = FakeSpringToolCallback("spring_echo") { "\"explicit:arc\"" }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(localTool, explicitCallback),
            hookContext = hookContext,
            toolsUsed = toolsUsed,
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertEquals("explicit:arc", responses[0].responseData())
        assertEquals(listOf("spring_echo"), toolsUsed)
    }

    @Test
    fun `blocking call exceeds timeout일 때 timeout explicit spring callback해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 40,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() }
        )
        val toolCall = toolCall(id = "id-1", name = "blocking_spring")
        val toolsUsed = mutableListOf<String>()
        val springCallback = FakeSpringToolCallback("blocking_spring") {
            Thread.sleep(250)
            "late"
        }

        lateinit var responses: List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse>
        val elapsedMs = measureTimeMillis {
            responses = orchestrator.executeInParallel(
                toolCalls = listOf(toolCall),
                tools = listOf(springCallback),
                hookContext = hookContext,
                toolsUsed = toolsUsed,
                totalToolCallsCounter = AtomicInteger(0),
                maxToolCalls = 10,
                allowedTools = null
            )
        }

        assertEquals(1, responses.size)
        assertTrue(responses[0].responseData().contains("timed out after 40ms")) {
            "Timeout response should mention 40ms timeout"
        }
        assertEquals(listOf("blocking_spring"), toolsUsed)
        assertTrue(elapsedMs < 220) { "Blocking callback should timeout early, elapsed=${elapsedMs}ms" }
    }

    @Test
    fun `max tool calls reached일 때 stop해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() }
        )
        val toolCall = toolCall(id = "id-1", name = "search")
        val callback = object : ToolCallback {
            override val name: String = "search"
            override val description: String = "Search tool"
            override suspend fun call(arguments: Map<String, Any?>): Any = "ok"
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(1),
            maxToolCalls = 1,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertTrue(responses[0].responseData().contains("Maximum tool call limit")) {
            "Response should indicate max tool call limit was reached"
        }
    }

    @Test
    fun `allowlist-blocked tool calls에 대해 not consume budget해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() }
        )
        val callback = object : ToolCallback {
            override val name: String = "search"
            override val description: String = "Search tool"
            override suspend fun call(arguments: Map<String, Any?>): Any = "ok"
        }
        val counter = AtomicInteger(0)

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(
                toolCall(id = "id-1", name = "blocked"),
                toolCall(id = "id-2", name = "search")
            ),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = counter,
            maxToolCalls = 1,
            allowedTools = setOf("search")
        )

        assertEquals(2, responses.size)
        assertTrue(responses[0].responseData().contains("not allowed")) {
            "First response should explain allowlist rejection"
        }
        assertEquals("ok", responses[1].responseData())
        assertEquals(1, counter.get()) { "Only the executed tool should consume budget" }
    }

    @Test
    fun `hallucinated missing tools에 대해 not consume budget해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() }
        )
        val callback = object : ToolCallback {
            override val name: String = "search"
            override val description: String = "Search tool"
            override suspend fun call(arguments: Map<String, Any?>): Any = "ok"
        }
        val counter = AtomicInteger(0)

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(
                toolCall(id = "id-1", name = "missing"),
                toolCall(id = "id-2", name = "search")
            ),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = counter,
            maxToolCalls = 1,
            allowedTools = null
        )

        assertEquals(2, responses.size)
        assertTrue(responses[0].responseData().contains("not found")) {
            "Missing tool response should explain that the tool does not exist"
        }
        assertEquals("ok", responses[1].responseData())
        assertEquals(1, counter.get()) { "Only the real tool execution should consume budget" }
    }

    @Test
    fun `strict json normalization is enabled일 때 wrap plain text output해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() }
        )
        val toolCall = toolCall(id = "id-1", name = "search")
        val callback = object : ToolCallback {
            override val name: String = "search"
            override val description: String = "Search tool"
            override suspend fun call(arguments: Map<String, Any?>): Any = "Error: backend down"
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null,
            normalizeToolResponseToJson = true
        )

        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(responses[0].responseData(), Map::class.java) as Map<String, Any?>
        assertEquals("Error: backend down", payload["result"])
    }

    @Test
    fun `strict json normalization is enabled일 때 preserve valid json output해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() }
        )
        val toolCall = toolCall(id = "id-1", name = "search")
        val callback = object : ToolCallback {
            override val name: String = "search"
            override val description: String = "Search tool"
            override suspend fun call(arguments: Map<String, Any?>): Any = """{"status":"ok"}"""
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null,
            normalizeToolResponseToJson = true
        )

        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(responses[0].responseData(), Map::class.java) as Map<String, Any?>
        assertEquals("ok", payload["status"])
    }

    @Test
    fun `sanitizer is enabled일 때 sanitize failed tool error output해야 한다`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() },
            toolOutputSanitizer = ToolOutputSanitizer()
        )
        val toolCall = toolCall(id = "id-1", name = "jira_search")
        val callback = object : ToolCallback {
            override val name: String = "jira_search"
            override val description: String = "Search"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                throw RuntimeException("Connection failed at /internal/path/secret.conf")
            }
        }

        val responses = orchestrator.executeInParallel(
            toolCalls = listOf(toolCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(1, responses.size)
        assertTrue(
            responses[0].responseData().contains("--- BEGIN TOOL DATA (jira_search) ---"),
            "Failed tool output should be wrapped with data markers when sanitizer is enabled"
        )
    }

    @Test
    fun `same tool and args are called twice with cache enabled일 때 return cached result해야 한다`() = runBlocking {
        var callCount = 0
        val callback = object : ToolCallback {
            override val name: String = "search"
            override val description: String = "Search tool"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                callCount++
                return "result-$callCount"
            }
        }
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("q" to "arc") },
            toolResultCacheProperties = ToolResultCacheProperties(
                enabled = true,
                ttlSeconds = 60,
                maxSize = 200
            )
        )

        val firstCall = toolCall(id = "id-1", name = "search", arguments = """{"q":"arc"}""")
        val secondCall = toolCall(id = "id-2", name = "search", arguments = """{"q":"arc"}""")

        val firstResponses = orchestrator.executeInParallel(
            toolCalls = listOf(firstCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )
        val secondResponses = orchestrator.executeInParallel(
            toolCalls = listOf(secondCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals("result-1", firstResponses[0].responseData(), "First call should return fresh result")
        assertEquals("result-1", secondResponses[0].responseData(), "Second call should return cached result")
        assertEquals(1, callCount, "Tool should be invoked only once due to caching")
    }

    @Test
    fun `cache is disabled일 때 not cache해야 한다`() = runBlocking {
        var callCount = 0
        val callback = object : ToolCallback {
            override val name: String = "search"
            override val description: String = "Search tool"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                callCount++
                return "result-$callCount"
            }
        }
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("q" to "arc") },
            toolResultCacheProperties = ToolResultCacheProperties(enabled = false)
        )

        val firstCall = toolCall(id = "id-1", name = "search", arguments = """{"q":"arc"}""")
        val secondCall = toolCall(id = "id-2", name = "search", arguments = """{"q":"arc"}""")

        orchestrator.executeInParallel(
            toolCalls = listOf(firstCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )
        orchestrator.executeInParallel(
            toolCalls = listOf(secondCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals(2, callCount, "Tool should be invoked twice when cache is disabled")
    }

    @Test
    fun `not cache failed tool results해야 한다`() = runBlocking {
        var callCount = 0
        val callback = object : ToolCallback {
            override val name: String = "search"
            override val description: String = "Search tool"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                callCount++
                return if (callCount == 1) "Error: service unavailable" else "success"
            }
        }
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("q" to "arc") },
            toolResultCacheProperties = ToolResultCacheProperties(
                enabled = true,
                ttlSeconds = 60,
                maxSize = 200
            )
        )

        val firstCall = toolCall(id = "id-1", name = "search", arguments = """{"q":"arc"}""")
        val secondCall = toolCall(id = "id-2", name = "search", arguments = """{"q":"arc"}""")

        val firstResponses = orchestrator.executeInParallel(
            toolCalls = listOf(firstCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )
        val secondResponses = orchestrator.executeInParallel(
            toolCalls = listOf(secondCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertTrue(
            firstResponses[0].responseData().contains("Error:"),
            "First call should return error result"
        )
        assertEquals("success", secondResponses[0].responseData(), "Second call should execute again after error")
        assertEquals(2, callCount, "Tool should be called twice because first result was an error")
    }

    @Test
    fun `different arguments에 대해 cache separately해야 한다`() = runBlocking {
        var callCount = 0
        val callback = object : ToolCallback {
            override val name: String = "search"
            override val description: String = "Search tool"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                callCount++
                return "result-$callCount"
            }
        }
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { args ->
                @Suppress("UNCHECKED_CAST")
                if (args != null) objectMapper.readValue(args, Map::class.java) as Map<String, Any?>
                else emptyMap()
            },
            toolResultCacheProperties = ToolResultCacheProperties(
                enabled = true,
                ttlSeconds = 60,
                maxSize = 200
            )
        )

        val call1 = toolCall(id = "id-1", name = "search", arguments = """{"q":"kotlin"}""")
        val call2 = toolCall(id = "id-2", name = "search", arguments = """{"q":"java"}""")

        val resp1 = orchestrator.executeInParallel(
            toolCalls = listOf(call1),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )
        val resp2 = orchestrator.executeInParallel(
            toolCalls = listOf(call2),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals("result-1", resp1[0].responseData(), "First distinct args should return fresh result")
        assertEquals("result-2", resp2[0].responseData(), "Different args should return separate result")
        assertEquals(2, callCount, "Tool should be invoked once per unique argument set")
    }

    @Test
    fun `record cache hit and miss metrics해야 한다`() = runBlocking {
        val metrics = mockk<com.arc.reactor.agent.metrics.AgentMetrics>(relaxed = true)
        val callback = object : ToolCallback {
            override val name: String = "search"
            override val description: String = "Search tool"
            override suspend fun call(arguments: Map<String, Any?>): Any = "ok"
        }
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = metrics,
            parseToolArguments = { mapOf("q" to "arc") },
            toolResultCacheProperties = ToolResultCacheProperties(
                enabled = true,
                ttlSeconds = 60,
                maxSize = 200
            )
        )

        val firstCall = toolCall(id = "id-1", name = "search", arguments = """{"q":"arc"}""")
        val secondCall = toolCall(id = "id-2", name = "search", arguments = """{"q":"arc"}""")

        orchestrator.executeInParallel(
            toolCalls = listOf(firstCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )
        orchestrator.executeInParallel(
            toolCalls = listOf(secondCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        io.mockk.verify(exactly = 1) {
            metrics.recordToolResultCacheMiss("search", any())
        }
        io.mockk.verify(exactly = 1) {
            metrics.recordToolResultCacheHit("search", any())
        }
    }

    @Test
    fun `cache enabled로 return cached result for direct tool call해야 한다`() = runBlocking {
        var callCount = 0
        val callback = object : ToolCallback {
            override val name: String = "search"
            override val description: String = "Search tool"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                callCount++
                return "direct-result-$callCount"
            }
        }
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            toolResultCacheProperties = ToolResultCacheProperties(
                enabled = true,
                ttlSeconds = 60,
                maxSize = 200
            )
        )
        val tools = listOf(ArcToolCallbackAdapter(callback))
        val params = mapOf<String, Any?>("q" to "arc")

        val first = orchestrator.executeDirectToolCall(
            toolName = "search",
            toolParams = params,
            tools = tools,
            hookContext = hookContext,
            toolsUsed = mutableListOf()
        )
        val second = orchestrator.executeDirectToolCall(
            toolName = "search",
            toolParams = params,
            tools = tools,
            hookContext = hookContext,
            toolsUsed = mutableListOf()
        )

        assertEquals("direct-result-1", first.output, "First direct call should return fresh result")
        assertEquals("direct-result-1", second.output, "Second direct call should return cached result")
        assertEquals(1, callCount, "Tool should be invoked only once due to caching")
    }

    @Test
    fun `TTL and re-execute tool 후 expire cached result해야 한다`() = runBlocking {
        var callCount = 0
        val callback = object : ToolCallback {
            override val name: String = "search"
            override val description: String = "Search tool"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                callCount++
                return "result-$callCount"
            }
        }
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("q" to "arc") },
            toolResultCacheProperties = ToolResultCacheProperties(
                enabled = true,
                ttlSeconds = 1,
                maxSize = 200
            )
        )

        val firstCall = toolCall(id = "id-1", name = "search", arguments = """{"q":"arc"}""")
        val firstResponses = orchestrator.executeInParallel(
            toolCalls = listOf(firstCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )
        assertEquals("result-1", firstResponses[0].responseData(), "First call should return fresh result")

        Thread.sleep(1100)

        val secondCall = toolCall(id = "id-2", name = "search", arguments = """{"q":"arc"}""")
        val secondResponses = orchestrator.executeInParallel(
            toolCalls = listOf(secondCall),
            tools = listOf(ArcToolCallbackAdapter(callback)),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            totalToolCallsCounter = AtomicInteger(0),
            maxToolCalls = 10,
            allowedTools = null
        )

        assertEquals("result-2", secondResponses[0].responseData(), "Second call after TTL expiry should return fresh result")
        assertEquals(2, callCount, "Tool should be invoked twice because cache entry expired after TTL")
    }

    private fun toolCall(id: String, name: String, arguments: String = "{}"): AssistantMessage.ToolCall {
        val toolCall = mockk<AssistantMessage.ToolCall>()
        every { toolCall.id() } returns id
        every { toolCall.name() } returns name
        every { toolCall.arguments() } returns arguments
        return toolCall
    }

    private class EchoLocalTool : LocalTool {
        @Tool(description = "Echo text")
        fun echo_text(text: String): String = "echo:$text"
    }

    private class SpringEchoLocalTool : LocalTool {
        @Tool(description = "Echo text with colliding name")
        fun spring_echo(text: String): String = "local:$text"
    }

    private class FakeSpringToolCallback(
        private val name: String,
        private val handler: (String) -> String
    ) : org.springframework.ai.tool.ToolCallback {
        override fun getToolDefinition(): ToolDefinition = ToolDefinition.builder()
            .name(name)
            .description("fake")
            .inputSchema("""{"type":"object","properties":{}}""")
            .build()

        override fun getToolMetadata(): ToolMetadata = ToolMetadata.builder().build()

        override fun call(toolInput: String): String = handler(toolInput)
    }
}
