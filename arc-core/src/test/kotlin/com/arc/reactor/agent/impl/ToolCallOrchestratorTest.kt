package com.arc.reactor.agent.impl

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

class ToolCallOrchestratorTest {

    private val objectMapper = jacksonObjectMapper()
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
        assertTrue(responses[0].responseData().contains("not allowed")) {
            "Blocked tool response should contain 'not allowed'"
        }
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
        assertTrue(
            responses[0].responseData().contains("approval store unavailable", ignoreCase = true)
        ) { "Response should indicate approval store is unavailable" }
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
        assertTrue(responses[0].responseData().contains("Tool call blocked")) {
            "Response should indicate tool call was blocked"
        }
        assertTrue(responses[0].responseData().contains("Approval check failed")) {
            "Response should indicate approval check failed"
        }
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
    fun `should execute direct tool call and append toolsUsed`() = runBlocking {
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

        assertTrue(result.success)
        assertEquals("owner=PAY-123", result.output)
        assertEquals(listOf("work_owner_lookup"), toolsUsed)
    }

    @Test
    fun `should inject requesterEmail for direct requester-aware work tool`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() }
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
    fun `should capture verified sources from tool output`() = runBlocking {
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
    fun `should capture verified sources before sanitizing tool output`() = runBlocking {
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
    fun `should inject requesterEmail for personal jira tool when assignee is missing`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("project" to "DEV") }
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
    fun `should not inject requesterEmail when assigneeAccountId is already provided`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("assigneeAccountId" to "acct-1") }
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
    fun `should inject requesterEmail for requester-aware work tool when assignee is missing`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("jiraProject" to "DEV") }
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
    fun `should inject assigneeAccountId for requester-aware work tool when assignee is missing`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("jiraProject" to "DEV") }
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
    fun `should prefer requesterAccountId over requesterEmail for assignee injection`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("jiraProject" to "DEV") }
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
    fun `should inject requesterEmail for requester-aware bitbucket tool when reviewer is missing`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { mapOf("repo" to "dev") }
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
    fun `should inject requesterEmail for requester-aware authored bitbucket tool`() = runBlocking {
        val orchestrator = ToolCallOrchestrator(
            toolCallTimeoutMs = 1000,
            hookExecutor = null,
            toolApprovalPolicy = null,
            pendingApprovalStore = null,
            agentMetrics = NoOpAgentMetrics(),
            parseToolArguments = { emptyMap() }
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
    fun `should execute LocalTool annotated method and append toolsUsed`() = runBlocking {
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
    fun `should execute explicit spring callback and normalize quoted output`() = runBlocking {
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
    fun `should prefer explicit spring callback over reflected LocalTool callback on name collision`() = runBlocking {
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
    fun `should timeout explicit spring callback when blocking call exceeds timeout`() = runBlocking {
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
        assertTrue(responses[0].responseData().contains("Maximum tool call limit")) {
            "Response should indicate max tool call limit was reached"
        }
    }

    @Test
    fun `should wrap plain text output when strict json normalization is enabled`() = runBlocking {
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
    fun `should preserve valid json output when strict json normalization is enabled`() = runBlocking {
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
