package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.MediaConverter
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.TokenEstimator
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.prompt.ChatOptions
import java.util.concurrent.atomic.AtomicInteger

/**
 * ManualReActLoopExecutor에 대한 테스트.
 *
 * 수동 ReAct 루프 실행기의 동작을 검증합니다.
 */
class ManualReActLoopExecutorTest {

    @Test
    fun `llm returns final text without tool calls일 때 return validated result해야 한다`() = runBlocking {
        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val callResponseSpec = mockk<ChatClient.CallResponseSpec>()
        io.mockk.every { requestSpec.call() } returns callResponseSpec
        io.mockk.every { callResponseSpec.chatResponse() } returns AgentTestFixture.simpleChatResponse("hello")

        val toolOrchestrator = mockk<ToolCallOrchestrator>(relaxed = true)
        val optionsUsed = mutableListOf<Boolean>()
        val loopExecutor = ManualReActLoopExecutor(
            messageTrimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 10_000,
                outputReserveTokens = 100,
                tokenEstimator = TokenEstimator { it.length }
            ),
            toolCallOrchestrator = toolOrchestrator,
            buildRequestSpec = { _, _, _, _, _ -> requestSpec },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, hasTools ->
                optionsUsed.add(hasTools)
                ChatOptions.builder().build()
            },
            validateAndRepairResponse = { rawContent, _, _, _, _ ->
                assertEquals("hello", rawContent)
                AgentResult.success(content = "validated")
            },
            recordTokenUsage = { _, _ -> }
        )

        val result = loopExecutor.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi"),
            activeChatClient = mockk(relaxed = true),
            systemPrompt = "sys",
            initialTools = emptyList(),
            conversationHistory = emptyList(),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            allowedTools = null,
            maxToolCalls = 3
        )

        assertTrue(result.success, "Single-turn manual loop execution should succeed")
        assertEquals("validated", result.content)
        assertEquals(listOf(false), optionsUsed)
    }

    @Test
    fun `maxToolCalls reached일 때 disable tools해야 한다`() = runBlocking {
        val toolCall = AssistantMessage.ToolCall("tc-1", "call", "search", "{}")
        val firstResponse = AgentTestFixture.simpleChatResponse("").mutateWithToolCalls(listOf(toolCall))
        val secondResponse = AgentTestFixture.simpleChatResponse("done")

        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val callResponseSpec = mockk<ChatClient.CallResponseSpec>()
        io.mockk.every { requestSpec.call() } returns callResponseSpec
        io.mockk.every { callResponseSpec.chatResponse() } returnsMany listOf(firstResponse, secondResponse)

        val toolOrchestrator = mockk<ToolCallOrchestrator>()
        coEvery {
            toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            (it.invocation.args[4] as AtomicInteger).set(1)
            listOf(ToolResponseMessage.ToolResponse("tc-1", "search", "ok"))
        }

        val optionsUsed = mutableListOf<Boolean>()
        val loopExecutor = ManualReActLoopExecutor(
            messageTrimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 10_000,
                outputReserveTokens = 100,
                tokenEstimator = TokenEstimator { it.length }
            ),
            toolCallOrchestrator = toolOrchestrator,
            buildRequestSpec = { _, _, _, _, _ -> requestSpec },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, hasTools ->
                optionsUsed.add(hasTools)
                ChatOptions.builder().build()
            },
            validateAndRepairResponse = { rawContent, _, _, _, _ ->
                AgentResult.success(content = rawContent)
            },
            recordTokenUsage = { _, _ -> }
        )

        val result = loopExecutor.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", maxToolCalls = 1),
            activeChatClient = mockk(relaxed = true),
            systemPrompt = "sys",
            initialTools = listOf(mockk<Any>(relaxed = true)),
            conversationHistory = listOf(MediaConverter.buildUserMessage("history", emptyList())),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            allowedTools = null,
            maxToolCalls = 1
        )

        assertTrue(result.success, "ReAct loop should succeed after tool call and final response")
        assertEquals("done", result.content)
        assertTrue(optionsUsed.contains(true), "First iteration should use tools (tools enabled)")
        assertTrue(optionsUsed.contains(false), "Final iteration after maxToolCalls should disable tools")
    }

    @Test
    fun `maxToolCalls is zero일 때 start with tools disabled해야 한다`() = runBlocking {
        val toolCall = AssistantMessage.ToolCall("tc-1", "call", "search", "{}")
        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val callResponseSpec = mockk<ChatClient.CallResponseSpec>()
        io.mockk.every { requestSpec.call() } returns callResponseSpec
        io.mockk.every {
            callResponseSpec.chatResponse()
        } returns AgentTestFixture.simpleChatResponse("tool-free").mutateWithToolCalls(listOf(toolCall))

        val toolOrchestrator = mockk<ToolCallOrchestrator>()
        val optionsUsed = mutableListOf<Boolean>()
        val loopExecutor = ManualReActLoopExecutor(
            messageTrimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 10_000,
                outputReserveTokens = 100,
                tokenEstimator = TokenEstimator { it.length }
            ),
            toolCallOrchestrator = toolOrchestrator,
            buildRequestSpec = { _, _, _, _, _ -> requestSpec },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, hasTools ->
                optionsUsed.add(hasTools)
                ChatOptions.builder().build()
            },
            validateAndRepairResponse = { rawContent, _, _, _, _ -> AgentResult.success(content = rawContent) },
            recordTokenUsage = { _, _ -> }
        )

        val result = loopExecutor.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "hi", maxToolCalls = 0),
            activeChatClient = mockk(relaxed = true),
            systemPrompt = "sys",
            initialTools = listOf(mockk<Any>(relaxed = true)),
            conversationHistory = emptyList(),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi"),
            toolsUsed = mutableListOf(),
            allowedTools = null,
            maxToolCalls = 0
        )

        assertTrue(result.success, "Manual loop should still return the assistant response")
        assertEquals("tool-free", result.content)
        assertEquals(listOf(false), optionsUsed)
        coVerify(exactly = 0) {
            toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `tool execution fails일 때 not leave orphan AssistantMessage해야 한다`() = runBlocking {
        val toolCall = AssistantMessage.ToolCall("tc-1", "call", "search", "{}")
        val responseWithTools = AgentTestFixture.simpleChatResponse("")
            .mutateWithToolCalls(listOf(toolCall))

        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val callResponseSpec = mockk<ChatClient.CallResponseSpec>()
        io.mockk.every { requestSpec.call() } returns callResponseSpec
        io.mockk.every { callResponseSpec.chatResponse() } returns responseWithTools

        val toolOrchestrator = mockk<ToolCallOrchestrator>()
        coEvery {
            toolOrchestrator.executeInParallel(
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } throws RuntimeException("Tool execution failed")

        val capturedMessages = mutableListOf<List<String>>()
        val loopExecutor = ManualReActLoopExecutor(
            messageTrimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 10_000,
                outputReserveTokens = 100,
                tokenEstimator = TokenEstimator { it.length }
            ),
            toolCallOrchestrator = toolOrchestrator,
            buildRequestSpec = { _, _, msgs, _, _ ->
                capturedMessages.add(
                    msgs.map { it.javaClass.simpleName }
                )
                requestSpec
            },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, _ -> ChatOptions.builder().build() },
            validateAndRepairResponse = { _, _, _, _, _ ->
                AgentResult.success(content = "unused")
            },
            recordTokenUsage = { _, _ -> }
        )

        val exception = assertThrows<RuntimeException> {
            loopExecutor.execute(
                command = AgentCommand(
                    systemPrompt = "sys", userPrompt = "hi"
                ),
                activeChatClient = mockk(relaxed = true),
                systemPrompt = "sys",
                initialTools = listOf(mockk<Any>(relaxed = true)),
                conversationHistory = emptyList(),
                hookContext = HookContext(
                    runId = "run-1", userId = "u", userPrompt = "hi"
                ),
                toolsUsed = mutableListOf(),
                allowedTools = null,
                maxToolCalls = 3
            )
        }
        exception.message shouldBe "Tool execution failed"

        // that the messages list passed to buildRequestSpec never 확인
        // contained an orphan AssistantMessage without a ToolResponseMessage
        for (msgTypes in capturedMessages) {
            val assistantCount = msgTypes.count { it == "AssistantMessage" }
            val toolResponseCount = msgTypes.count {
                it == "ToolResponseMessage"
            }
            assertTrue(
                assistantCount == toolResponseCount,
                "AssistantMessage count ($assistantCount) must equal " +
                    "ToolResponseMessage count ($toolResponseCount) " +
                    "for pair integrity"
            )
        }
    }

    @Test
    fun `chatResponse has no results일 때 return empty content해야 한다`() =
        runBlocking {
            // ChatResponse with empty generations list -- assistantOutput
            // will be null, pendingToolCalls empty, returns via validation
            val chatResponse = org.springframework.ai.chat.model.ChatResponse(
                emptyList()
            )

            val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
            val callResponseSpec = mockk<ChatClient.CallResponseSpec>()
            io.mockk.every { requestSpec.call() } returns callResponseSpec
            io.mockk.every {
                callResponseSpec.chatResponse()
            } returns chatResponse

            val toolOrchestrator = mockk<ToolCallOrchestrator>(relaxed = true)
            val loopExecutor = ManualReActLoopExecutor(
                messageTrimmer = ConversationMessageTrimmer(
                    maxContextWindowTokens = 10_000,
                    outputReserveTokens = 100,
                    tokenEstimator = TokenEstimator { it.length }
                ),
                toolCallOrchestrator = toolOrchestrator,
                buildRequestSpec = { _, _, _, _, _ -> requestSpec },
                callWithRetry = { block -> block() },
                buildChatOptions = { _, _ -> ChatOptions.builder().build() },
                validateAndRepairResponse = { rawContent, _, _, _, _ ->
                    AgentResult.success(content = rawContent)
                },
                recordTokenUsage = { _, _ -> }
            )

            val result = loopExecutor.execute(
                command = AgentCommand(
                    systemPrompt = "sys", userPrompt = "hi"
                ),
                activeChatClient = mockk(relaxed = true),
                systemPrompt = "sys",
                initialTools = emptyList(),
                conversationHistory = emptyList(),
                hookContext = HookContext(
                    runId = "run-1", userId = "u", userPrompt = "hi"
                ),
                toolsUsed = mutableListOf(),
                allowedTools = null,
                maxToolCalls = 3
            )

            assertTrue(
                result.success,
                "Null assistant output with no active tools should " +
                    "gracefully return via validation"
            )
            assertEquals(
                "",
                result.content,
                "Content should be empty string when no results"
            )
        }

    @Test
    fun `tool error시 retry hint UserMessage가 주입되어야 한다`() = runBlocking {
        val toolCall = AssistantMessage.ToolCall("tc-1", "call", "jql_search", "{}")
        // 첫 번째 응답: tool call 포함 -> 도구 에러 반환
        val firstResponse = AgentTestFixture.simpleChatResponse("").mutateWithToolCalls(listOf(toolCall))
        // 두 번째 응답: tool call 포함 (재시도) -> 성공
        val retryToolCall = AssistantMessage.ToolCall("tc-2", "call", "jql_search", "{}")
        val secondResponse = AgentTestFixture.simpleChatResponse("").mutateWithToolCalls(listOf(retryToolCall))
        // 세 번째 응답: 최종 텍스트 답변
        val thirdResponse = AgentTestFixture.simpleChatResponse("final answer")

        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val callResponseSpec = mockk<ChatClient.CallResponseSpec>()
        io.mockk.every { requestSpec.call() } returns callResponseSpec
        io.mockk.every { callResponseSpec.chatResponse() } returnsMany
            listOf(firstResponse, secondResponse, thirdResponse)

        var toolCallCount = 0
        val toolOrchestrator = mockk<ToolCallOrchestrator>()
        coEvery {
            toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            toolCallCount++
            (it.invocation.args[4] as AtomicInteger).incrementAndGet()
            if (toolCallCount == 1) {
                // 첫 번째 호출: 에러 반환
                listOf(ToolResponseMessage.ToolResponse("tc-1", "jql_search", "Error: JQL syntax error"))
            } else {
                // 두 번째 호출: 성공
                listOf(ToolResponseMessage.ToolResponse("tc-2", "jql_search", "results: [...]"))
            }
        }

        val capturedMessages = mutableListOf<List<String>>()
        val loopExecutor = ManualReActLoopExecutor(
            messageTrimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 100_000,
                outputReserveTokens = 100,
                tokenEstimator = TokenEstimator { it.length }
            ),
            toolCallOrchestrator = toolOrchestrator,
            buildRequestSpec = { _, _, msgs, _, _ ->
                capturedMessages.add(msgs.map { it.javaClass.simpleName })
                requestSpec
            },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, _ -> ChatOptions.builder().build() },
            validateAndRepairResponse = { rawContent, _, _, _, _ ->
                AgentResult.success(content = rawContent)
            },
            recordTokenUsage = { _, _ -> }
        )

        val result = loopExecutor.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "search issues"),
            activeChatClient = mockk(relaxed = true),
            systemPrompt = "sys",
            initialTools = listOf(mockk<Any>(relaxed = true)),
            conversationHistory = emptyList(),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "search issues"),
            toolsUsed = mutableListOf(),
            allowedTools = null,
            maxToolCalls = 5
        )

        assertTrue(result.success, "Loop should complete successfully after tool error retry")
        assertEquals("final answer", result.content, "Should return the final text response")

        // 두 번째 LLM 호출 메시지에 UserMessage(retry hint)가 포함되어야 함
        assertTrue(
            capturedMessages.size >= 2,
            "Should have at least 2 LLM calls (initial + retry after error)"
        )
        val secondCallMessages = capturedMessages[1]
        assertTrue(
            secondCallMessages.count { it == "UserMessage" } >= 2,
            "Second LLM call should contain original UserMessage + retry hint UserMessage, " +
                "but got: $secondCallMessages"
        )

        // 세 번째 LLM 호출 — 성공 후 stale hint가 제거되어 원래 UserMessage 1개만 남아야 함
        if (capturedMessages.size >= 3) {
            val thirdCallMessages = capturedMessages[2]
            val userMsgCount = thirdCallMessages.count { it == "UserMessage" }
            assertEquals(
                1, userMsgCount,
                "Third LLM call should have exactly 1 UserMessage (original only), " +
                    "stale retry hint should be cleaned up after successful tool call, but got $userMsgCount"
            )
        }
    }

    @Test
    fun `tool success시 retry hint가 주입되지 않아야 한다`() = runBlocking {
        val toolCall = AssistantMessage.ToolCall("tc-1", "call", "search", "{}")
        val firstResponse = AgentTestFixture.simpleChatResponse("").mutateWithToolCalls(listOf(toolCall))
        val secondResponse = AgentTestFixture.simpleChatResponse("done")

        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val callResponseSpec = mockk<ChatClient.CallResponseSpec>()
        io.mockk.every { requestSpec.call() } returns callResponseSpec
        io.mockk.every { callResponseSpec.chatResponse() } returnsMany listOf(firstResponse, secondResponse)

        val toolOrchestrator = mockk<ToolCallOrchestrator>()
        coEvery {
            toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            (it.invocation.args[4] as AtomicInteger).incrementAndGet()
            listOf(ToolResponseMessage.ToolResponse("tc-1", "search", "success: results"))
        }

        val capturedMessages = mutableListOf<List<String>>()
        val loopExecutor = ManualReActLoopExecutor(
            messageTrimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 100_000,
                outputReserveTokens = 100,
                tokenEstimator = TokenEstimator { it.length }
            ),
            toolCallOrchestrator = toolOrchestrator,
            buildRequestSpec = { _, _, msgs, _, _ ->
                capturedMessages.add(msgs.map { it.javaClass.simpleName })
                requestSpec
            },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, _ -> ChatOptions.builder().build() },
            validateAndRepairResponse = { rawContent, _, _, _, _ ->
                AgentResult.success(content = rawContent)
            },
            recordTokenUsage = { _, _ -> }
        )

        val result = loopExecutor.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "search"),
            activeChatClient = mockk(relaxed = true),
            systemPrompt = "sys",
            initialTools = listOf(mockk<Any>(relaxed = true)),
            conversationHistory = emptyList(),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "search"),
            toolsUsed = mutableListOf(),
            allowedTools = null,
            maxToolCalls = 5
        )

        assertTrue(result.success, "Should succeed with successful tool call")
        assertEquals("done", result.content)

        // 두 번째 호출 시 UserMessage는 원래 것 1개만 있어야 함 (retry hint 없음)
        if (capturedMessages.size >= 2) {
            val secondCallMessages = capturedMessages[1]
            val userMsgCount = secondCallMessages.count { it == "UserMessage" }
            assertEquals(
                1, userMsgCount,
                "No retry hint should be injected after successful tool call, " +
                    "but got $userMsgCount UserMessages: $secondCallMessages"
            )
        }
    }

    @Test
    fun `injectToolErrorRetryHint unit test - error response면 hint를 추가해야 한다`() {
        val messages = mutableListOf<org.springframework.ai.chat.messages.Message>()
        val toolResponses = listOf(
            ToolResponseMessage.ToolResponse("tc-1", "search", "Error: JQL syntax error")
        )

        ReActLoopUtils.injectToolErrorRetryHint(toolResponses, messages)

        assertEquals(1, messages.size, "Should add exactly one retry hint message")
        assertTrue(
            messages[0] is org.springframework.ai.chat.messages.UserMessage,
            "Injected message should be a UserMessage"
        )
        assertEquals(
            ReActLoopUtils.TOOL_ERROR_RETRY_HINT,
            (messages[0] as org.springframework.ai.chat.messages.UserMessage).text,
            "Hint content should match"
        )
    }

    @Test
    fun `injectToolErrorRetryHint unit test - success response면 hint를 추가하지 않아야 한다`() {
        val messages = mutableListOf<org.springframework.ai.chat.messages.Message>()
        val toolResponses = listOf(
            ToolResponseMessage.ToolResponse("tc-1", "search", "results: [item1, item2]")
        )

        ReActLoopUtils.injectToolErrorRetryHint(toolResponses, messages)

        assertEquals(0, messages.size, "Should not add any message for successful tool response")
    }

    @Test
    fun `injectToolErrorRetryHint unit test - mixed responses에서 하나라도 error면 hint를 추가해야 한다`() {
        val messages = mutableListOf<org.springframework.ai.chat.messages.Message>()
        val toolResponses = listOf(
            ToolResponseMessage.ToolResponse("tc-1", "search", "results: ok"),
            ToolResponseMessage.ToolResponse("tc-2", "update", "Error: Permission denied")
        )

        ReActLoopUtils.injectToolErrorRetryHint(toolResponses, messages)

        assertEquals(1, messages.size, "Should add retry hint when any tool response has an error")
    }

    @Test
    fun `injectToolErrorRetryHint dedup - 연속 에러 호출 시 hint는 1개만 유지되어야 한다`() {
        val messages = mutableListOf<org.springframework.ai.chat.messages.Message>()
        val errorResponses = listOf(
            ToolResponseMessage.ToolResponse("tc-1", "search", "Error: failed")
        )

        ReActLoopUtils.injectToolErrorRetryHint(errorResponses, messages)
        assertEquals(1, messages.size, "First call should add one hint")

        ReActLoopUtils.injectToolErrorRetryHint(errorResponses, messages)
        assertEquals(1, messages.size, "Second call should still have only one hint (dedup)")
    }

    @Test
    fun `injectToolErrorRetryHint cleanup - 성공 응답 시 이전 hint가 제거되어야 한다`() {
        val messages = mutableListOf<org.springframework.ai.chat.messages.Message>()
        val errorResponses = listOf(
            ToolResponseMessage.ToolResponse("tc-1", "search", "Error: failed")
        )
        val successResponses = listOf(
            ToolResponseMessage.ToolResponse("tc-2", "search", "results: ok")
        )

        ReActLoopUtils.injectToolErrorRetryHint(errorResponses, messages)
        assertEquals(1, messages.size, "Error should add hint")

        ReActLoopUtils.injectToolErrorRetryHint(successResponses, messages)
        assertEquals(0, messages.size, "Success should clean up stale hint")
    }

    @Test
    fun `hasToolError - 에러 응답 포함 시 true를 반환해야 한다`() {
        val errorResponses = listOf(
            ToolResponseMessage.ToolResponse("tc-1", "search", "Error: JQL syntax error")
        )
        assertTrue(
            ReActLoopUtils.hasToolError(errorResponses),
            "Should detect tool error response"
        )
    }

    @Test
    fun `hasToolError - 성공 응답만 있을 때 false를 반환해야 한다`() {
        val successResponses = listOf(
            ToolResponseMessage.ToolResponse("tc-1", "search", "results: ok")
        )
        assertFalse(
            ReActLoopUtils.hasToolError(successResponses),
            "Should not detect error in successful responses"
        )
    }

    @Test
    fun `injectForceRetryHintIfNeeded - 재시도 한도 미만일 때 SystemMessage를 주입해야 한다`() {
        val messages = mutableListOf<org.springframework.ai.chat.messages.Message>()

        val shouldContinue = ReActLoopUtils.injectForceRetryHintIfNeeded(messages, 0)

        assertTrue(shouldContinue, "Should return true to continue loop")
        assertEquals(1, messages.size, "Should add one force-retry hint")
        assertTrue(
            messages[0] is org.springframework.ai.chat.messages.SystemMessage,
            "Force-retry hint should be a SystemMessage for higher LLM compliance"
        )
    }

    @Test
    fun `injectForceRetryHintIfNeeded - 재시도 한도 도달 시 false를 반환해야 한다`() {
        val messages = mutableListOf<org.springframework.ai.chat.messages.Message>()

        val shouldContinue = ReActLoopUtils.injectForceRetryHintIfNeeded(
            messages, ReActLoopUtils.MAX_TEXT_RETRIES_AFTER_TOOL_ERROR
        )

        assertFalse(shouldContinue, "Should return false when retry limit reached")
        assertEquals(0, messages.size, "Should not add any message when limit reached")
    }

    @Test
    fun `tool error 후 text 응답 시 force retry로 루프가 계속되어야 한다`() = runBlocking {
        val toolCall = AssistantMessage.ToolCall("tc-1", "call", "jql_search", "{}")
        // 1차: tool call → 에러
        val firstResponse = AgentTestFixture.simpleChatResponse("").mutateWithToolCalls(listOf(toolCall))
        // 2차: 텍스트만 응답 (LLM이 힌트 무시) → force retry 발동
        val textOnlyResponse = AgentTestFixture.simpleChatResponse("재시도하겠습니다")
        // 3차: force retry 후 tool call 생성
        val retryToolCall = AssistantMessage.ToolCall("tc-2", "call", "jql_search", "{}")
        val retryResponse = AgentTestFixture.simpleChatResponse("").mutateWithToolCalls(listOf(retryToolCall))
        // 4차: 최종 답변
        val finalResponse = AgentTestFixture.simpleChatResponse("검색 결과입니다")

        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val callResponseSpec = mockk<ChatClient.CallResponseSpec>()
        io.mockk.every { requestSpec.call() } returns callResponseSpec
        io.mockk.every { callResponseSpec.chatResponse() } returnsMany
            listOf(firstResponse, textOnlyResponse, retryResponse, finalResponse)

        var toolCallCount = 0
        val toolOrchestrator = mockk<ToolCallOrchestrator>()
        coEvery {
            toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            toolCallCount++
            (it.invocation.args[4] as AtomicInteger).incrementAndGet()
            if (toolCallCount == 1) {
                listOf(ToolResponseMessage.ToolResponse("tc-1", "jql_search", "Error: invalid field"))
            } else {
                listOf(ToolResponseMessage.ToolResponse("tc-2", "jql_search", "results: [...]"))
            }
        }

        val capturedMessages = mutableListOf<List<String>>()
        val loopExecutor = ManualReActLoopExecutor(
            messageTrimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 100_000,
                outputReserveTokens = 100,
                tokenEstimator = TokenEstimator { it.length }
            ),
            toolCallOrchestrator = toolOrchestrator,
            buildRequestSpec = { _, _, msgs, _, _ ->
                capturedMessages.add(msgs.map { it.javaClass.simpleName })
                requestSpec
            },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, _ -> ChatOptions.builder().build() },
            validateAndRepairResponse = { rawContent, _, _, _, _ ->
                AgentResult.success(content = rawContent)
            },
            recordTokenUsage = { _, _ -> }
        )

        val result = loopExecutor.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "이슈 검색"),
            activeChatClient = mockk(relaxed = true),
            systemPrompt = "sys",
            initialTools = listOf(mockk<Any>(relaxed = true)),
            conversationHistory = emptyList(),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "이슈 검색"),
            toolsUsed = mutableListOf(),
            allowedTools = null,
            maxToolCalls = 5
        )

        assertTrue(result.success, "Loop should succeed after force retry")
        assertEquals("검색 결과입니다", result.content, "Should return the final answer after successful retry")
        assertTrue(
            capturedMessages.size >= 3,
            "Should have at least 3 LLM calls (initial + text retry + retry tool call)"
        )
        // 3번째 호출에 SystemMessage(force-retry hint)가 포함되어야 함
        val thirdCallMessages = capturedMessages[2]
        assertTrue(
            thirdCallMessages.contains("SystemMessage"),
            "Third LLM call should contain SystemMessage force-retry hint, but got: $thirdCallMessages"
        )
    }

    @Test
    fun `text retry 한도 도달 시 텍스트 응답으로 종료해야 한다`() = runBlocking {
        val toolCall = AssistantMessage.ToolCall("tc-1", "call", "jql_search", "{}")
        // 1차: tool call → 에러
        val firstResponse = AgentTestFixture.simpleChatResponse("").mutateWithToolCalls(listOf(toolCall))
        // 2차~3차+: 계속 텍스트만 응답 (MAX_TEXT_RETRIES_AFTER_TOOL_ERROR 횟수만큼)
        val textResponses = (1..ReActLoopUtils.MAX_TEXT_RETRIES_AFTER_TOOL_ERROR + 1).map {
            AgentTestFixture.simpleChatResponse("재시도 실패 $it")
        }

        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val callResponseSpec = mockk<ChatClient.CallResponseSpec>()
        io.mockk.every { requestSpec.call() } returns callResponseSpec
        io.mockk.every { callResponseSpec.chatResponse() } returnsMany (listOf(firstResponse) + textResponses)

        val toolOrchestrator = mockk<ToolCallOrchestrator>()
        coEvery {
            toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            (it.invocation.args[4] as AtomicInteger).incrementAndGet()
            listOf(ToolResponseMessage.ToolResponse("tc-1", "jql_search", "Error: failed"))
        }

        val loopExecutor = ManualReActLoopExecutor(
            messageTrimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 100_000,
                outputReserveTokens = 100,
                tokenEstimator = TokenEstimator { it.length }
            ),
            toolCallOrchestrator = toolOrchestrator,
            buildRequestSpec = { _, _, _, _, _ -> requestSpec },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, _ -> ChatOptions.builder().build() },
            validateAndRepairResponse = { rawContent, _, _, _, _ ->
                AgentResult.success(content = rawContent)
            },
            recordTokenUsage = { _, _ -> }
        )

        val result = loopExecutor.execute(
            command = AgentCommand(systemPrompt = "sys", userPrompt = "이슈 검색"),
            activeChatClient = mockk(relaxed = true),
            systemPrompt = "sys",
            initialTools = listOf(mockk<Any>(relaxed = true)),
            conversationHistory = emptyList(),
            hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "이슈 검색"),
            toolsUsed = mutableListOf(),
            allowedTools = null,
            maxToolCalls = 5
        )

        assertTrue(result.success, "Loop should eventually terminate with text response")
        assertTrue(
            result.content.orEmpty().contains("재시도 실패"),
            "Should return the last text response after retry limit reached"
        )
    }

    private fun org.springframework.ai.chat.model.ChatResponse.mutateWithToolCalls(
        toolCalls: List<AssistantMessage.ToolCall>
    ): org.springframework.ai.chat.model.ChatResponse {
        val output = this.results.firstOrNull()?.output ?: AssistantMessage("")
        val newOutput = AssistantMessage.builder()
            .content(output.text.orEmpty())
            .toolCalls(toolCalls)
            .build()
        return org.springframework.ai.chat.model.ChatResponse(
            listOf(org.springframework.ai.chat.model.Generation(newOutput)),
            this.metadata
        )
    }
}
