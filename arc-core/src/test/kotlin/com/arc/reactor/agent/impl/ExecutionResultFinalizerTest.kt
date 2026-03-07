package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.OutputMinViolationMode
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentErrorCode
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.OutputRejectionCategory
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import com.arc.reactor.response.ResponseFilter
import com.arc.reactor.response.ResponseFilterChain
import com.arc.reactor.response.ResponseFilterContext
import com.arc.reactor.response.ToolResponseSignal
import com.arc.reactor.response.VerifiedSource
import com.arc.reactor.response.impl.VerifiedSourcesResponseFilter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class ExecutionResultFinalizerTest {

    @Test
    fun `should apply response filter then save history and call hook on success`() = runBlocking {
        val chain = ResponseFilterChain(listOf(object : ResponseFilter {
            override suspend fun filter(content: String, context: ResponseFilterContext): String = "$content!"
        }))
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = chain,
            boundaries = BoundaryProperties(),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi").apply {
            verifiedSources += VerifiedSource(
                title = "Policy",
                url = "https://example.com/policy",
                toolName = "confluence_answer_question"
            )
            metadata["answerMode"] = "knowledge"
            metadata["freshness"] = mapOf("mode" to "live_confluence", "sourceType" to "confluence")
        }
        val result = finalizer.finalize(
            result = AgentResult.success(content = "hello"),
            command = command,
            hookContext = hookContext,
            toolsUsed = listOf("search"),
            startTime = 1_000L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertTrue(result.success, "Finalizer should return success when output guard and boundaries pass")
        assertEquals("hello!", result.content)
        assertEquals(true, result.metadata["grounded"], "Grounded metadata should be attached")
        assertEquals("knowledge", result.metadata["answerMode"], "Answer mode should be attached")
        assertEquals(1, result.metadata["verifiedSourceCount"], "Verified source count should be attached")
        coVerify(exactly = 1) {
            conversationManager.saveHistory(command, match { it.success && it.content == "hello!" })
        }
        coVerify(exactly = 1) {
            hookExecutor.executeAfterAgentComplete(
                context = hookContext,
                response = match {
                    it.success && it.response == "hello!" && it.errorMessage == null && it.toolsUsed == listOf("search")
                }
            )
        }
        verify(exactly = 1) { metrics.recordExecution(match { it.success && it.content == "hello!" }) }
    }

    @Test
    fun `should return OUTPUT_GUARD_REJECTED when output guard rejects`() = runBlocking {
        val rejectingStage = object : OutputGuardStage {
            override val stageName = "RejectingStage"
            override val order = 1
            override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                return OutputGuardResult.Rejected(
                    reason = "blocked",
                    category = OutputRejectionCategory.POLICY_VIOLATION
                )
            }
        }
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = OutputGuardPipeline(listOf(rejectingStage)),
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi")
        val result = finalizer.finalize(
            result = AgentResult.success(content = "sensitive"),
            command = command,
            hookContext = hookContext,
            toolsUsed = emptyList(),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertFalse(result.success, "Result should fail when output guard rejects the response")
        assertEquals(AgentErrorCode.OUTPUT_GUARD_REJECTED, result.errorCode)
        assertEquals("blocked", result.metadata["blockReason"], "Block reason should be captured in metadata")
        coVerify(exactly = 0) { conversationManager.saveHistory(command, match { true }) }
        coVerify(exactly = 1) {
            hookExecutor.executeAfterAgentComplete(
                hookContext,
                match { !it.success && it.errorCode == "OUTPUT_GUARD_REJECTED" }
            )
        }
        verify(exactly = 1) { metrics.recordExecution(match { !it.success && it.errorCode == AgentErrorCode.OUTPUT_GUARD_REJECTED }) }
    }

    @Test
    fun `should return OUTPUT_TOO_SHORT when boundary mode is FAIL`() = runBlocking {
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(outputMinChars = 10, outputMinViolationMode = OutputMinViolationMode.FAIL),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        val result = finalizer.finalize(
            result = AgentResult.success(content = "short"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = emptyList(),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertFalse(result.success, "Result should fail when output is too short and mode is FAIL")
        assertEquals(AgentErrorCode.OUTPUT_TOO_SHORT, result.errorCode)
        assertEquals("output_too_short", result.metadata["blockReason"], "Boundary failure should expose block reason")
        verify(exactly = 1) { metrics.recordExecution(match { !it.success && it.errorCode == AgentErrorCode.OUTPUT_TOO_SHORT }) }
    }

    @Test
    fun `should retry once for short response when boundary mode is RETRY_ONCE`() = runBlocking {
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(outputMinChars = 10, outputMinViolationMode = OutputMinViolationMode.RETRY_ONCE),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        val result = finalizer.finalize(
            result = AgentResult.success(content = "short"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = emptyList(),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> "long enough response" }
        )

        assertTrue(result.success, "Result should succeed after retry produces a longer response")
        assertEquals("long enough response", result.content)
        verify(exactly = 1) { metrics.recordBoundaryViolation("output_too_short", "retry_once", 10, 5, any()) }
        verify(exactly = 1) { metrics.recordExecution(match { it.success && it.content == "long enough response" }) }
    }

    @Test
    fun `should record unverified response metric when sources are missing`() = runBlocking {
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = ResponseFilterChain(listOf(VerifiedSourcesResponseFilter())),
            boundaries = BoundaryProperties(),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        val command = AgentCommand(
            systemPrompt = "sys",
            userPrompt = "Show the current policy",
            metadata = mapOf("channel" to "web")
        )
        val result = finalizer.finalize(
            result = AgentResult.success(content = "Here is the policy."),
            command = command,
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "Show the current policy"),
            toolsUsed = listOf("jira_list_projects"),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertEquals("unverified_sources", result.metadata["blockReason"], "Should mark missing source block reason")
        verify(exactly = 1) {
            metrics.recordUnverifiedResponse(match {
                it["channel"] == "web" &&
                    it["queryCluster"] == "93bd4b524029" &&
                    it["queryLabel"] == "Prompt cluster 93bd4b524029" &&
                    it["blockReason"] == "unverified_sources"
            })
        }
    }

    @Test
    fun `should replace generic unverified copy with identity resolution guidance`() = runBlocking {
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        val command = AgentCommand(systemPrompt = "sys", userPrompt = "내가 담당한 Jira 오픈 이슈 목록을 보여줘.")
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = command.userPrompt).apply {
            metadata["blockReason"] = "identity_unresolved"
        }

        val result = finalizer.finalize(
            result = AgentResult.success(
                content = "검증 가능한 출처를 찾지 못해 답변을 확정할 수 없습니다. 승인된 Jira 자료를 다시 조회해 주세요."
            ),
            command = command,
            hookContext = hookContext,
            toolsUsed = listOf("jira_my_open_issues"),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertEquals(
            "요청자 계정을 Jira 사용자로 확인할 수 없어 개인화 조회를 확정할 수 없습니다. requesterEmail과 Atlassian 사용자 매핑을 확인해 주세요.",
            result.content
        ) {
            "Identity resolution failures should not surface as generic unverified-source guidance"
        }
        assertEquals("identity_unresolved", result.metadata["blockReason"], "Identity failures should keep their dedicated block reason")
    }

    @Test
    fun `should rethrow cancellation from after hook`() = runBlocking {
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>()
        coEvery { hookExecutor.executeAfterAgentComplete(any(), any()) } throws CancellationException("cancelled")
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = conversationManager,
            hookExecutor = hookExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        try {
            finalizer.finalize(
                result = AgentResult.success(content = "hello"),
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
                toolsUsed = emptyList(),
                startTime = 500L,
                attemptLongerResponse = { _, _, _ -> null }
            )
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // expected
        }
    }

    @Test
    fun `should synthesize visible upstream auth guidance when tool metadata reports authentication failure`() = runBlocking {
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = mockk(relaxed = true),
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = mockk(relaxed = true),
            nowMs = { 1_000L }
        )
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "휴가 규정을 찾아줘.").apply {
            metadata[ToolCallOrchestrator.TOOL_SIGNALS_METADATA_KEY] = mutableListOf(
                ToolResponseSignal(toolName = "confluence_answer_question", grounded = false, blockReason = "upstream_auth_failed")
            )
            metadata["blockReason"] = "upstream_auth_failed"
        }

        val result = finalizer.finalize(
            result = AgentResult.success(
                content = "검증 가능한 출처를 찾지 못해 답변을 확정할 수 없습니다. 승인된 Confluence 자료를 다시 조회해 주세요."
            ),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "휴가 규정을 찾아줘."),
            hookContext = hookContext,
            toolsUsed = listOf("confluence_answer_question"),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertEquals(
            "연결된 업무 도구 인증이 실패해 이 조회를 확정할 수 없습니다. 시스템 계정 토큰 설정을 확인해 주세요.",
            result.content
        ) {
            "Upstream auth failures should not surface as generic unverified-source guidance"
        }
        assertEquals("upstream_auth_failed", result.metadata["blockReason"], "Auth failures should keep their dedicated block reason")
    }

    @Test
    fun `should synthesize visible policy denied response when tool metadata blocks access`() = runBlocking {
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = mockk(relaxed = true),
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = mockk(relaxed = true),
            nowMs = { 1_000L }
        )
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "CAMPAIGN 프로젝트의 blocker 이슈를 정리해줘.").apply {
            metadata[ToolCallOrchestrator.TOOL_SIGNALS_METADATA_KEY] = mutableListOf(
                ToolResponseSignal(toolName = "jira_blocker_digest", grounded = false, blockReason = "policy_denied")
            )
            metadata["blockReason"] = "policy_denied"
        }

        val result = finalizer.finalize(
            result = AgentResult(success = true, content = null),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "CAMPAIGN 프로젝트의 blocker 이슈를 정리해줘."),
            hookContext = hookContext,
            toolsUsed = listOf("jira_blocker_digest"),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertEquals("policy_denied", result.metadata["blockReason"], "Policy block reason should be preserved")
        assertTrue(result.content!!.contains("접근 정책"), "Policy-denied responses should explain the policy block")
    }

    @Test
    fun `should synthesize read only refusal for empty swagger mutation response`() = runBlocking {
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = mockk(relaxed = true),
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = mockk(relaxed = true),
            nowMs = { 1_000L }
        )

        val result = finalizer.finalize(
            result = AgentResult(success = true, content = null),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "로드된 Petstore v2 스펙을 catalog에서 제거해줘."),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "로드된 Petstore v2 스펙을 catalog에서 제거해줘."),
            toolsUsed = emptyList(),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertEquals("read_only_mutation", result.metadata["blockReason"], "Mutation prompts should expose a read-only block reason")
        assertTrue(result.content!!.contains("읽기 전용"), "Mutation refusals should be visible to the end user")
    }
}
