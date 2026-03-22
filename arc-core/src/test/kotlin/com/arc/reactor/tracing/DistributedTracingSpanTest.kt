package com.arc.reactor.tracing

import com.arc.reactor.guard.GuardStage
import com.arc.reactor.guard.impl.GuardPipeline
import com.arc.reactor.guard.model.GuardCommand
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.guard.output.OutputGuardContext
import com.arc.reactor.guard.output.OutputGuardPipeline
import com.arc.reactor.guard.output.OutputGuardResult
import com.arc.reactor.guard.output.OutputGuardStage
import com.arc.reactor.guard.output.OutputRejectionCategory
import com.arc.reactor.hook.AfterAgentCompleteHook
import com.arc.reactor.hook.AfterToolCallHook
import com.arc.reactor.hook.BeforeAgentStartHook
import com.arc.reactor.hook.BeforeToolCallHook
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.memory.DefaultConversationManager
import com.arc.reactor.memory.InMemoryMemoryStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Guard/Hook/Memory 스팬 생성을 검증하는 테스트.
 *
 * 분산 추적 완성 — W3C Trace Context, Guard/Hook/Memory 스팬 추가 검증.
 */
class DistributedTracingSpanTest {

    private lateinit var tracer: ArcReactorTracer
    private lateinit var spanHandle: ArcReactorTracer.SpanHandle
    private val capturedSpanNames = mutableListOf<String>()
    private val capturedAttributes = mutableListOf<Map<String, String>>()

    @BeforeEach
    fun setup() {
        capturedSpanNames.clear()
        capturedAttributes.clear()
        spanHandle = mockk(relaxed = true)
        tracer = mockk()
        every { tracer.startSpan(any(), any()) } answers {
            capturedSpanNames.add(firstArg())
            capturedAttributes.add(secondArg())
            spanHandle
        }
    }

    // ─── Guard 스팬 테스트 ─────────────────────────────

    @Nested
    inner class InputGuardSpan {

        @Test
        fun `모든 단계 통과 시 arc-guard-input 스팬을 생성해야 한다`() = runTest {
            val stage = passingStage("validation")
            val pipeline = GuardPipeline(
                stages = listOf(stage),
                tracer = tracer
            )

            pipeline.guard(GuardCommand(userId = "user-1", text = "Hello"))

            assertTrue(
                capturedSpanNames.contains("arc.guard.input"),
                "arc.guard.input 스팬이 생성되어야 한다"
            )
            verify(atLeast = 1) { spanHandle.setAttribute("guard.result", "allowed") }
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `거부 시 guard-result를 rejected로 설정해야 한다`() = runTest {
            val stage = rejectingStage("injection", "blocked")
            val pipeline = GuardPipeline(
                stages = listOf(stage),
                tracer = tracer
            )

            pipeline.guard(GuardCommand(userId = "user-1", text = "bad input"))

            verify { spanHandle.setAttribute("guard.result", "rejected") }
            verify { spanHandle.setAttribute("guard.stage", "injection") }
            verify { spanHandle.setAttribute("guard.reason", "blocked") }
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `단계 예외 시 에러를 스팬에 기록해야 한다`() = runTest {
            val error = RuntimeException("stage error")
            val stage = throwingStage("failing-stage", error)
            val pipeline = GuardPipeline(
                stages = listOf(stage),
                tracer = tracer
            )

            pipeline.guard(GuardCommand(userId = "user-1", text = "test"))

            verify { spanHandle.setAttribute("guard.result", "error") }
            verify { spanHandle.setError(error) }
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `단계 수를 스팬 속성에 포함해야 한다`() = runTest {
            val pipeline = GuardPipeline(
                stages = listOf(passingStage("s1"), passingStage("s2")),
                tracer = tracer
            )

            pipeline.guard(GuardCommand(userId = "user-1", text = "test"))

            val attrs = capturedAttributes.firstOrNull {
                capturedSpanNames[capturedAttributes.indexOf(it)] == "arc.guard.input"
            }
            assertNotNull(attrs, "arc.guard.input 스팬의 속성이 존재해야 한다")
            assertEquals("2", attrs!!["guard.stage.count"], "단계 수가 2여야 한다")
        }

        @Test
        fun `빈 파이프라인은 스팬을 생성하지 않아야 한다`() = runTest {
            val pipeline = GuardPipeline(stages = emptyList(), tracer = tracer)

            pipeline.guard(GuardCommand(userId = "user-1", text = "test"))

            assertTrue(
                capturedSpanNames.isEmpty(),
                "빈 파이프라인에서는 스팬이 생성되지 않아야 한다"
            )
        }
    }

    @Nested
    inner class OutputGuardSpan {

        @Test
        fun `통과 시 arc-guard-output 스팬을 생성해야 한다`() = runTest {
            val stage = allowingOutputStage("pii-check")
            val pipeline = OutputGuardPipeline(
                stages = listOf(stage),
                tracer = tracer
            )

            pipeline.check("safe content", outputContext())

            assertTrue(
                capturedSpanNames.contains("arc.guard.output"),
                "arc.guard.output 스팬이 생성되어야 한다"
            )
            verify { spanHandle.setAttribute("guard.result", "allowed") }
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `거부 시 rejected 속성을 설정해야 한다`() = runTest {
            val stage = rejectingOutputStage("pii-leak", "PII detected")
            val pipeline = OutputGuardPipeline(
                stages = listOf(stage),
                tracer = tracer
            )

            pipeline.check("SSN: 123-45-6789", outputContext())

            verify { spanHandle.setAttribute("guard.result", "rejected") }
            verify { spanHandle.setAttribute("guard.stage", "pii-leak") }
            verify { spanHandle.setAttribute("guard.reason", "PII detected") }
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `수정 시 modified 결과를 기록해야 한다`() = runTest {
            val stage = modifyingOutputStage("pii-mask", "masked PII")
            val pipeline = OutputGuardPipeline(
                stages = listOf(stage),
                tracer = tracer
            )

            pipeline.check("SSN: 123-45-6789", outputContext())

            verify { spanHandle.setAttribute("guard.result", "modified") }
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `빈 파이프라인은 스팬을 생성하지 않아야 한다`() = runTest {
            val pipeline = OutputGuardPipeline(
                stages = emptyList(),
                tracer = tracer
            )

            pipeline.check("content", outputContext())

            assertTrue(
                capturedSpanNames.isEmpty(),
                "빈 파이프라인에서는 스팬이 생성되지 않아야 한다"
            )
        }
    }

    // ─── Hook 스팬 테스트 ─────────────────────────────

    @Nested
    inner class HookSpan {

        @Test
        fun `BeforeStart 훅 실행 시 arc-hook-before_start 스팬을 생성해야 한다`() = runTest {
            val hook = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult =
                    HookResult.Continue
            }
            val executor = HookExecutor(
                beforeStartHooks = listOf(hook),
                tracer = tracer
            )

            executor.executeBeforeAgentStart(hookContext())

            assertTrue(
                capturedSpanNames.contains("arc.hook.before_start"),
                "arc.hook.before_start 스팬이 생성되어야 한다"
            )
            verify { spanHandle.setAttribute("hook.result", "continue") }
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `BeforeStart 훅 거부 시 rejected를 기록해야 한다`() = runTest {
            val hook = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult =
                    HookResult.Reject("Not allowed")
            }
            val executor = HookExecutor(
                beforeStartHooks = listOf(hook),
                tracer = tracer
            )

            executor.executeBeforeAgentStart(hookContext())

            verify { spanHandle.setAttribute("hook.result", "rejected") }
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `AfterComplete 훅 실행 시 arc-hook-after_complete 스팬을 생성해야 한다`() = runTest {
            val hook = object : AfterAgentCompleteHook {
                override val order = 100
                override suspend fun afterAgentComplete(
                    context: HookContext,
                    response: AgentResponse
                ) {
                    // 관찰만 수행
                }
            }
            val executor = HookExecutor(
                afterCompleteHooks = listOf(hook),
                tracer = tracer
            )

            executor.executeAfterAgentComplete(
                hookContext(),
                AgentResponse(success = true, response = "OK")
            )

            assertTrue(
                capturedSpanNames.contains("arc.hook.after_complete"),
                "arc.hook.after_complete 스팬이 생성되어야 한다"
            )
            verify { spanHandle.setAttribute("hook.result", "success") }
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `BeforeToolCall 훅 실행 시 arc-hook-before_tool_call 스팬을 생성해야 한다`() = runTest {
            val hook = object : BeforeToolCallHook {
                override val order = 1
                override suspend fun beforeToolCall(context: ToolCallContext): HookResult =
                    HookResult.Continue
            }
            val executor = HookExecutor(
                beforeToolCallHooks = listOf(hook),
                tracer = tracer
            )

            executor.executeBeforeToolCall(toolCallContext())

            assertTrue(
                capturedSpanNames.contains("arc.hook.before_tool_call"),
                "arc.hook.before_tool_call 스팬이 생성되어야 한다"
            )
        }

        @Test
        fun `AfterToolCall 훅 실행 시 arc-hook-after_tool_call 스팬을 생성해야 한다`() = runTest {
            val hook = object : AfterToolCallHook {
                override val order = 100
                override suspend fun afterToolCall(
                    context: ToolCallContext,
                    result: ToolCallResult
                ) {
                    // 관찰만 수행
                }
            }
            val executor = HookExecutor(
                afterToolCallHooks = listOf(hook),
                tracer = tracer
            )

            executor.executeAfterToolCall(
                toolCallContext(),
                ToolCallResult(success = true, output = "done")
            )

            assertTrue(
                capturedSpanNames.contains("arc.hook.after_tool_call"),
                "arc.hook.after_tool_call 스팬이 생성되어야 한다"
            )
            verify { spanHandle.setAttribute("hook.result", "success") }
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `훅이 없으면 스팬을 생성하지 않아야 한다`() = runTest {
            val executor = HookExecutor(tracer = tracer)

            executor.executeBeforeAgentStart(hookContext())
            executor.executeAfterAgentComplete(
                hookContext(),
                AgentResponse(success = true, response = "OK")
            )

            assertTrue(
                capturedSpanNames.isEmpty(),
                "빈 훅 목록에서는 스팬이 생성되지 않아야 한다"
            )
        }

        @Test
        fun `훅 수를 속성에 포함해야 한다`() = runTest {
            val hook1 = object : BeforeAgentStartHook {
                override val order = 1
                override suspend fun beforeAgentStart(context: HookContext): HookResult =
                    HookResult.Continue
            }
            val hook2 = object : BeforeAgentStartHook {
                override val order = 2
                override suspend fun beforeAgentStart(context: HookContext): HookResult =
                    HookResult.Continue
            }
            val executor = HookExecutor(
                beforeStartHooks = listOf(hook1, hook2),
                tracer = tracer
            )

            executor.executeBeforeAgentStart(hookContext())

            val attrs = capturedAttributes.firstOrNull {
                capturedSpanNames[capturedAttributes.indexOf(it)] == "arc.hook.before_start"
            }
            assertNotNull(attrs, "arc.hook.before_start 스팬의 속성이 존재해야 한다")
            assertEquals("2", attrs!!["hook.count"], "훅 수가 2여야 한다")
        }
    }

    // ─── Memory 스팬 테스트 ─────────────────────────────

    @Nested
    inner class MemorySpan {

        @Test
        fun `히스토리 로드 시 arc-memory-load 스팬을 생성해야 한다`() = runTest {
            val memoryStore = InMemoryMemoryStore()
            val manager = DefaultConversationManager(
                memoryStore = memoryStore,
                properties = AgentProperties(),
                tracer = tracer
            )

            val command = AgentCommand(
                systemPrompt = "You are helpful.",
                userPrompt = "Hello",
                metadata = mutableMapOf<String, Any>("sessionId" to "session-1")
            )
            manager.loadHistory(command)

            assertTrue(
                capturedSpanNames.contains("arc.memory.load"),
                "arc.memory.load 스팬이 생성되어야 한다"
            )
            val attrs = capturedAttributes.firstOrNull {
                capturedSpanNames[capturedAttributes.indexOf(it)] == "arc.memory.load"
            }
            assertEquals("session-1", attrs?.get("session.id"), "세션 ID가 포함되어야 한다")
            verify { spanHandle.setAttribute("memory.message.count", "0") }
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `히스토리 저장 시 arc-memory-save 스팬을 생성해야 한다`() = runTest {
            val memoryStore = InMemoryMemoryStore()
            val manager = DefaultConversationManager(
                memoryStore = memoryStore,
                properties = AgentProperties(),
                tracer = tracer
            )

            val command = AgentCommand(
                systemPrompt = "You are helpful.",
                userPrompt = "Hello",
                metadata = mutableMapOf<String, Any>("sessionId" to "session-2")
            )
            val result = AgentResult.success(content = "Hi there!")
            manager.saveHistory(command, result)

            assertTrue(
                capturedSpanNames.contains("arc.memory.save"),
                "arc.memory.save 스팬이 생성되어야 한다"
            )
            val attrs = capturedAttributes.firstOrNull {
                capturedSpanNames[capturedAttributes.indexOf(it)] == "arc.memory.save"
            }
            assertEquals("session-2", attrs?.get("session.id"), "세션 ID가 포함되어야 한다")
            assertEquals("2", attrs?.get("memory.message.count"), "메시지 수가 2여야 한다")
            verify(atLeast = 1) { spanHandle.close() }
        }

        @Test
        fun `세션 ID가 없으면 스팬을 생성하지 않아야 한다`() = runTest {
            val manager = DefaultConversationManager(
                memoryStore = InMemoryMemoryStore(),
                properties = AgentProperties(),
                tracer = tracer
            )

            val command = AgentCommand(
                systemPrompt = "You are helpful.",
                userPrompt = "Hello"
            )
            manager.loadHistory(command)

            assertTrue(
                capturedSpanNames.isEmpty(),
                "세션 ID 없이는 스팬이 생성되지 않아야 한다"
            )
        }

        @Test
        fun `conversationHistory 직접 제공 시 스팬을 생성하지 않아야 한다`() = runTest {
            val manager = DefaultConversationManager(
                memoryStore = InMemoryMemoryStore(),
                properties = AgentProperties(),
                tracer = tracer
            )
            val history = listOf(
                com.arc.reactor.agent.model.Message(
                    role = com.arc.reactor.agent.model.MessageRole.USER,
                    content = "Hi"
                )
            )

            val command = AgentCommand(
                systemPrompt = "You are helpful.",
                userPrompt = "Hello",
                conversationHistory = history
            )
            manager.loadHistory(command)

            assertTrue(
                capturedSpanNames.isEmpty(),
                "직접 제공된 히스토리에서는 메모리 스팬이 생성되지 않아야 한다"
            )
        }
    }

    // ─── 헬퍼 메서드 ─────────────────────────────

    private fun passingStage(name: String): GuardStage = object : GuardStage {
        override val stageName = name
        override val order = 1
        override suspend fun enforce(command: GuardCommand) = GuardResult.Allowed.DEFAULT
    }

    private fun rejectingStage(name: String, reason: String): GuardStage = object : GuardStage {
        override val stageName = name
        override val order = 1
        override suspend fun enforce(command: GuardCommand) = GuardResult.Rejected(
            reason = reason,
            category = RejectionCategory.PROMPT_INJECTION
        )
    }

    private fun throwingStage(name: String, error: Throwable): GuardStage = object : GuardStage {
        override val stageName = name
        override val order = 1
        override suspend fun enforce(command: GuardCommand): GuardResult = throw error
    }

    private fun allowingOutputStage(name: String): OutputGuardStage = object : OutputGuardStage {
        override val stageName = name
        override val order = 1
        override suspend fun check(content: String, context: OutputGuardContext) =
            OutputGuardResult.Allowed.DEFAULT
    }

    private fun rejectingOutputStage(name: String, reason: String): OutputGuardStage =
        object : OutputGuardStage {
            override val stageName = name
            override val order = 1
            override suspend fun check(content: String, context: OutputGuardContext) =
                OutputGuardResult.Rejected(
                    reason = reason,
                    category = OutputRejectionCategory.PII_DETECTED
                )
        }

    private fun modifyingOutputStage(name: String, reason: String): OutputGuardStage =
        object : OutputGuardStage {
            override val stageName = name
            override val order = 1
            override suspend fun check(content: String, context: OutputGuardContext) =
                OutputGuardResult.Modified(
                    content = "***masked***",
                    reason = reason
                )
        }

    private fun outputContext(): OutputGuardContext = OutputGuardContext(
        command = AgentCommand(systemPrompt = "sys", userPrompt = "test"),
        toolsUsed = emptyList(),
        durationMs = 0
    )

    private fun hookContext(): HookContext = HookContext(
        runId = "run-1",
        userId = "user-1",
        userPrompt = "Hello"
    )

    private fun toolCallContext(): ToolCallContext = ToolCallContext(
        agentContext = hookContext(),
        toolName = "myTool",
        toolParams = emptyMap(),
        callIndex = 0
    )
}
