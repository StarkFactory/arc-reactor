package com.arc.reactor.agent.impl

import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentMode
import com.arc.reactor.agent.model.DefaultErrorMessageResolver
import com.arc.reactor.hook.model.HookContext
import com.arc.reactor.memory.ConversationManager
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.Message

/**
 * StreamingExecutionCoordinator에 대한 테스트.
 *
 * 스트리밍 실행 조정 로직을 검증합니다.
 */
class StreamingExecutionCoordinatorTest {

    @Test
    fun `capture streaming stage timings and preserve channel tags해야 한다`() = runBlocking {
        val metrics = mockk<AgentMetrics>(relaxed = true)
        val conversationManager = mockk<ConversationManager>()
        coEvery { conversationManager.loadHistory(any()) } returns emptyList<Message>()
        val loopExecutor = mockk<StreamingReActLoopExecutor>()
        coEvery {
            loopExecutor.execute(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns StreamingLoopResult(success = true, collectedContent = "done", lastIterationContent = "done")

        val coordinator = StreamingExecutionCoordinator(
            concurrencySemaphore = Semaphore(1),
            requestTimeoutMs = 1_000L,
            maxToolCallsLimit = 4,
            preExecutionResolver = PreExecutionResolver(
                guard = null,
                hookExecutor = null,
                intentResolver = null,
                blockedIntents = emptySet(),
                agentMetrics = metrics
            ),
            conversationManager = conversationManager,
            ragContextRetriever = RagContextRetriever(
                enabled = false,
                topK = 4,
                rerankEnabled = false,
                ragPipeline = null,
                retrievalTimeoutMs = 5000
            ),
            systemPromptBuilder = SystemPromptBuilder(),
            toolPreparationPlanner = ToolPreparationPlanner(
                localTools = emptyList(),
                toolCallbacks = emptyList(),
                mcpToolCallbacks = { emptyList() },
                toolSelector = null,
                maxToolsPerRequest = 8,
                fallbackToolTimeoutMs = 1_000L
            ),
            resolveChatClient = { mockk<ChatClient>(relaxed = true) },
            resolveIntentAllowedTools = { null },
            streamingReActLoopExecutor = loopExecutor,
            errorMessageResolver = DefaultErrorMessageResolver(),
            agentErrorPolicy = AgentErrorPolicy(),
            agentMetrics = metrics
        )
        val hookContext = HookContext(runId = "run-1", userId = "u", userPrompt = "hi", channel = "web")

        val state = coordinator.execute(
            command = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "hi",
                mode = AgentMode.REACT,
                metadata = mapOf("channel" to "web")
            ),
            hookContext = hookContext,
            toolsUsed = mutableListOf(),
            emit = {}
        )

        assertTrue(state.streamSuccess, "Streaming coordinator should mark the run as successful")
        val stageTimings = readStageTimings(hookContext)
        assertTrue(stageTimings.containsKey("queue_wait"), "queue_wait timing should be recorded")
        assertTrue(stageTimings.containsKey("guard"), "guard timing should be recorded")
        assertTrue(stageTimings.containsKey("before_hooks"), "before_hooks timing should be recorded")
        assertTrue(stageTimings.containsKey("history_load"), "history_load timing should be recorded")
        assertTrue(stageTimings.containsKey("rag_retrieval"), "rag_retrieval timing should be recorded")
        assertTrue(stageTimings.containsKey("tool_selection"), "tool_selection timing should be recorded")
        assertTrue(stageTimings.containsKey("agent_loop"), "agent_loop timing should be recorded")
        verify { metrics.recordStageLatency("queue_wait", any(), match { it["channel"] == "web" }) }
        verify { metrics.recordStageLatency("guard", any(), match { it["channel"] == "web" }) }
        verify { metrics.recordStageLatency("before_hooks", any(), match { it["channel"] == "web" }) }
        verify { metrics.recordStageLatency("history_load", any(), match { it["channel"] == "web" }) }
        verify { metrics.recordStageLatency("rag_retrieval", any(), match { it["channel"] == "web" }) }
        verify { metrics.recordStageLatency("tool_selection", any(), match { it["channel"] == "web" }) }
        verify { metrics.recordStageLatency("agent_loop", any(), match { it["channel"] == "web" }) }
    }
}
