package com.arc.reactor.hook

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * HookExecutor의 After 훅 커버리지 공백을 보강하는 테스트.
 *
 * 기존 HookExecutorTest/HookEdgeCaseTest에서 다루지 않는 영역:
 * - AfterAgentComplete 훅 실행 순서 보장
 * - AfterToolCall 훅 실행 순서 보장
 * - After 훅에서 failOnError=false 시 다음 훅이 계속 실행됨
 * - 비활성(enabled=false) After 훅은 건너뜀
 * - Before 훅 없을 때 executeBeforeToolCall이 Continue 반환
 * - CancellationException이 AfterAgentComplete에서 전파됨
 */
class HookExecutorAfterHooksGapTest {

    private fun makeContext() = HookContext(
        runId = "run-test",
        userId = "user-test",
        userPrompt = "테스트 질문"
    )

    private fun makeToolContext(toolName: String = "test-tool") = ToolCallContext(
        agentContext = makeContext(),
        toolName = toolName,
        toolParams = emptyMap(),
        callIndex = 0
    )

    // ─────────────────────────────────────────────────────────────────────
    // AfterAgentComplete 훅 순서 보장
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class AfterAgentCompleteOrdering {

        @Test
        fun `AfterAgentComplete 훅은 order 오름차순으로 실행된다`() = runTest {
            val executionOrder = mutableListOf<Int>()

            val hook3 = object : AfterAgentCompleteHook {
                override val order = 3
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    executionOrder.add(3)
                }
            }
            val hook1 = object : AfterAgentCompleteHook {
                override val order = 1
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    executionOrder.add(1)
                }
            }
            val hook2 = object : AfterAgentCompleteHook {
                override val order = 2
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    executionOrder.add(2)
                }
            }

            // 역순으로 등록 — 순서가 정렬되는지 검증
            val executor = HookExecutor(afterCompleteHooks = listOf(hook3, hook1, hook2))

            executor.executeAfterAgentComplete(
                makeContext(),
                AgentResponse(success = true, response = "완료")
            )

            assertEquals(listOf(1, 2, 3), executionOrder) {
                "AfterAgentComplete 훅은 order 오름차순(1→2→3)으로 실행되어야 한다"
            }
        }

        @Test
        fun `AfterAgentComplete 훅 중 하나 실패해도 나머지가 계속 실행된다 (fail-open)`() = runTest {
            val executed = mutableListOf<Int>()

            val failingHook = object : AfterAgentCompleteHook {
                override val order = 1
                override val failOnError = false
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    executed.add(1)
                    throw RuntimeException("관찰 훅 실패")
                }
            }
            val successHook = object : AfterAgentCompleteHook {
                override val order = 2
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    executed.add(2)
                }
            }

            val executor = HookExecutor(afterCompleteHooks = listOf(failingHook, successHook))

            executor.executeAfterAgentComplete(
                makeContext(),
                AgentResponse(success = true, response = "완료")
            )

            assertEquals(listOf(1, 2), executed) {
                "failOnError=false인 AfterAgentComplete 훅 실패 후 다음 훅(2)이 실행되어야 한다"
            }
        }

        @Test
        fun `비활성화된 AfterAgentComplete 훅은 건너뛴다`() = runTest {
            val executed = mutableListOf<Int>()

            val disabledHook = object : AfterAgentCompleteHook {
                override val order = 1
                override val enabled = false
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    executed.add(1) // 절대 실행되면 안 됨
                }
            }
            val enabledHook = object : AfterAgentCompleteHook {
                override val order = 2
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    executed.add(2)
                }
            }

            val executor = HookExecutor(afterCompleteHooks = listOf(disabledHook, enabledHook))

            executor.executeAfterAgentComplete(
                makeContext(),
                AgentResponse(success = true, response = "완료")
            )

            assertEquals(listOf(2), executed) {
                "disabled(enabled=false) AfterAgentComplete 훅은 건너뛰어야 한다"
            }
        }

        @Test
        fun `AfterAgentComplete 훅이 없을 때 예외 없이 완료된다`() = runTest {
            val executor = HookExecutor() // 아무 훅도 없음

            var threw = false
            try {
                executor.executeAfterAgentComplete(
                    makeContext(),
                    AgentResponse(success = true, response = "완료")
                )
            } catch (_: Exception) {
                threw = true
            }

            assertTrue(!threw) {
                "AfterAgentComplete 훅이 없을 때 예외 없이 완료되어야 한다"
            }
        }

        @Test
        fun `AfterAgentComplete 훅에서 CancellationException은 항상 전파된다`() = runTest {
            val cancelHook = object : AfterAgentCompleteHook {
                override val order = 1
                override val failOnError = false // fail-open이어도 취소는 전파
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    throw CancellationException("코루틴 취소")
                }
            }

            val executor = HookExecutor(afterCompleteHooks = listOf(cancelHook))

            var cancellationPropagated = false
            try {
                executor.executeAfterAgentComplete(
                    makeContext(),
                    AgentResponse(success = true, response = "완료")
                )
            } catch (_: CancellationException) {
                cancellationPropagated = true
            }

            assertTrue(cancellationPropagated) {
                "AfterAgentComplete 훅의 CancellationException은 failOnError=false여도 전파되어야 한다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // AfterToolCall 훅 순서 보장
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class AfterToolCallOrdering {

        @Test
        fun `AfterToolCall 훅은 order 오름차순으로 실행된다`() = runTest {
            val executionOrder = mutableListOf<Int>()

            val hook2 = object : AfterToolCallHook {
                override val order = 2
                override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
                    executionOrder.add(2)
                }
            }
            val hook1 = object : AfterToolCallHook {
                override val order = 1
                override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
                    executionOrder.add(1)
                }
            }

            val executor = HookExecutor(afterToolCallHooks = listOf(hook2, hook1))

            executor.executeAfterToolCall(
                makeToolContext("db_query"),
                ToolCallResult(success = true, output = "결과 5개")
            )

            assertEquals(listOf(1, 2), executionOrder) {
                "AfterToolCall 훅은 order 오름차순(1→2)으로 실행되어야 한다"
            }
        }

        @Test
        fun `비활성화된 AfterToolCall 훅은 건너뛴다`() = runTest {
            val executed = mutableListOf<String>()

            val disabledHook = object : AfterToolCallHook {
                override val order = 1
                override val enabled = false
                override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
                    executed.add("disabled") // 절대 실행되면 안 됨
                }
            }
            val enabledHook = object : AfterToolCallHook {
                override val order = 2
                override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
                    executed.add("enabled")
                }
            }

            val executor = HookExecutor(afterToolCallHooks = listOf(disabledHook, enabledHook))
            executor.executeAfterToolCall(makeToolContext(), ToolCallResult(success = true))

            assertEquals(listOf("enabled"), executed) {
                "disabled AfterToolCall 훅은 실행되지 않아야 한다"
            }
        }

        @Test
        fun `AfterToolCall 훅이 없을 때 예외 없이 완료된다`() = runTest {
            val executor = HookExecutor() // 아무 훅도 없음

            var threw = false
            try {
                executor.executeAfterToolCall(makeToolContext(), ToolCallResult(success = true))
            } catch (_: Exception) {
                threw = true
            }

            assertTrue(!threw) {
                "AfterToolCall 훅이 없을 때 예외 없이 완료되어야 한다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Before 훅 실행 시맨틱 — 보완
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class BeforeHookSemantics {

        @Test
        fun `BeforeToolCall 훅 없을 때 Continue를 반환한다`() = runTest {
            val executor = HookExecutor()

            val result = executor.executeBeforeToolCall(makeToolContext())

            assertInstanceOf(HookResult.Continue::class.java, result) {
                "BeforeToolCall 훅이 없으면 Continue를 반환해야 한다"
            }
        }

        @Test
        fun `BeforeAgentStart 훅 없을 때 Continue를 반환한다`() = runTest {
            val executor = HookExecutor()

            val result = executor.executeBeforeAgentStart(makeContext())

            assertInstanceOf(HookResult.Continue::class.java, result) {
                "BeforeAgentStart 훅이 없으면 Continue를 반환해야 한다"
            }
        }

        @Test
        fun `BeforeToolCall에서 Reject하면 이유가 보존된다`() = runTest {
            val rejectReason = "도구 호출 권한 없음"
            val hook = object : BeforeToolCallHook {
                override val order = 1
                override suspend fun beforeToolCall(context: ToolCallContext): HookResult {
                    return HookResult.Reject(rejectReason)
                }
            }

            val executor = HookExecutor(beforeToolCallHooks = listOf(hook))
            val result = executor.executeBeforeToolCall(makeToolContext())

            val reject = assertInstanceOf(HookResult.Reject::class.java, result) {
                "BeforeToolCall에서 Reject 반환 시 Reject 결과여야 한다"
            }
            assertEquals(rejectReason, reject.reason) {
                "Reject 사유가 그대로 보존되어야 한다"
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 훅 컨텍스트 불변성 — 여러 훅이 같은 컨텍스트를 공유해도 안전
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class HookContextSharing {

        @Test
        fun `여러 AfterAgentComplete 훅이 같은 AgentResponse를 수신한다`() = runTest {
            val capturedResponses = mutableListOf<AgentResponse>()

            val response = AgentResponse(
                success = true,
                response = "결과 내용",
                toolsUsed = listOf("search", "calculator")
            )

            val hook1 = object : AfterAgentCompleteHook {
                override val order = 1
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    capturedResponses.add(response)
                }
            }
            val hook2 = object : AfterAgentCompleteHook {
                override val order = 2
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    capturedResponses.add(response)
                }
            }

            val executor = HookExecutor(afterCompleteHooks = listOf(hook1, hook2))
            executor.executeAfterAgentComplete(makeContext(), response)

            assertEquals(2, capturedResponses.size) {
                "두 훅 모두 AgentResponse를 수신해야 한다"
            }
            assertTrue(capturedResponses.all { it.response == "결과 내용" }) {
                "두 훅 모두 동일한 AgentResponse를 수신해야 한다"
            }
            assertTrue(capturedResponses.all { it.toolsUsed == listOf("search", "calculator") }) {
                "두 훅 모두 동일한 toolsUsed 목록을 수신해야 한다"
            }
        }
    }
}
