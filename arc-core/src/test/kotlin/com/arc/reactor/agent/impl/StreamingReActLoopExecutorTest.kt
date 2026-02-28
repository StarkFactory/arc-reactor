package com.arc.reactor.agent.impl

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.MediaConverter
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.TokenEstimator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.prompt.ChatOptions
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger

class StreamingReActLoopExecutorTest {

    @Test
    fun `should stream final text when llm returns no tool calls`() = runBlocking {
        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val streamResponseSpec = mockk<ChatClient.StreamResponseSpec>()
        every { requestSpec.stream() } returns streamResponseSpec
        every { streamResponseSpec.chatResponse() } returns Flux.just(AgentTestFixture.textChunk("hello"))

        val optionsUsed = mutableListOf<Boolean>()
        val emitted = mutableListOf<String>()
        val loopExecutor = StreamingReActLoopExecutor(
            messageTrimmer = ConversationMessageTrimmer(
                maxContextWindowTokens = 10_000,
                outputReserveTokens = 100,
                tokenEstimator = TokenEstimator { it.length }
            ),
            toolCallOrchestrator = mockk(relaxed = true),
            buildRequestSpec = { _, _, _, _, _ -> requestSpec },
            callWithRetry = { block -> block() },
            buildChatOptions = { _, hasTools ->
                optionsUsed.add(hasTools)
                ChatOptions.builder().build()
            }
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
            maxToolCalls = 3,
            emit = { emitted.add(it) }
        )

        assertTrue(result.success, "Single-turn streaming execution should succeed")
        assertEquals("hello", result.collectedContent)
        assertEquals("hello", result.lastIterationContent)
        assertEquals(listOf("hello"), emitted)
        assertEquals(listOf(false), optionsUsed)
    }

    @Test
    fun `should disable tools when maxToolCalls reached`() = runBlocking {
        val toolCall = AssistantMessage.ToolCall("tc-1", "call", "search", "{}")
        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val streamResponseSpec = mockk<ChatClient.StreamResponseSpec>()
        every { requestSpec.stream() } returns streamResponseSpec
        every { streamResponseSpec.chatResponse() } returnsMany listOf(
            Flux.just(AgentTestFixture.toolCallChunk(listOf(toolCall), "thinking")),
            Flux.just(AgentTestFixture.textChunk("done"))
        )

        val toolOrchestrator = mockk<ToolCallOrchestrator>()
        coEvery {
            toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            (it.invocation.args[4] as AtomicInteger).set(1)
            listOf(ToolResponseMessage.ToolResponse("tc-1", "search", "ok"))
        }

        val optionsUsed = mutableListOf<Boolean>()
        val emitted = mutableListOf<String>()
        val loopExecutor = StreamingReActLoopExecutor(
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
            }
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
            maxToolCalls = 1,
            emit = { emitted.add(it) }
        )

        assertTrue(result.success, "ReAct loop should succeed after tool call and final response")
        assertEquals("thinkingdone", result.collectedContent)
        assertEquals("done", result.lastIterationContent)
        assertTrue(optionsUsed.contains(true), "First iteration should use tools (tools enabled)")
        assertTrue(optionsUsed.contains(false), "Final iteration after maxToolCalls should disable tools")
        assertTrue(emitted.contains(StreamEventMarker.toolStart("search")), "toolStart marker for 'search' should be emitted")
        assertTrue(emitted.contains(StreamEventMarker.toolEnd("search")), "toolEnd marker for 'search' should be emitted")
        coVerify(exactly = 1) {
            toolOrchestrator.executeInParallel(any(), any(), any(), any(), any(), any(), any())
        }
    }
}
