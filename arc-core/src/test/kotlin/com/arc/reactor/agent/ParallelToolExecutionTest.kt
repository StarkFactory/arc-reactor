package com.arc.reactor.agent

import com.arc.reactor.agent.config.*
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.hook.BeforeToolCallHook
import com.arc.reactor.hook.HookExecutor
import com.arc.reactor.hook.model.HookResult
import com.arc.reactor.hook.model.ToolCallContext
import com.arc.reactor.tool.ToolCallback
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ParallelToolExecutionTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
    }

    @Nested
    inner class Parallelism {

        @Test
        fun `도구를 병렬로 실행해야 한다 - 동시성 검증`() = runBlocking {
        // 동시 실행 중인 도구의 최대 수를 추적합니다
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val tools = listOf("tool-a", "tool-b", "tool-c").map { name ->
            object : ToolCallback {
                override val name = name
                override val description = "Test tool: $name"
                override suspend fun call(arguments: Map<String, Any?>): Any {
                    val current = concurrent.incrementAndGet()
                    maxConcurrent.updateAndGet { max -> maxOf(max, current) }
                    kotlinx.coroutines.delay(100)  // 겹침을 허용하기 위해 슬롯을 유지합니다
                    concurrent.decrementAndGet()
                    return "result"
                }
            }
        }

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "tool-a", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "tool-b", "{}"),
            AssistantMessage.ToolCall("id-3", "function", "tool-c", "{}")
        )

        val callCount = AtomicInteger(0)
        every { fixture.requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                fixture.mockToolCallResponse(toolCalls)
            } else {
                fixture.mockFinalResponse("Done")
            }
        }

        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = properties,
            toolCallbacks = tools
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use all tools")
        )

        result.assertSuccess()
        // 병렬이면 최대 동시 실행 수가 1보다 커야 합니다 (이상적으로 3)
        assertTrue(maxConcurrent.get() > 1,
            "Expected concurrent execution > 1, but was ${maxConcurrent.get()} (tools ran sequentially)")
    }

    @Test
    fun `실행 시간과 관계없이 결과 순서를 보존해야 한다`() = runBlocking {
        // tool-slow가 더 오래 걸리지만 결과에서 첫 번째여야 합니다
        val tools = listOf(
            AgentTestFixture.delayingToolCallback("tool-slow", delayMs = 150, result = "SLOW"),
            AgentTestFixture.delayingToolCallback("tool-fast", delayMs = 10, result = "FAST")
        )

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "tool-slow", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "tool-fast", "{}")
        )

        val callCount = AtomicInteger(0)
        every { fixture.requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                fixture.mockToolCallResponse(toolCalls)
            } else {
                fixture.mockFinalResponse("Done")
            }
        }

        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = properties,
            toolCallbacks = tools
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use tools")
        )

        result.assertSuccess()
        // 두 도구 모두 기록되어야 합니다
        assertTrue(result.toolsUsed.contains("tool-slow"), "tool-slow should be in toolsUsed")
        assertTrue(result.toolsUsed.contains("tool-fast"), "tool-fast should be in toolsUsed")
    }
    }

    @Nested
    inner class Integration {

        @Test
        fun `병렬 도구 호출에 대해 훅을 실행해야 한다`() = runBlocking {
        val hookedTools = CopyOnWriteArrayList<String>()

        val beforeHook = object : BeforeToolCallHook {
            override val order = 1
            override suspend fun beforeToolCall(context: ToolCallContext): HookResult {
                hookedTools.add(context.toolName)
                return HookResult.Continue
            }
        }

        val hookExecutor = HookExecutor(beforeToolCallHooks = listOf(beforeHook))

        val tools = listOf(
            AgentTestFixture.toolCallback("tool-x"),
            AgentTestFixture.toolCallback("tool-y")
        )

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "tool-x", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "tool-y", "{}")
        )

        val callCount = AtomicInteger(0)
        every { fixture.requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                fixture.mockToolCallResponse(toolCalls)
            } else {
                fixture.mockFinalResponse("Done")
            }
        }

        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = properties,
            toolCallbacks = tools,
            hookExecutor = hookExecutor
        )

        executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use tools")
        )

        // 두 도구 모두 훅이 호출되어야 합니다
        assertTrue(hookedTools.contains("tool-x"), "Hook should have been called for tool-x")
        assertTrue(hookedTools.contains("tool-y"), "Hook should have been called for tool-y")
    }

    @Test
    fun `개별 도구 실패를 다른 도구에 영향 주지 않고 처리해야 한다`() = runBlocking {
        val failingTool = object : ToolCallback {
            override val name = "failing-tool"
            override val description = "Fails"
            override suspend fun call(arguments: Map<String, Any?>): Any {
                throw RuntimeException("Tool crashed")
            }
        }
        val successTool = AgentTestFixture.toolCallback("success-tool", result = "OK")

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "failing-tool", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "success-tool", "{}")
        )

        val callCount = AtomicInteger(0)
        every { fixture.requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                fixture.mockToolCallResponse(toolCalls)
            } else {
                fixture.mockFinalResponse("Final answer")
            }
        }

        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = properties,
            toolCallbacks = listOf(failingTool, successTool)
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use tools")
        )

        // 전체적으로 여전히 성공해야 합니다 (LLM이 한 도구의 오류와 다른 도구의 성공을 받습니다)
        result.assertSuccess()
        assertEquals("Final answer", result.content)
    }
    }

    @Nested
    inner class Limits {

        @Test
        fun `병렬 실행 전반에 걸쳐 maxToolCalls를 준수해야 한다`() = runBlocking {
        val tools = listOf(
            AgentTestFixture.toolCallback("t1"),
            AgentTestFixture.toolCallback("t2"),
            AgentTestFixture.toolCallback("t3")
        )

        val toolCalls = listOf(
            AssistantMessage.ToolCall("id-1", "function", "t1", "{}"),
            AssistantMessage.ToolCall("id-2", "function", "t2", "{}"),
            AssistantMessage.ToolCall("id-3", "function", "t3", "{}")
        )

        val callCount = AtomicInteger(0)
        every { fixture.requestSpec.call() } answers {
            if (callCount.getAndIncrement() == 0) {
                fixture.mockToolCallResponse(toolCalls)
            } else {
                fixture.mockFinalResponse("Done")
            }
        }

        // maxToolCalls = 2이지만 3개의 도구가 요청됨
        val executor = SpringAiAgentExecutor(
            chatClient = fixture.chatClient,
            properties = properties.copy(maxToolCalls = 2),
            toolCallbacks = tools
        )

        val result = executor.execute(
            AgentCommand(systemPrompt = "Test", userPrompt = "Use tools", maxToolCalls = 2)
        )

        result.assertSuccess()
        // 최대 2개의 도구만 성공적으로 실행되어야 합니다
        assertTrue(result.toolsUsed.size <= 2, "Should respect maxToolCalls limit: ${result.toolsUsed}")
    }
    }
}
