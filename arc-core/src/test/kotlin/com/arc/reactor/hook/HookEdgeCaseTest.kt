package com.arc.reactor.hook

import com.arc.reactor.hook.model.AgentResponse
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.hook.model.ToolCallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HookEdgeCaseTest {

    private fun createContext() = HookContext(
        runId = "run-1",
        userId = "user-1",
        userPrompt = "test"
    )

    @Nested
    inner class DisabledHooks {

        @Test
        fun `disabled hooks are skipped`() = runBlocking {
            val executionOrder = mutableListOf<Int>()

            val enabledHook = object : BeforeAgentStartHook {
                override val order = 1
                override val enabled = true
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    executionOrder.add(1)
                    return HookResult.Continue
                }
            }
            val disabledHook = object : BeforeAgentStartHook {
                override val order = 2
                override val enabled = false
                override suspend fun beforeAgentStart(context: HookContext): HookResult {
                    executionOrder.add(2)
                    return HookResult.Reject("Should not reach here")
                }
            }

            val executor = HookExecutor(beforeStartHooks = listOf(enabledHook, disabledHook))
            val result = executor.executeBeforeAgentStart(createContext())

            assertInstanceOf(HookResult.Continue::class.java, result)
            assertEquals(listOf(1), executionOrder, "Only enabled hook should run")
        }
    }

    @Nested
    inner class EmptyHookRegistration {

        @Test
        fun `returns Continue when no beforeStart hooks registered`() = runBlocking {
            val executor = HookExecutor()
            val result = executor.executeBeforeAgentStart(createContext())
            assertInstanceOf(HookResult.Continue::class.java, result)
        }

        @Test
        fun `returns Continue when no tool call hooks registered`() = runBlocking {
            val executor = HookExecutor()
            val result = executor.executeBeforeToolCall(ToolCallContext(
                agentContext = createContext(),
                toolName = "test",
                toolParams = emptyMap(),
                callIndex = 0
            ))
            assertInstanceOf(HookResult.Continue::class.java, result)
        }
    }

    @Nested
    inner class FailOnErrorBehavior {

        @Test
        fun `afterToolCall propagates exception when failOnError is true`() {
            val failingHook = object : AfterToolCallHook {
                override val order = 1
                override val failOnError = true
                override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
                    throw RuntimeException("Critical failure")
                }
            }

            val executor = HookExecutor(afterToolCallHooks = listOf(failingHook))
            val toolContext = ToolCallContext(
                agentContext = createContext(),
                toolName = "test",
                toolParams = emptyMap(),
                callIndex = 0
            )

            assertThrows<RuntimeException> {
                runBlocking {
                    executor.executeAfterToolCall(toolContext, ToolCallResult(success = true))
                }
            }
        }

        @Test
        fun `afterToolCall continues when failOnError is false`() = runBlocking {
            val executed = mutableListOf<String>()

            val failingHook = object : AfterToolCallHook {
                override val order = 1
                override val failOnError = false
                override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
                    executed.add("failing")
                    throw RuntimeException("Non-critical failure")
                }
            }
            val successHook = object : AfterToolCallHook {
                override val order = 2
                override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
                    executed.add("success")
                }
            }

            val executor = HookExecutor(afterToolCallHooks = listOf(failingHook, successHook))
            val toolContext = ToolCallContext(
                agentContext = createContext(),
                toolName = "test",
                toolParams = emptyMap(),
                callIndex = 0
            )

            executor.executeAfterToolCall(toolContext, ToolCallResult(success = true))
            assertEquals(listOf("failing", "success"), executed)
        }

        @Test
        fun `afterAgentComplete propagates exception when failOnError is true`() {
            val failingHook = object : AfterAgentCompleteHook {
                override val order = 1
                override val failOnError = true
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    throw RuntimeException("Critical audit failure")
                }
            }

            val executor = HookExecutor(afterCompleteHooks = listOf(failingHook))

            assertThrows<RuntimeException> {
                runBlocking {
                    executor.executeAfterAgentComplete(
                        createContext(),
                        AgentResponse(success = true, response = "done")
                    )
                }
            }
        }

        @Test
        fun `afterToolCall rethrows cancellation even when failOnError is false`() {
            val cancellingHook = object : AfterToolCallHook {
                override val order = 1
                override val failOnError = false
                override suspend fun afterToolCall(context: ToolCallContext, result: ToolCallResult) {
                    throw CancellationException("cancel tool hook")
                }
            }

            val executor = HookExecutor(afterToolCallHooks = listOf(cancellingHook))
            val thrown = assertThrows<CancellationException>(
                "CancellationException must propagate from afterToolCall hooks"
            ) {
                runBlocking {
                    executor.executeAfterToolCall(
                        ToolCallContext(
                            agentContext = createContext(),
                            toolName = "test-tool",
                            toolParams = emptyMap(),
                            callIndex = 0
                        ),
                        ToolCallResult(success = true)
                    )
                }
            }
            assertEquals("cancel tool hook", thrown.message) {
                "CancellationException message should be preserved"
            }
        }

        @Test
        fun `afterAgentComplete rethrows cancellation even when failOnError is false`() {
            val cancellingHook = object : AfterAgentCompleteHook {
                override val order = 1
                override val failOnError = false
                override suspend fun afterAgentComplete(context: HookContext, response: AgentResponse) {
                    throw CancellationException("cancel completion hook")
                }
            }

            val executor = HookExecutor(afterCompleteHooks = listOf(cancellingHook))
            val thrown = assertThrows<CancellationException>(
                "CancellationException must propagate from afterAgentComplete hooks"
            ) {
                runBlocking {
                    executor.executeAfterAgentComplete(
                        createContext(),
                        AgentResponse(success = true, response = "done")
                    )
                }
            }
            assertEquals("cancel completion hook", thrown.message) {
                "CancellationException message should be preserved"
            }
        }
    }

    @Nested
    inner class ThreadSafety {

        @Test
        fun `HookContext toolsUsed is thread-safe`() {
            val context = createContext()

            val threads = (1..10).map { i ->
                Thread {
                    repeat(10) { j ->
                        context.toolsUsed.add("tool-$i-$j")
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertEquals(100, context.toolsUsed.size,
                "All 100 tool entries should be present after concurrent writes")
        }

        @Test
        fun `HookContext metadata is thread-safe`() {
            val context = createContext()

            val threads = (1..10).map { i ->
                Thread {
                    repeat(10) { j ->
                        context.metadata["key-$i-$j"] = "value-$i-$j"
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertEquals(100, context.metadata.size,
                "All 100 metadata entries should be present after concurrent writes")
        }
    }

    @Nested
    inner class HookContextProperties {

        @Test
        fun `durationMs returns non-negative value`() {
            val context = createContext()
            assertTrue(context.durationMs() >= 0) { "durationMs should be non-negative, got: ${context.durationMs()}" }
        }
    }
}
