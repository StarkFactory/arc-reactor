package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.MediaConverter
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.TokenEstimator
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.prompt.ChatOptions
import java.util.concurrent.atomic.AtomicInteger

class ManualReActLoopExecutorTest {

    @Test
    fun `should return validated result when llm returns final text without tool calls`() = runBlocking {
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

        assertTrue(result.success)
        assertEquals("validated", result.content)
        assertEquals(listOf(false), optionsUsed)
    }

    @Test
    fun `should disable tools when maxToolCalls reached`() = runBlocking {
        val toolCall = AssistantMessage.ToolCall("tc-1", "call", "search", "{}")
        val firstResponse = AgentTestFixture.simpleChatResponse("").mutateWithToolCalls(listOf(toolCall))
        val secondResponse = AgentTestFixture.simpleChatResponse("done")

        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val callResponseSpec = mockk<ChatClient.CallResponseSpec>()
        io.mockk.every { requestSpec.call() } returns callResponseSpec
        io.mockk.every { callResponseSpec.chatResponse() } returnsMany listOf(firstResponse, secondResponse)

        val toolOrchestrator = mockk<ToolCallOrchestrator>()
        coEvery {
            toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any())
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

        assertTrue(result.success)
        assertEquals("done", result.content)
        assertTrue(optionsUsed.contains(true))
        assertTrue(optionsUsed.contains(false))
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
