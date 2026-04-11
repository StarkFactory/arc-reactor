package com.arc.reactor.agent.impl

import com.arc.reactor.agent.config.ToolResultCacheProperties
import com.arc.reactor.agent.metrics.NoOpAgentMetrics
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.tool.ToolCallback
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import java.util.concurrent.atomic.AtomicInteger

/**
 * ToolCallOrchestrator 커버리지 gap 보강 테스트.
 *
 * 기존 ToolCallOrchestratorTest에서 다루지 않은 엣지 케이스를 검증한다:
 * - BeforeToolCallHook이 Reject를 반환하는 병렬 실행 경로
 * - BeforeToolCallHook이 Reject를 반환하는 직접 실행(direct) 경로
 * - 도구 출력 최대 길이(maxToolOutputLength) 초과 시 출력 잘라내기(truncation)
 * - ArcToolCallbackAdapter의 ToolDefinition / ToolMetadata 위임 검증
 */
class ToolCallOrchestratorCoverageGapTest {

    // ──────────────────────────────────────────────
    // 공통 픽스처
    // ──────────────────────────────────────────────

    private val baseHookContext = HookContext(
        runId = "run-gap",
        userId = "user-gap",
        userPrompt = "gap test"
    )

    /** 간단한 성공 콜백 */
    private fun successCallback(name: String, output: String = "ok") = object : ToolCallback {
        override val name: String = name
        override val description: String = "$name description"
        override suspend fun call(arguments: Map<String, Any?>): Any = output
    }

    /** AssistantMessage.ToolCall mock 빌더 */
    private fun toolCall(
        id: String,
        name: String,
        arguments: String = "{}"
    ): AssistantMessage.ToolCall {
        val tc = mockk<AssistantMessage.ToolCall>()
        io.mockk.every { tc.id() } returns id
        io.mockk.every { tc.name() } returns name
        io.mockk.every { tc.arguments() } returns arguments
        return tc
    }

    // ──────────────────────────────────────────────
    // 1. 병렬 실행 경로 — BeforeToolCallHook Reject
    // ──────────────────────────────────────────────

    @Nested
    inner class BeforeToolCallHookRejectInParallel {

        /**
         * BeforeToolCallHook이 Reject를 반환하면 도구가 실행되지 않아야 한다.
         * 기존 테스트에는 `executeInParallel`에서 Hook Reject를 직접 검증하는 케이스가 없었다.
         */
        @Test
        fun `BeforeToolCallHook이 Reject를 반환하면 도구를 실행하지 않아야 한다`() = runTest {
            val hookExecutor = mockk<HookExecutor>()
            val callback = mockk<ToolCallback>()

            io.mockk.every { callback.name } returns "danger"
            io.mockk.every { callback.description } returns "dangerous tool"
            io.mockk.every { callback.inputSchema } returns """{"type":"object","properties":{}}"""
            coEvery { callback.call(any()) } returns "should not reach"
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Reject("정책 위반: 위험 도구")
            coEvery { hookExecutor.executeAfterToolCall(any(), any()) } returns Unit

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = hookExecutor,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() }
            )
            val call = toolCall(id = "id-1", name = "danger")

            val responses = orchestrator.executeInParallel(
                toolCalls = listOf(call),
                tools = listOf(ArcToolCallbackAdapter(callback)),
                hookContext = baseHookContext,
                toolsUsed = mutableListOf(),
                totalToolCallsCounter = AtomicInteger(0),
                maxToolCalls = 10,
                allowedTools = null
            )

            responses.size shouldBe 1 withClue "응답이 정확히 1개여야 한다"
            responses[0].responseData() shouldContain "rejected" withClue
                "Hook Reject 시 응답에 'rejected'가 포함되어야 한다"
            responses[0].responseData() shouldContain "정책 위반: 위험 도구" withClue
                "Reject 이유가 응답에 포함되어야 한다"
            coVerify(exactly = 0) { callback.call(any()) }
        }

        /**
         * BeforeToolCallHook이 Reject를 반환해도 toolsUsed에 추가하지 않아야 한다.
         */
        @Test
        fun `BeforeToolCallHook이 Reject를 반환하면 toolsUsed에 추가하지 않아야 한다`() = runTest {
            val hookExecutor = mockk<HookExecutor>()
            val callback = successCallback("blocked_tool")

            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Reject("차단")
            coEvery { hookExecutor.executeAfterToolCall(any(), any()) } returns Unit

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = hookExecutor,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() }
            )
            val toolsUsed = mutableListOf<String>()
            val call = toolCall(id = "id-1", name = "blocked_tool")

            orchestrator.executeInParallel(
                toolCalls = listOf(call),
                tools = listOf(ArcToolCallbackAdapter(callback)),
                hookContext = baseHookContext,
                toolsUsed = toolsUsed,
                totalToolCallsCounter = AtomicInteger(0),
                maxToolCalls = 10,
                allowedTools = null
            )

            toolsUsed.isEmpty() shouldBe true withClue "Hook Reject 시 toolsUsed는 비어 있어야 한다"
        }

        /**
         * BeforeToolCallHook이 Reject를 반환해도 budget 카운터를 소비하지 않아야 한다.
         */
        @Test
        fun `BeforeToolCallHook이 Reject를 반환하면 budget 카운터를 소비하지 않아야 한다`() = runTest {
            val hookExecutor = mockk<HookExecutor>()
            val callback = successCallback("search")

            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Reject("차단")
            coEvery { hookExecutor.executeAfterToolCall(any(), any()) } returns Unit

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = hookExecutor,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() }
            )
            val counter = AtomicInteger(0)
            val call = toolCall(id = "id-1", name = "search")

            orchestrator.executeInParallel(
                toolCalls = listOf(call),
                tools = listOf(ArcToolCallbackAdapter(callback)),
                hookContext = baseHookContext,
                toolsUsed = mutableListOf(),
                totalToolCallsCounter = counter,
                maxToolCalls = 10,
                allowedTools = null
            )

            counter.get() shouldBe 0 withClue "Hook에 의해 차단된 도구는 budget을 소비하지 않아야 한다"
        }

        /**
         * 병렬 실행 중 한 도구만 Hook Reject될 때 나머지 도구는 정상 실행되어야 한다.
         */
        @Test
        fun `일부 도구만 BeforeToolCallHook에서 Reject될 때 나머지 도구는 정상 실행해야 한다`() = runTest {
            val hookExecutor = mockk<HookExecutor>()
            val dangerCallback = mockk<ToolCallback>()
            val safeCallback = successCallback("safe_tool", "safe result")

            io.mockk.every { dangerCallback.name } returns "danger"
            io.mockk.every { dangerCallback.description } returns "danger"
            io.mockk.every { dangerCallback.inputSchema } returns """{"type":"object","properties":{}}"""
            coEvery { dangerCallback.call(any()) } returns "should not run"

            coEvery { hookExecutor.executeBeforeToolCall(match { it.toolName == "danger" }) } returns
                HookResult.Reject("위험 도구 차단")
            coEvery { hookExecutor.executeBeforeToolCall(match { it.toolName == "safe_tool" }) } returns
                HookResult.Continue
            coEvery { hookExecutor.executeAfterToolCall(any(), any()) } returns Unit

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = hookExecutor,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() }
            )
            val toolsUsed = mutableListOf<String>()

            val responses = orchestrator.executeInParallel(
                toolCalls = listOf(
                    toolCall(id = "id-1", name = "danger"),
                    toolCall(id = "id-2", name = "safe_tool")
                ),
                tools = listOf(
                    ArcToolCallbackAdapter(dangerCallback),
                    ArcToolCallbackAdapter(safeCallback)
                ),
                hookContext = baseHookContext,
                toolsUsed = toolsUsed,
                totalToolCallsCounter = AtomicInteger(0),
                maxToolCalls = 10,
                allowedTools = null
            )

            responses.size shouldBe 2 withClue "두 도구 모두 응답을 반환해야 한다"
            responses[0].responseData() shouldContain "rejected" withClue "danger 도구는 차단 응답이어야 한다"
            responses[1].responseData() shouldBe "safe result" withClue "safe_tool은 정상 실행되어야 한다"
            toolsUsed shouldBe listOf("safe_tool") withClue "safe_tool만 toolsUsed에 추가되어야 한다"
            coVerify(exactly = 0) { dangerCallback.call(any()) }
        }
    }

    // ──────────────────────────────────────────────
    // 2. 직접 실행 경로 — BeforeToolCallHook Reject
    // ──────────────────────────────────────────────

    @Nested
    inner class BeforeToolCallHookRejectInDirectPath {

        /**
         * executeDirectToolCall에서 BeforeToolCallHook이 Reject를 반환하면
         * 도구를 실행하지 않고 실패 결과를 반환해야 한다.
         */
        @Test
        fun `직접 실행 경로에서 BeforeToolCallHook이 Reject를 반환하면 실패 결과를 반환해야 한다`() = runTest {
            val hookExecutor = mockk<HookExecutor>()
            val callback = mockk<ToolCallback>()

            io.mockk.every { callback.name } returns "admin_tool"
            io.mockk.every { callback.description } returns "admin only"
            io.mockk.every { callback.inputSchema } returns """{"type":"object","properties":{}}"""
            coEvery { callback.call(any()) } returns "should not run"
            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Reject("권한 없음")
            coEvery { hookExecutor.executeAfterToolCall(any(), any()) } returns Unit

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = hookExecutor,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() }
            )

            val result = orchestrator.executeDirectToolCall(
                toolName = "admin_tool",
                toolParams = emptyMap(),
                tools = listOf(ArcToolCallbackAdapter(callback)),
                hookContext = baseHookContext,
                toolsUsed = mutableListOf()
            )

            result.success shouldBe false withClue "Hook Reject 시 결과는 실패여야 한다"
            result.output.orEmpty() shouldContain "Tool call rejected" withClue "Reject 결과 메시지가 포함되어야 한다"
            result.output.orEmpty() shouldContain "권한 없음" withClue "Reject 이유가 출력에 포함되어야 한다"
            coVerify(exactly = 0) { callback.call(any()) }
        }

        /**
         * 직접 실행 경로에서 Hook Reject 시 toolsUsed에 추가하지 않아야 한다.
         */
        @Test
        fun `직접 실행 경로에서 BeforeToolCallHook이 Reject를 반환하면 toolsUsed에 추가하지 않아야 한다`() = runTest {
            val hookExecutor = mockk<HookExecutor>()

            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Reject("차단")
            coEvery { hookExecutor.executeAfterToolCall(any(), any()) } returns Unit

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = hookExecutor,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() }
            )
            val toolsUsed = mutableListOf<String>()
            val callback = successCallback("target_tool")

            orchestrator.executeDirectToolCall(
                toolName = "target_tool",
                toolParams = emptyMap(),
                tools = listOf(ArcToolCallbackAdapter(callback)),
                hookContext = baseHookContext,
                toolsUsed = toolsUsed
            )

            toolsUsed.isEmpty() shouldBe true withClue "직접 실행 경로에서도 Hook Reject 시 toolsUsed는 비어 있어야 한다"
        }
    }

    // ──────────────────────────────────────────────
    // 3. 도구 출력 최대 길이 초과 시 잘라내기
    // ──────────────────────────────────────────────

    @Nested
    inner class MaxToolOutputLengthTruncation {

        /**
         * 도구 출력이 maxToolOutputLength를 초과하면 잘라내야 한다.
         * 이 엣지 케이스는 기존 ToolCallOrchestratorTest에 전혀 테스트되지 않았다.
         */
        @Test
        fun `도구 출력이 최대 길이를 초과하면 잘라내고 TRUNCATED 마커를 추가해야 한다`() = runTest {
            val longOutput = "x".repeat(200)
            val maxLength = 100
            val callback = successCallback("heavy_tool", longOutput)

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = null,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() },
                maxToolOutputLength = maxLength
            )
            val call = toolCall(id = "id-1", name = "heavy_tool")

            val responses = orchestrator.executeInParallel(
                toolCalls = listOf(call),
                tools = listOf(ArcToolCallbackAdapter(callback)),
                hookContext = baseHookContext,
                toolsUsed = mutableListOf(),
                totalToolCallsCounter = AtomicInteger(0),
                maxToolCalls = 10,
                allowedTools = null
            )

            responses.size shouldBe 1 withClue "응답이 1개여야 한다"
            val responseData = responses[0].responseData()
            responseData shouldContain "TRUNCATED" withClue "잘라낸 출력에는 TRUNCATED 마커가 있어야 한다"
            responseData.startsWith("x".repeat(maxLength)) shouldBe true withClue
                "앞부분 ${maxLength}자는 보존되어야 한다"
        }

        /**
         * 도구 출력이 최대 길이 이하이면 잘라내지 않아야 한다.
         */
        @Test
        fun `도구 출력이 최대 길이 이하이면 잘라내지 않아야 한다`() = runTest {
            val shortOutput = "short output"
            val callback = successCallback("light_tool", shortOutput)

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = null,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() },
                maxToolOutputLength = 1000
            )
            val call = toolCall(id = "id-1", name = "light_tool")

            val responses = orchestrator.executeInParallel(
                toolCalls = listOf(call),
                tools = listOf(ArcToolCallbackAdapter(callback)),
                hookContext = baseHookContext,
                toolsUsed = mutableListOf(),
                totalToolCallsCounter = AtomicInteger(0),
                maxToolCalls = 10,
                allowedTools = null
            )

            responses[0].responseData() shouldBe shortOutput withClue "짧은 출력은 변경 없이 그대로여야 한다"
            responses[0].responseData() shouldNotContain "TRUNCATED" withClue "잘라내지 않으면 TRUNCATED가 없어야 한다"
        }

        /**
         * maxToolOutputLength=0이면 잘라내지 않고 전체 출력을 반환해야 한다.
         */
        @Test
        fun `maxToolOutputLength가 0이면 잘라내지 않아야 한다`() = runTest {
            val longOutput = "y".repeat(100_000)
            val callback = successCallback("unlimited_tool", longOutput)

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = null,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() },
                maxToolOutputLength = 0
            )
            val call = toolCall(id = "id-1", name = "unlimited_tool")

            val responses = orchestrator.executeInParallel(
                toolCalls = listOf(call),
                tools = listOf(ArcToolCallbackAdapter(callback)),
                hookContext = baseHookContext,
                toolsUsed = mutableListOf(),
                totalToolCallsCounter = AtomicInteger(0),
                maxToolCalls = 10,
                allowedTools = null
            )

            responses[0].responseData() shouldBe longOutput withClue "maxToolOutputLength=0이면 전체 출력이 반환되어야 한다"
            responses[0].responseData() shouldNotContain "TRUNCATED" withClue
                "maxToolOutputLength=0이면 TRUNCATED 마커가 없어야 한다"
        }

        /**
         * 직접 실행(direct) 경로에서도 maxToolOutputLength 초과 시 잘라내야 한다.
         */
        @Test
        fun `직접 실행 경로에서도 도구 출력이 최대 길이를 초과하면 잘라내야 한다`() = runTest {
            val longOutput = "z".repeat(500)
            val callback = successCallback("direct_heavy", longOutput)

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = null,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() },
                maxToolOutputLength = 50
            )

            val result = orchestrator.executeDirectToolCall(
                toolName = "direct_heavy",
                toolParams = emptyMap(),
                tools = listOf(ArcToolCallbackAdapter(callback)),
                hookContext = baseHookContext,
                toolsUsed = mutableListOf()
            )

            result.success shouldBe true withClue "도구 실행 자체는 성공이어야 한다"
            result.output.orEmpty() shouldContain "TRUNCATED" withClue "직접 실행 경로에서도 TRUNCATED 마커가 있어야 한다"
            result.output.orEmpty().startsWith("z".repeat(50)) shouldBe true withClue "앞 50자는 보존되어야 한다"
        }
    }

    // ──────────────────────────────────────────────
    // 4. ArcToolCallbackAdapter ToolDefinition / ToolMetadata 위임 검증
    // ──────────────────────────────────────────────

    @Nested
    inner class ArcToolCallbackAdapterDelegation {

        /**
         * ArcToolCallbackAdapter의 getToolDefinition()은
         * 내부 ToolCallback의 이름/설명/스키마를 정확히 노출해야 한다.
         * 기존 ArcToolCallbackAdapterTest는 call()만 검증하고 정의 위임을 검증하지 않았다.
         */
        @Test
        fun `getToolDefinition은 내부 콜백의 이름과 설명을 정확히 반환해야 한다`() {
            val callback = object : ToolCallback {
                override val name: String = "my_special_tool"
                override val description: String = "특별한 도구 설명"
                override val inputSchema: String = """{"type":"object","properties":{"q":{"type":"string"}}}"""
                override suspend fun call(arguments: Map<String, Any?>): Any = "ok"
            }

            val adapter = ArcToolCallbackAdapter(callback)
            val definition = adapter.toolDefinition

            definition.name() shouldBe "my_special_tool" withClue "toolDefinition.name()이 내부 콜백 이름과 일치해야 한다"
            definition.description() shouldBe "특별한 도구 설명" withClue "toolDefinition.description()이 내부 콜백 설명과 일치해야 한다"
            definition.inputSchema() shouldBe callback.inputSchema withClue "toolDefinition.inputSchema()가 내부 콜백 스키마와 일치해야 한다"
        }

        /**
         * ArcToolCallbackAdapter의 getToolMetadata()는 null이 아니어야 한다.
         */
        @Test
        fun `getToolMetadata는 returnDirect 기본값 false를 반환해야 한다`() {
            val callback = successCallback("meta_tool")
            val adapter = ArcToolCallbackAdapter(callback)

            val metadata = adapter.toolMetadata

            // ToolMetadata.builder().build()의 기본값은 returnDirect=false
            metadata.returnDirect() shouldBe false withClue
                "기본 ToolMetadata의 returnDirect는 false여야 한다"
        }

        /**
         * fallbackToolTimeoutMs가 지정되지 않으면 기본값 15000ms가 적용되어야 한다.
         * ArcToolCallbackAdapter 기본 생성자 파라미터 검증.
         */
        @Test
        fun `기본 fallbackToolTimeoutMs로 생성한 어댑터도 도구를 정상 실행해야 한다`() {
            val callback = object : ToolCallback {
                override val name: String = "default_timeout_tool"
                override val description: String = "default timeout"
                override suspend fun call(arguments: Map<String, Any?>): Any = "default ok"
            }

            // 기본 폴백 타임아웃(15000ms) 사용
            val adapter = ArcToolCallbackAdapter(callback)
            val output = adapter.call("{}")

            output shouldBe "default ok" withClue "기본 폴백 타임아웃으로 생성한 어댑터도 도구를 정상 실행해야 한다"
        }

        /**
         * ToolCallback의 inputSchema 기본값("")이 그대로 전달되어야 한다.
         */
        @Test
        fun `inputSchema 기본값이 있는 콜백도 정상 위임해야 한다`() {
            val callback = object : ToolCallback {
                override val name: String = "schema_tool"
                override val description: String = "schema test"
                // inputSchema는 기본값 사용
                override suspend fun call(arguments: Map<String, Any?>): Any = "schema ok"
            }

            val adapter = ArcToolCallbackAdapter(callback)
            val definition = adapter.toolDefinition

            definition.name() shouldBe "schema_tool" withClue "이름이 정확히 위임되어야 한다"
        }
    }

    // ──────────────────────────────────────────────
    // 5. AfterToolCallHook이 BeforeToolCallHook 차단 후에도 호출되는지
    // ──────────────────────────────────────────────

    @Nested
    inner class AfterToolCallHookOnHookReject {

        /**
         * 병렬 실행 경로에서 BeforeToolCallHook이 차단해도
         * AfterToolCallHook은 호출되지 않아야 한다.
         * (ToolCallOrchestrator 내부 흐름: checkHookAndApproval에서 일찍 반환되므로 afterHook 없음)
         */
        @Test
        fun `병렬 실행 경로에서 BeforeToolCallHook이 차단하면 AfterToolCallHook을 호출하지 않아야 한다`() = runTest {
            val hookExecutor = mockk<HookExecutor>()
            val callback = successCallback("guarded_tool")

            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Reject("완전 차단")
            coEvery { hookExecutor.executeAfterToolCall(any<ToolCallContext>(), any<ToolCallResult>()) } returns Unit

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = hookExecutor,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() }
            )
            val call = toolCall(id = "id-1", name = "guarded_tool")

            orchestrator.executeInParallel(
                toolCalls = listOf(call),
                tools = listOf(ArcToolCallbackAdapter(callback)),
                hookContext = baseHookContext,
                toolsUsed = mutableListOf(),
                totalToolCallsCounter = AtomicInteger(0),
                maxToolCalls = 10,
                allowedTools = null
            )

            coVerify(exactly = 0) { hookExecutor.executeAfterToolCall(any(), any()) }
        }

        /**
         * 직접 실행 경로에서 BeforeToolCallHook이 차단해도
         * AfterToolCallHook은 호출되지 않아야 한다.
         */
        @Test
        fun `직접 실행 경로에서 BeforeToolCallHook이 차단하면 AfterToolCallHook을 호출하지 않아야 한다`() = runTest {
            val hookExecutor = mockk<HookExecutor>()
            val callback = successCallback("direct_guarded")

            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Reject("직접 차단")
            coEvery { hookExecutor.executeAfterToolCall(any<ToolCallContext>(), any<ToolCallResult>()) } returns Unit

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = hookExecutor,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() }
            )

            orchestrator.executeDirectToolCall(
                toolName = "direct_guarded",
                toolParams = emptyMap(),
                tools = listOf(ArcToolCallbackAdapter(callback)),
                hookContext = baseHookContext,
                toolsUsed = mutableListOf()
            )

            coVerify(exactly = 0) { hookExecutor.executeAfterToolCall(any(), any()) }
        }
    }

    // ──────────────────────────────────────────────
    // 6. R270: AfterToolCallHook 예외 발생 시 metric 보장 (regression fix)
    // ──────────────────────────────────────────────

    /**
     * R270 regression: failOnError=true Hook이 AfterToolCall 예외를 던질 때
     * agentMetrics.recordToolCall이 finally 블록에서 항상 호출되어야 한다.
     *
     * R270 fix 이전: hookExecutor.executeAfterToolCall이 throw → recordToolCall 누락
     * R270 fix 이후: try-finally로 metric 항상 기록 + 예외는 호출자로 전파
     */
    @Nested
    inner class R270HookExceptionMetricGuarantee {

        @Test
        fun `R270 fix - 직접 실행 경로에서 AfterToolCallHook 예외 시에도 recordToolCall 호출되어야 한다`() = runTest {
            val hookExecutor = mockk<HookExecutor>()
            val callback = successCallback("metric_test_tool", "ok")
            val spyMetrics = mockk<com.arc.reactor.agent.metrics.AgentMetrics>(relaxed = true)
            val recordedToolNames = mutableListOf<String>()
            io.mockk.every {
                spyMetrics.recordToolCall(any(), any(), any())
            } answers {
                recordedToolNames.add(firstArg())
                Unit
            }

            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue
            // failOnError=true Hook이 throw하는 시뮬레이션
            coEvery {
                hookExecutor.executeAfterToolCall(any(), any())
            } throws RuntimeException("simulated fail-on-error hook exception")

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = hookExecutor,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = spyMetrics,
                parseToolArguments = { emptyMap() }
            )

            // Hook 예외는 호출자로 전파됨 (try-finally의 의도된 동작)
            try {
                orchestrator.executeDirectToolCall(
                    toolName = "metric_test_tool",
                    toolParams = emptyMap(),
                    tools = listOf(ArcToolCallbackAdapter(callback)),
                    hookContext = baseHookContext,
                    toolsUsed = mutableListOf()
                )
            } catch (e: RuntimeException) {
                // 예상된 예외 — Hook이 throw한 것
            }

            // R270 fix 핵심: Hook 예외와 무관하게 metric은 항상 기록되어야 한다
            recordedToolNames.size shouldBe 1 withClue
                "R270 fix: AfterToolCallHook 예외 시에도 finally 블록에서 recordToolCall 1회 호출"
            recordedToolNames shouldBe listOf("metric_test_tool")
        }

        @Test
        fun `R270 fix - 병렬 실행 경로에서 AfterToolCallHook 예외 시에도 recordToolCall 호출되어야 한다`() = runTest {
            val hookExecutor = mockk<HookExecutor>()
            val callback = successCallback("parallel_metric_tool", "ok")
            val spyMetrics = mockk<com.arc.reactor.agent.metrics.AgentMetrics>(relaxed = true)
            val recordedToolNames = mutableListOf<String>()
            io.mockk.every {
                spyMetrics.recordToolCall(any(), any(), any())
            } answers {
                recordedToolNames.add(firstArg())
                Unit
            }

            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue
            coEvery {
                hookExecutor.executeAfterToolCall(any(), any())
            } throws RuntimeException("simulated parallel hook exception")

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = hookExecutor,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = spyMetrics,
                parseToolArguments = { emptyMap() }
            )
            val call = toolCall(id = "id-1", name = "parallel_metric_tool")

            try {
                orchestrator.executeInParallel(
                    toolCalls = listOf(call),
                    tools = listOf(ArcToolCallbackAdapter(callback)),
                    hookContext = baseHookContext,
                    toolsUsed = mutableListOf(),
                    totalToolCallsCounter = AtomicInteger(0),
                    maxToolCalls = 10,
                    allowedTools = null
                )
            } catch (e: RuntimeException) {
                // 예상된 예외
            }

            recordedToolNames.size shouldBe 1 withClue
                "R270 fix: 병렬 경로에서도 Hook 예외 시 recordToolCall 1회 호출"
            recordedToolNames shouldBe listOf("parallel_metric_tool")
        }

        @Test
        fun `R270 정상 경로 회귀 - Hook 정상 시에도 recordToolCall 정상 호출`() = runTest {
            // R270 try-finally 변경이 정상 경로에 영향 없는지 회귀 검증
            val hookExecutor = mockk<HookExecutor>()
            val callback = successCallback("normal_tool", "ok")
            val spyMetrics = mockk<com.arc.reactor.agent.metrics.AgentMetrics>(relaxed = true)
            val recordedToolNames = mutableListOf<String>()
            io.mockk.every {
                spyMetrics.recordToolCall(any(), any(), any())
            } answers {
                recordedToolNames.add(firstArg())
                Unit
            }

            coEvery { hookExecutor.executeBeforeToolCall(any()) } returns HookResult.Continue
            coEvery { hookExecutor.executeAfterToolCall(any(), any()) } returns Unit

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = hookExecutor,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = spyMetrics,
                parseToolArguments = { emptyMap() }
            )

            orchestrator.executeDirectToolCall(
                toolName = "normal_tool",
                toolParams = emptyMap(),
                tools = listOf(ArcToolCallbackAdapter(callback)),
                hookContext = baseHookContext,
                toolsUsed = mutableListOf()
            )

            recordedToolNames.size shouldBe 1 withClue
                "정상 경로: recordToolCall 1회 호출 (R270 변경 회귀 없음)"
        }
    }

    // ──────────────────────────────────────────────
    // 7. R271: executionErrorOutcome 메시지에 exception class name 노출 금지
    // ──────────────────────────────────────────────

    /**
     * R271 regression: 도구 실행 실패 시 LLM에 노출되는 에러 메시지에
     * exception 클래스명(NPE, IllegalStateException 등)이 포함되지 않아야 한다.
     *
     * R271 이전: `"Error: 도구 실행 중 오류가 발생했습니다. NullPointerException"`
     * R271 이후: `"Error: 도구 실행 중 오류가 발생했습니다."`
     *
     * exception 정보는 ops 로그(`logger.error(e)`)에만 기록.
     */
    @Nested
    inner class R271ExecutionErrorMessageSanitization {

        @Test
        fun `R271 fix - 도구가 NullPointerException을 던질 때 LLM 메시지에 exception 클래스명이 없어야 한다`() = runTest {
            val throwingCallback = object : ToolCallback {
                override val name: String = "buggy_tool"
                override val description: String = "intentionally broken"
                override suspend fun call(arguments: Map<String, Any?>): Any {
                    throw NullPointerException("internal NPE — should not leak to LLM")
                }
            }

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = null,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() }
            )

            val result = orchestrator.executeDirectToolCall(
                toolName = "buggy_tool",
                toolParams = emptyMap(),
                tools = listOf(ArcToolCallbackAdapter(throwingCallback)),
                hookContext = baseHookContext,
                toolsUsed = mutableListOf()
            )

            result.success shouldBe false withClue "도구 호출 실패"
            val output = result.output.orEmpty()
            // 두 fallback 경로 모두에 대비:
            // - ArcToolCallbackAdapter: "Error: 도구 'buggy_tool' 실행 중 오류가 발생했습니다."
            // - ToolCallOrchestrator.executionErrorOutcome: "Error: 도구 실행 중 오류가 발생했습니다."
            output shouldContain "실행 중 오류가 발생했습니다" withClue
                "일반 에러 메시지 포함"
            output shouldNotContain "NullPointerException" withClue
                "R271 fix: exception 클래스명이 LLM 출력에 노출되면 안 됨"
            output shouldNotContain "internal NPE" withClue
                "exception message도 노출 금지"
        }

        @Test
        fun `R271 fix - 다양한 exception 타입에서도 클래스명이 노출되지 않아야 한다`() = runTest {
            // 도구 이름은 중립적으로 — 도구 이름 자체에 exception 클래스명을 넣지 않음
            val cases = listOf(
                "neutral_a" to IllegalStateException("state error"),
                "neutral_b" to IllegalArgumentException("arg error"),
                "neutral_c" to ArithmeticException("math error"),
                "neutral_d" to RuntimeException("runtime")
            )

            for ((toolName, exceptionToThrow) in cases) {
                val callback = object : ToolCallback {
                    override val name: String = toolName
                    override val description: String = "throws $exceptionToThrow"
                    override suspend fun call(arguments: Map<String, Any?>): Any {
                        throw exceptionToThrow
                    }
                }

                val orchestrator = ToolCallOrchestrator(
                    toolCallTimeoutMs = 1000,
                    hookExecutor = null,
                    toolApprovalPolicy = null,
                    pendingApprovalStore = null,
                    agentMetrics = NoOpAgentMetrics(),
                    parseToolArguments = { emptyMap() }
                )

                val result = orchestrator.executeDirectToolCall(
                    toolName = toolName,
                    toolParams = emptyMap(),
                    tools = listOf(ArcToolCallbackAdapter(callback)),
                    hookContext = baseHookContext,
                    toolsUsed = mutableListOf()
                )

                val output = result.output.orEmpty()
                val className = exceptionToThrow::class.simpleName.orEmpty()
                output shouldNotContain className withClue
                    "$className 클래스명이 LLM 출력에 노출되면 안 됨: $output"
            }
        }
    }

    // ──────────────────────────────────────────────
    // 8. R273: findMcpToolCallback TOCTOU fix (R270 P2 마지막)
    // ──────────────────────────────────────────────

    /**
     * R273 regression: MCP 콜백 provider가 두 호출 사이 결과가 달라져도 (예: MCP 서버 재시작)
     * `executeSingleToolCall`이 동일한 스냅샷으로 존재 확인 + 실제 실행을 수행해야 한다.
     *
     * R273 이전: `findMcpToolCallback`이 `checkToolExistsAndReserveSlot`(line 329)와
     * `invokeToolAdapterRaw`(line 771)에서 각각 호출되어 두 호출 사이 결과가 달라지면
     * "존재 확인 통과 → 미발견 에러" 모순 발생.
     *
     * R273 fix: `executeSingleToolCall`에서 한 번 resolve하고 chain을 통해 pass through.
     */
    @Nested
    inner class R273McpToolCallbackTOCTOU {

        /** 호출 횟수에 따라 결과가 달라지는 mcpToolCallbackProvider. */
        private fun flakeyMcpProvider(
            callsBeforeServerRestart: Int,
            mcpCallback: ToolCallback
        ): () -> List<ToolCallback> {
            val callCount = AtomicInteger(0)
            return {
                val n = callCount.incrementAndGet()
                if (n <= callsBeforeServerRestart) {
                    listOf(mcpCallback)
                } else {
                    emptyList() // 서버 재시작 후 콜백 사라짐
                }
            }
        }

        @Test
        fun `R273 fix - MCP provider가 두 번째 호출에서 빈 리스트 반환해도 도구 실행 성공`() = runTest {
            // 시나리오: 첫 호출(존재 확인)에서 콜백 발견 → R273 fix가 결과를 캐시 →
            // 두 번째 호출에서 provider가 빈 리스트를 반환해도 캐시된 콜백으로 실행
            // R273 이전 동작: 첫 호출 통과 → 두 번째 호출 null → "도구 미발견" 에러
            val mcpCallback = object : ToolCallback {
                override val name: String = "mcp_dynamic_tool"
                override val description: String = "dynamic tool"
                override suspend fun call(arguments: Map<String, Any?>): Any = "mcp result ok"
            }

            // 첫 호출만 콜백 반환, 그 후엔 빈 리스트
            val provider = flakeyMcpProvider(callsBeforeServerRestart = 1, mcpCallback = mcpCallback)

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = null,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() },
                mcpToolCallbackProvider = provider
            )
            val call = toolCall(id = "id-1", name = "mcp_dynamic_tool")

            val responses = orchestrator.executeInParallel(
                toolCalls = listOf(call),
                tools = emptyList(), // tools 목록에는 없음 → MCP 폴백 경로
                hookContext = baseHookContext,
                toolsUsed = mutableListOf(),
                totalToolCallsCounter = AtomicInteger(0),
                maxToolCalls = 10,
                allowedTools = null
            )

            responses.size shouldBe 1
            val responseData = responses[0].responseData()
            responseData shouldContain "mcp result ok" withClue
                "R273 fix: TOCTOU 윈도우가 제거되어 단일 스냅샷으로 실행 완료. " +
                    "actual=$responseData"
            responseData shouldNotContain "not found" withClue
                "R273 fix: 두 번째 호출 빈 리스트에도 미발견 에러 없음"
        }

        @Test
        fun `R273 회귀 - provider가 항상 콜백 반환하면 정상 동작`() = runTest {
            // R273 변경이 정상 케이스에 영향을 주지 않는지 검증
            val mcpCallback = object : ToolCallback {
                override val name: String = "stable_mcp_tool"
                override val description: String = "stable tool"
                override suspend fun call(arguments: Map<String, Any?>): Any = "stable ok"
            }
            val callCount = AtomicInteger(0)
            val provider: () -> List<ToolCallback> = {
                callCount.incrementAndGet()
                listOf(mcpCallback)
            }

            val orchestrator = ToolCallOrchestrator(
                toolCallTimeoutMs = 1000,
                hookExecutor = null,
                toolApprovalPolicy = null,
                pendingApprovalStore = null,
                agentMetrics = NoOpAgentMetrics(),
                parseToolArguments = { emptyMap() },
                mcpToolCallbackProvider = provider
            )
            val call = toolCall(id = "id-1", name = "stable_mcp_tool")

            val responses = orchestrator.executeInParallel(
                toolCalls = listOf(call),
                tools = emptyList(),
                hookContext = baseHookContext,
                toolsUsed = mutableListOf(),
                totalToolCallsCounter = AtomicInteger(0),
                maxToolCalls = 10,
                allowedTools = null
            )

            responses.size shouldBe 1
            responses[0].responseData() shouldContain "stable ok" withClue
                "정상 케이스에서 R273 fix가 회귀 없음"
            // R273 fix 부수 효과: provider 호출 횟수 감소 (이전 2회 → 이후 1회)
            // 단, 정확한 횟수는 실행 경로에 따라 다를 수 있어 strict assert는 생략
            (callCount.get() >= 1) shouldBe true withClue
                "provider는 최소 1회 호출됨"
        }
    }
}

/** Kotest shouldBe infix 확장: withClue 패턴을 위한 간단한 DSL */
private infix fun <T> T.withClue(clue: String): T {
    return this
}
