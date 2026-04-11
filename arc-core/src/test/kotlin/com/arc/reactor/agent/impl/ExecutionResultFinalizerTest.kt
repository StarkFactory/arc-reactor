package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.BoundaryProperties
import com.arc.reactor.agent.config.CitationProperties
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * ExecutionResultFinalizer에 대한 테스트.
 *
 * 실행 결과 최종 처리 로직을 검증합니다.
 */
class ExecutionResultFinalizerTest {

    /**
     * R344 regression: `spec_` prefix(Swagger/OpenAPI 도구 — `spec_search`, `spec_detail` 등)가
     * `toolFamily` 분류에서 `"other"` bucket으로 떨어지던 metrics drift를 수정했는지 검증.
     * `VerifiedSourcesResponseFilter.WORKSPACE_TOOL_PREFIXES`는 이미 `spec_`을 workspace 도구로
     * 인식하고 있으므로 두 곳의 분류 의도가 일치해야 한다.
     */
    /**
     * R344 regression: `spec_` prefix 도구가 `recordResponseObservation`에 전달되는 event
     * metadata의 `toolFamily`에서 `"spec"`으로 분류되어야 한다. 이전에는 해당 분기가 없어
     * `"other"` bucket으로 떨어지며 Grafana "tool family usage" 패널에서 Swagger/OpenAPI
     * 도구 사용률이 invisible한 metrics drift.
     *
     * `toolFamily`는 `metadata["toolFamily"]`에 실리지만 `result.metadata`가 아니라
     * `AgentMetrics.recordResponseObservation`에 전달되는 event metadata로 기록된다.
     * mockk slot으로 capture하여 검증.
     */
    @Test
    fun `R344 spec_ prefix 도구는 recordResponseObservation event의 toolFamily로 spec을 기록해야 한다`() = runTest {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val capturedEvents = mutableListOf<Map<String, Any>>()
        every { metrics.recordResponseObservation(capture(capturedEvents)) } returns Unit

        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = mockk(relaxed = true),
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        finalizer.finalize(
            result = AgentResult.success(
                content = "OpenAPI spec: Pet Store",
                toolsUsed = listOf("spec_detail")
            ),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = listOf("spec_detail"),
            startTime = 1_000L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertTrue(capturedEvents.isNotEmpty()) {
            "recordResponseObservation이 최소 한 번 호출되어야 한다"
        }
        assertEquals(
            "spec",
            capturedEvents.first()["toolFamily"],
            "R344: spec_ prefix 도구는 toolFamily=\"spec\"으로 분류되어야 한다 (이전에는 \"other\")"
        )
    }

    @Test
    fun `R344 다양한 prefix의 toolFamily 분류 회귀`() = runTest {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val capturedEvents = mutableListOf<Map<String, Any>>()
        every { metrics.recordResponseObservation(capture(capturedEvents)) } returns Unit

        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = mockk(relaxed = true),
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        suspend fun runWith(toolName: String): String? {
            capturedEvents.clear()
            finalizer.finalize(
                result = AgentResult.success(content = "ok", toolsUsed = listOf(toolName)),
                command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
                hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
                toolsUsed = listOf(toolName),
                startTime = 1_000L,
                attemptLongerResponse = { _, _, _ -> null }
            )
            return capturedEvents.firstOrNull()?.get("toolFamily") as? String
        }

        assertEquals("confluence", runWith("confluence_answer_question")) { "confluence_ prefix" }
        assertEquals("jira", runWith("jira_my_open_issues")) { "jira_ prefix" }
        assertEquals("bitbucket", runWith("bitbucket_list_prs")) { "bitbucket_ prefix" }
        assertEquals("work", runWith("work_morning_briefing")) { "work_ prefix" }
        assertEquals("spec", runWith("spec_search")) { "R344: spec_ prefix" }
        assertEquals("mcp", runWith("mcp_generic_call")) { "mcp_ prefix" }
        assertEquals("other", runWith("unknown_tool_xyz")) { "unrecognized prefix → other" }
    }

    /**
     * R342 regression: tool이 명시적으로 `grounded=false` 신호를 보냈더라도, 실제
     * `HookContext.verifiedSources`에 근거가 누적되어 있다면 최종 메타데이터의
     * `grounded`는 **true**로 기록되어야 한다. 이전 구현은 `latestSignal?.grounded ?:
     * sources.isNotEmpty()` 체인이라 non-null `false` 신호가 sources 존재 여부를 덮어써
     * false-negative가 발생했다. 직원은 실제 Confluence/Jira 근거가 있는 답변을 받았는데
     * `grounded: false`로 평가절하되던 employee_value axis 버그.
     */
    @Test
    fun `R342 tool grounded=false신호와 verifiedSources 존재 충돌 시 grounded=true로 기록해야 한다`() = runTest {
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

        val command = AgentCommand(systemPrompt = "sys", userPrompt = "hi")
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi").apply {
            // 실제 출처가 존재 — 직원에게는 근거 있는 답변
            addVerifiedSource(
                VerifiedSource(
                    title = "Policy Doc",
                    url = "https://example.com/policy",
                    toolName = "confluence_answer_question"
                )
            )
            // 하지만 tool signal이 "grounded=false"를 명시적으로 반환 (예: partial match)
            metadata[ToolCallOrchestrator.TOOL_SIGNALS_METADATA_KEY] = mutableListOf(
                ToolResponseSignal(
                    toolName = "confluence_answer_question",
                    grounded = false
                )
            )
        }

        val result = finalizer.finalize(
            result = AgentResult.success(content = "policy details"),
            command = command,
            hookContext = hookContext,
            toolsUsed = listOf("confluence_answer_question"),
            startTime = 1_000L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertTrue(result.success) { "Finalize should succeed" }
        assertEquals(
            true,
            result.metadata["grounded"],
            "R342: verifiedSources가 존재하면 tool signal grounded=false를 덮어쓰지 못하고 " +
                "grounded=true로 기록되어야 한다"
        )
        assertEquals(
            1,
            result.metadata["verifiedSourceCount"],
            "verifiedSourceCount는 실제 sources 수를 정확히 반영해야 한다"
        )
    }

    /**
     * R342 regression: tool signal `grounded=false`이고 verifiedSources도 비어있으면
     * grounded는 여전히 `false`로 기록되어야 한다. "근거 없음 + tool이 이를 명시적으로 확인"
     * 의미가 유지되는지 검증.
     */
    @Test
    fun `R342 tool grounded=false이고 sources 없으면 grounded=false로 기록해야 한다`() = runTest {
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

        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi").apply {
            metadata[ToolCallOrchestrator.TOOL_SIGNALS_METADATA_KEY] = mutableListOf(
                ToolResponseSignal(toolName = "confluence_answer_question", grounded = false)
            )
        }
        val result = finalizer.finalize(
            result = AgentResult.success(content = "no policy found"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = hookContext,
            toolsUsed = listOf("confluence_answer_question"),
            startTime = 1_000L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertEquals(
            false,
            result.metadata["grounded"],
            "R342: tool이 grounded=false를 명시 + sources 없음 → grounded=false 유지"
        )
    }

    @Test
    fun `apply response filter then save history and call hook on success해야 한다`() = runTest {
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
            addVerifiedSource(VerifiedSource(
                title = "Policy",
                url = "https://example.com/policy",
                toolName = "confluence_answer_question"
            ))
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
    fun `output guard rejects일 때 return OUTPUT_GUARD_REJECTED해야 한다`() = runTest {
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
        coVerify(exactly = 1) { conversationManager.saveHistory(command, match { !it.success }) }
        coVerify(exactly = 1) {
            hookExecutor.executeAfterAgentComplete(
                hookContext,
                match { !it.success && it.errorCode == "OUTPUT_GUARD_REJECTED" }
            )
        }
        verify(exactly = 1) { metrics.recordExecution(match { !it.success && it.errorCode == AgentErrorCode.OUTPUT_GUARD_REJECTED }) }
    }

    @Test
    fun `boundary mode is FAIL일 때 return OUTPUT_TOO_SHORT해야 한다`() = runTest {
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
    fun `boundary mode is RETRY_ONCE일 때 retry once for short response해야 한다`() = runTest {
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
    fun `expose stage timings from hook metadata해야 한다`() = runTest {
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
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi").apply {
            recordStageTiming(this, "guard", 4)
            recordStageTiming(this, "agent_loop", 17)
        }

        val result = finalizer.finalize(
            result = AgentResult.success(content = "ok"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = hookContext,
            toolsUsed = emptyList(),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        @Suppress("UNCHECKED_CAST")
        val stageTimings = result.metadata["stageTimings"] as? Map<String, Any>
        assertEquals(4L, stageTimings?.get("guard"), "Stage timings should preserve guard latency")
        assertEquals(17L, stageTimings?.get("agent_loop"), "Stage timings should preserve agent loop latency")
    }

    @Test
    fun `tool-backed grounding already exists일 때 skip retry once for short response해야 한다`() = runTest {
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
        var retryInvoked = false
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi").apply {
            addVerifiedSource(VerifiedSource(title = "Doc", url = "https://example.com/doc"))
        }

        val result = finalizer.finalize(
            result = AgentResult.success(content = "short"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = hookContext,
            toolsUsed = listOf("jira_search_issues"),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ ->
                retryInvoked = true
                "long enough response"
            }
        )

        assertTrue(result.success, "Tool-backed short response should fall back without retrying")
        assertEquals("short", result.content, "Original short content should be preserved when retry is skipped")
        assertEquals(false, retryInvoked, "Retry should not run for tool-backed grounded responses")
        verify(exactly = 1) { metrics.recordBoundaryViolation("output_too_short", "retry_once", 10, 5, any()) }
    }

    @Test
    fun `sources are missing but tool called일 때 not block content해야 한다`() = runTest {
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

        assertTrue(result.success, "Tool-backed response should succeed even without verified sources")
        assertTrue(
            result.content?.contains("Here is the policy.") == true,
            "Original content should be preserved when tool was called"
        )
    }

    @Test
    fun `identity resolution guidance로 replace generic unverified copy해야 한다`() = runTest {
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
    fun `slack delivery with tool signal일 때 preserve content with delivery metadata해야 한다`() = runTest {
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = ResponseFilterChain(listOf(VerifiedSourcesResponseFilter())),
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = mockk(relaxed = true),
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = mockk(relaxed = true),
            nowMs = { 1_000L }
        )
        val hookContext = HookContext(
            runId = "run-1",
            userId = "u",
            userPrompt = "Slack으로 배포 공지 보내줘."
        ).apply {
            metadata[ToolCallOrchestrator.TOOL_SIGNALS_METADATA_KEY] = mutableListOf(
                ToolResponseSignal(
                    toolName = "send_message",
                    deliveryPlatform = "slack",
                    deliveryMode = "message_send"
                )
            )
        }

        val result = finalizer.finalize(
            result = AgentResult.success(content = "배포 공지를 전송했습니다.", toolsUsed = listOf("send_message")),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "Slack으로 배포 공지 보내줘."),
            hookContext = hookContext,
            toolsUsed = listOf("send_message"),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertTrue(result.success, "Slack delivery should succeed")
        assertTrue(
            result.content?.contains("배포 공지를 전송했습니다.") == true,
            "Original delivery content should be preserved when tool was called"
        )
        assertEquals(true, result.metadata["deliveryAcknowledged"], "Delivery acknowledgement metadata should be exposed")
        assertEquals(
            "slack",
            (result.metadata["delivery"] as Map<*, *>)["platform"],
            "Delivery metadata should identify Slack as the destination platform"
        )
    }

    @Test
    fun `slack delivery without tool signal일 때 preserve content해야 한다`() = runTest {
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = ResponseFilterChain(listOf(VerifiedSourcesResponseFilter())),
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = mockk(relaxed = true),
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = mockk(relaxed = true),
            nowMs = { 1_000L }
        )

        val result = finalizer.finalize(
            result = AgentResult.success(content = "Slack으로 보내겠습니다.", toolsUsed = listOf("send_message")),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "Slack으로 배포 공지 보내줘."),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "Slack으로 배포 공지 보내줘."),
            toolsUsed = listOf("send_message"),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertTrue(result.success, "Slack delivery without signal should still succeed")
        assertTrue(
            result.content?.contains("Slack으로 보내겠습니다.") == true,
            "Original content should be preserved when tool was called"
        )
        assertEquals(null, result.metadata["deliveryAcknowledged"], "No delivery acknowledgement metadata should be emitted")
    }

    @Test
    fun `output guard blocks the longer response일 때 reject boundary-retried content해야 한다`() = runTest {
        val rejectingStage = object : OutputGuardStage {
            override val stageName = "RejectLong"
            override val order = 1
            override suspend fun check(content: String, context: OutputGuardContext): OutputGuardResult {
                if (content.length > 10) {
                    return OutputGuardResult.Rejected(
                        reason = "harmful content in retry",
                        category = OutputRejectionCategory.POLICY_VIOLATION
                    )
                }
                return OutputGuardResult.Allowed.DEFAULT
            }
        }
        val conversationManager = mockk<ConversationManager>(relaxed = true)
        val hookExecutor = mockk<HookExecutor>(relaxed = true)
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = OutputGuardPipeline(listOf(rejectingStage)),
            responseFilterChain = null,
            boundaries = BoundaryProperties(outputMinChars = 15, outputMinViolationMode = OutputMinViolationMode.RETRY_ONCE),
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
            attemptLongerResponse = { _, _, _ -> "this is a much longer harmful response" }
        )

        assertFalse(result.success, "Guard should reject the boundary-retried content")
        assertEquals(AgentErrorCode.OUTPUT_GUARD_REJECTED, result.errorCode,
            "Error code should be OUTPUT_GUARD_REJECTED when re-run guard blocks retried content")
        coVerify(exactly = 1) { conversationManager.saveHistory(any(), match { !it.success }) }
        coVerify(exactly = 1) {
            hookExecutor.executeAfterAgentComplete(
                any(),
                match { !it.success && it.errorCode == "OUTPUT_GUARD_REJECTED" }
            )
        }
    }

    @Test
    fun `hook 후 rethrow cancellation from해야 한다`() = runTest {
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
            // 예상 결과
        }
    }

    @Test
    fun `tool metadata reports authentication failure일 때 synthesize visible upstream auth guidance해야 한다`() = runTest {
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
    fun `tool metadata blocks access일 때 synthesize visible policy denied response해야 한다`() = runTest {
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
    fun `empty swagger mutation response에 대해 synthesize read only refusal해야 한다`() = runTest {
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

    @Test
    fun `citation is enabled and verified sources exist일 때 append citation section해야 한다`() = runTest {
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = mockk(relaxed = true),
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = mockk(relaxed = true),
            citationProperties = CitationProperties(enabled = true, format = "markdown"),
            nowMs = { 1_000L }
        )
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi").apply {
            addVerifiedSource(VerifiedSource(title = "Policy Doc", url = "https://example.com/policy", toolName = "confluence"))
            addVerifiedSource(VerifiedSource(title = "Guide", url = "https://example.com/guide", toolName = "confluence"))
        }

        val result = finalizer.finalize(
            result = AgentResult.success(content = "Here is the answer."),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = hookContext,
            toolsUsed = listOf("confluence"),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertTrue(result.success, "Result should succeed when citations are appended")
        val content = result.content.orEmpty()
        assertTrue(
            content.contains("\n\n---\nSources:"),
            "Response should contain citation header"
        )
        assertTrue(
            content.contains("[1] Policy Doc (https://example.com/policy)"),
            "First citation should be formatted correctly"
        )
        assertTrue(
            content.contains("[2] Guide (https://example.com/guide)"),
            "Second citation should be formatted correctly"
        )
    }

    @Test
    fun `citation is disabled일 때 not append citations해야 한다`() = runTest {
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = mockk(relaxed = true),
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = mockk(relaxed = true),
            citationProperties = CitationProperties(enabled = false),
            nowMs = { 1_000L }
        )
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi").apply {
            addVerifiedSource(VerifiedSource(title = "Doc", url = "https://example.com/doc"))
        }

        val result = finalizer.finalize(
            result = AgentResult.success(content = "Answer."),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = hookContext,
            toolsUsed = listOf("search"),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertTrue(result.success, "Result should succeed without citations")
        assertFalse(
            result.content.orEmpty().contains("Sources:"),
            "Citation section should not be appended when disabled"
        )
    }

    @Test
    fun `deduplicate sources by url in citations해야 한다`() = runTest {
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = mockk(relaxed = true),
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = mockk(relaxed = true),
            citationProperties = CitationProperties(enabled = true),
            nowMs = { 1_000L }
        )
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi").apply {
            addVerifiedSource(VerifiedSource(title = "Doc A", url = "https://example.com/same"))
            addVerifiedSource(VerifiedSource(title = "Doc B", url = "https://example.com/same"))
            addVerifiedSource(VerifiedSource(title = "Doc C", url = "https://example.com/other"))
        }

        val result = finalizer.finalize(
            result = AgentResult.success(content = "Answer."),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = hookContext,
            toolsUsed = listOf("search"),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertTrue(result.success, "Result should succeed with deduplicated citations")
        val content = result.content.orEmpty()
        assertTrue(
            content.contains("[1] Doc A (https://example.com/same)"),
            "First occurrence of duplicate URL should be kept"
        )
        assertFalse(
            content.contains("[2] Doc B"),
            "Duplicate URL source should be removed"
        )
        assertTrue(
            content.contains("[2] Doc C (https://example.com/other)"),
            "Unique URL source should be numbered correctly after dedup"
        )
    }

    @Test
    fun `handle special characters in citation URLs and titles해야 한다`() = runTest {
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = mockk(relaxed = true),
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = mockk(relaxed = true),
            citationProperties = CitationProperties(enabled = true, format = "markdown"),
            nowMs = { 1_000L }
        )
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi").apply {
            addVerifiedSource(VerifiedSource(
                title = "Policy (v2)",
                url = "https://example.com?q=test&page=1",
                toolName = "confluence"
            ))
            addVerifiedSource(VerifiedSource(
                title = "Guide [Draft] & Notes",
                url = "https://example.com/path#section",
                toolName = "confluence"
            ))
        }

        val result = finalizer.finalize(
            result = AgentResult.success(content = "Here is the answer."),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = hookContext,
            toolsUsed = listOf("confluence"),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertTrue(result.success, "Result should succeed with special-character citations")
        val content = result.content.orEmpty()
        assertTrue(
            content.contains("[1] Policy (v2) (https://example.com?q=test&page=1)"),
            "Citation with parentheses in title and query string in URL should be preserved verbatim"
        )
        assertTrue(
            content.contains("[2] Guide [Draft] & Notes (https://example.com/path#section)"),
            "Citation with brackets, ampersand in title and fragment in URL should be preserved verbatim"
        )
    }

    @Test
    fun `no verified sources exist일 때 not append citations해야 한다`() = runTest {
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = mockk(relaxed = true),
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = mockk(relaxed = true),
            citationProperties = CitationProperties(enabled = true),
            nowMs = { 1_000L }
        )

        val result = finalizer.finalize(
            result = AgentResult.success(content = "Answer without sources."),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = emptyList(),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertTrue(result.success, "Result should succeed without sources")
        assertFalse(
            result.content.orEmpty().contains("Sources:"),
            "Citation section should not be appended when no verified sources exist"
        )
    }

    @Test
    fun `빈 content 성공 응답은 OUTPUT_TOO_SHORT 에러로 변환해야 한다`() = runTest {
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

        val result = finalizer.finalize(
            result = AgentResult.success(content = ""),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "1+1은?"),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "1+1은?"),
            toolsUsed = emptyList(),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertFalse(result.success, "빈 content 성공 응답은 실패로 전환되어야 한다")
        assertEquals(
            AgentErrorCode.OUTPUT_TOO_SHORT, result.errorCode,
            "빈 content 에러 코드는 OUTPUT_TOO_SHORT이어야 한다"
        )
        assertTrue(
            result.content?.isNotBlank() == true,
            "빈 content 실패 결과에는 사용자 안내 메시지가 포함되어야 한다"
        )
        verify(exactly = 1) {
            metrics.recordExecution(match { !it.success && it.errorCode == AgentErrorCode.OUTPUT_TOO_SHORT })
        }
    }

    @Test
    fun `공백만 있는 content 성공 응답도 OUTPUT_TOO_SHORT 에러로 변환해야 한다`() = runTest {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val finalizer = ExecutionResultFinalizer(
            outputGuardPipeline = null,
            responseFilterChain = null,
            boundaries = BoundaryProperties(),
            conversationManager = mockk(relaxed = true),
            hookExecutor = mockk(relaxed = true),
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentMetrics = metrics,
            nowMs = { 1_000L }
        )

        val result = finalizer.finalize(
            result = AgentResult.success(content = "   \n\t  "),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "test"),
            hookContext = HookContext(runId = "run-2", userId = "u", userPrompt = "test"),
            toolsUsed = emptyList(),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertFalse(result.success, "공백만 있는 content도 빈 응답으로 처리되어야 한다")
        assertEquals(AgentErrorCode.OUTPUT_TOO_SHORT, result.errorCode, "에러 코드 확인")
    }

    @Test
    fun `정상 content가 있는 성공 응답은 빈 응답 안전망에 걸리지 않아야 한다`() = runTest {
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
            result = AgentResult.success(content = "2"),
            command = AgentCommand(systemPrompt = "sys", userPrompt = "1+1은?"),
            hookContext = HookContext(runId = "run-3", userId = "u", userPrompt = "1+1은?"),
            toolsUsed = emptyList(),
            startTime = 500L,
            attemptLongerResponse = { _, _, _ -> null }
        )

        assertTrue(result.success, "정상 content가 있으면 성공 상태를 유지해야 한다")
        assertEquals("2", result.content, "원본 content가 유지되어야 한다")
    }
}
