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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.prompt.ChatOptions
import java.util.concurrent.atomic.AtomicInteger

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
