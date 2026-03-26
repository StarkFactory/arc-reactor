package com.arc.reactor.agent

import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.metrics.AgentMetrics
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.guard.RequestGuard
import com.arc.reactor.guard.model.GuardResult
import com.arc.reactor.guard.model.RejectionCategory
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RetrievedDocument
import com.arc.reactor.tool.ToolSelector
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tier 1 핵심 기능에 대한 TDD 테스트:
 * 1-1: ToolSelector + ToolCallback 통합
 * 1-2: AgentMetrics 통합
 * 1-3: RAG → Agent 통합
 * 1-4: ReAct 루프 제어 (미사용 설정 속성)
 */
class Tier1FeatureTest {

    private lateinit var fixture: AgentTestFixture
    private val properties = AgentTestFixture.defaultProperties()

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
        fixture.mockCallResponse()
    }

    // =========================================================================
    // Tier 1-1: ToolSelector + ToolCallback Integration
    // =========================================================================
    @Nested
    inner class ToolSelectorIntegration {

        @Test
        fun `toolCallbacks로 call ToolSelector해야 한다`() = runTest {
            val callback1 = AgentTestFixture.toolCallback("tool1", "Tool 1")
            val callback2 = AgentTestFixture.toolCallback("tool2", "Tool 2")

            val selector = mockk<ToolSelector>()
            every { selector.select(any(), any()) } returns listOf(callback1)

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(callback1, callback2),
                toolSelector = selector
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Use tool1"))

            verify { selector.select("Use tool1", listOf(callback1, callback2)) }
        }

        @Test
        fun `include MCP callbacks in ToolSelector filtering해야 한다`() = runTest {
            val customCallback = AgentTestFixture.toolCallback("custom", "Custom Tool")
            val mcpCallback = AgentTestFixture.toolCallback("mcp-tool", "MCP Tool")

            val selector = mockk<ToolSelector>()
            every { selector.select(any(), any()) } returns listOf(customCallback, mcpCallback)

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(customCallback),
                toolSelector = selector,
                mcpToolCallbacks = { listOf(mcpCallback) }
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "query"))

            // ToolSelector은(는) receive both custom and MCP callbacks해야 합니다
            verify {
                selector.select("query", match { it.size == 2 && it.any { cb -> cb.name == "mcp-tool" } })
            }
        }

        @Test
        fun `pass filtered ToolCallbacks as Spring AI tools해야 한다`() = runTest {
            val included = AgentTestFixture.toolCallback("included", "Included")
            val excluded = AgentTestFixture.toolCallback("excluded", "Excluded")

            val selector = mockk<ToolSelector>()
            every { selector.select(any(), any()) } returns listOf(included)

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(included, excluded),
                toolSelector = selector
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "query"))

            // toolCallbacks() should be called (selector returned 1 ToolCallback)
            verify { fixture.requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) }
        }

        @Test
        fun `no selector provided일 때 use AllToolSelector해야 한다`() = runTest {
            val callback = AgentTestFixture.toolCallback("tool", "Tool")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(callback)
            )

            val result = executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "query"))

            result.assertSuccess()
            // toolCallbacks() should be called with the callback wrapped as ArcToolCallbackAdapter
            verify { fixture.requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) }
        }

        @Test
        fun `no callbacks and no local tools일 때 not call tools해야 한다`() = runTest {
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "query"))

            // Neither tools() nor toolCallbacks()은(는) be called when no tools해야 합니다
            verify(exactly = 0) { fixture.requestSpec.tools(*anyVararg<Any>()) }
            verify(exactly = 0) { fixture.requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) }
        }
    }

    // =========================================================================
    // Tier 1-2: AgentMetrics Integration
    // =========================================================================
    @Nested
    inner class AgentMetricsIntegration {

        @Test
        fun `call recordExecution on success해야 한다`() = runTest {
            val metrics = mockk<AgentMetrics>(relaxed = true)

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                agentMetrics = metrics
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            verify { metrics.recordExecution(match { it.success }) }
        }

        @Test
        fun `call recordExecution on failure해야 한다`() = runTest {
            val metrics = mockk<AgentMetrics>(relaxed = true)
            every { fixture.requestSpec.call() } throws RuntimeException("LLM error")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                agentMetrics = metrics
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            verify { metrics.recordExecution(match { !it.success }) }
        }

        @Test
        fun `guard rejects일 때 call recordGuardRejection해야 한다`() = runTest {
            val metrics = mockk<AgentMetrics>(relaxed = true)
            val guard = mockk<RequestGuard>()
            coEvery { guard.guard(any()) } returns GuardResult.Rejected(
                reason = "Rate limit",
                category = RejectionCategory.RATE_LIMITED,
                stage = "rateLimit"
            )

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                guard = guard,
                agentMetrics = metrics
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello", userId = "user-1"))

            verify { metrics.recordGuardRejection("rateLimit", "Rate limit", any()) }
        }

        @Test
        fun `NoOpAgentMetrics by default로 work해야 한다`() = runTest {
            // agentMetrics 매개변수 없음 → NoOpAgentMetrics를 사용해야 합니다
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val result = executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            result.assertSuccess()  // not throw even without metrics해야 합니다
        }
    }

    // =========================================================================
    // Tier 1-3: RAG → Agent Integration
    // =========================================================================
    @Nested
    inner class RagIntegration {

        @Test
        fun `inject RAG context into system prompt해야 한다`() = runTest {
            val ragPipeline = mockk<RagPipeline>()
            coEvery { ragPipeline.retrieve(any()) } returns RagContext(
                context = "Relevant document: Return policy allows 30-day returns.",
                documents = listOf(
                    RetrievedDocument(id = "doc1", content = "Return policy allows 30-day returns.", score = 0.9)
                ),
                totalTokens = 20
            )

            val ragProperties = RagProperties(enabled = true, topK = 5, maxContextTokens = 4000)
            val props = properties.copy(rag = ragProperties)

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props,
                ragPipeline = ragPipeline
            )

            executor.execute(AgentCommand(systemPrompt = "You are helpful.", userPrompt = "What is the pipeline architecture?"))

            // RAG pipeline은(는) be called해야 합니다
            coVerify { ragPipeline.retrieve(match { it.query == "What is the pipeline architecture?" }) }

            // System prompt은(는) include RAG context해야 합니다
            verify {
                fixture.requestSpec.system(match<String> { it.contains("Return policy allows 30-day returns.") })
            }
        }

        @Test
        fun `pass metadata filters to RAG query해야 한다`() = runTest {
            val ragPipeline = mockk<RagPipeline>()
            coEvery { ragPipeline.retrieve(any()) } returns RagContext.EMPTY

            val ragProperties = RagProperties(enabled = true, topK = 5, maxContextTokens = 4000)
            val props = properties.copy(rag = ragProperties)

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props,
                ragPipeline = ragPipeline
            )

            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Show docs",
                    metadata = mapOf(
                        "ragFilters" to mapOf("source" to "confluence"),
                        "rag.filter.space" to "ENG"
                    )
                )
            )

            coVerify {
                ragPipeline.retrieve(
                    match {
                        it.query == "Show docs" &&
                            it.filters["source"] == "confluence" &&
                            it.filters["space"] == "ENG"
                    }
                )
            }
        }

        @Test
        fun `disabled일 때 not call RAG해야 한다`() = runTest {
            val ragPipeline = mockk<RagPipeline>()

            val ragProperties = RagProperties(enabled = false)
            val props = properties.copy(rag = ragProperties)

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props,
                ragPipeline = ragPipeline
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            coVerify(exactly = 0) { ragPipeline.retrieve(any()) }
        }

        @Test
        fun `pipeline is null일 때 not call RAG해야 한다`() = runTest {
            val ragProperties = RagProperties(enabled = true)
            val props = properties.copy(rag = ragProperties)

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props
                // ragPipeline provided 없음
            )

            // execute successfully without RAG해야 합니다
            val result = executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))
            result.assertSuccess()
        }

        @Test
        fun `handle RAG failure gracefully해야 한다`() = runTest {
            val ragPipeline = mockk<RagPipeline>()
            coEvery { ragPipeline.retrieve(any()) } throws RuntimeException("Vector store unavailable")

            val ragProperties = RagProperties(enabled = true)
            val props = properties.copy(rag = ragProperties)

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props,
                ragPipeline = ragPipeline
            )

            // 여전히 성공적으로 실행되어야 합니다 (RAG 실패가 에이전트를 차단하지 않아야 합니다)
            val result = executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))
            result.assertSuccess()
        }

        @Test
        fun `no documents found일 때 skip RAG context해야 한다`() = runTest {
            val ragPipeline = mockk<RagPipeline>()
            val systemPromptSlot = slot<String>()
            coEvery { ragPipeline.retrieve(any()) } returns RagContext.EMPTY
            every { fixture.requestSpec.system(capture(systemPromptSlot)) } returns fixture.requestSpec

            val ragProperties = RagProperties(enabled = true)
            val props = properties.copy(rag = ragProperties)

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props,
                ragPipeline = ragPipeline
            )

            executor.execute(AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Hello"))

            val capturedPrompt = systemPromptSlot.captured
            assertTrue(capturedPrompt.contains("You are helpful.")) {
                "Base system prompt should be preserved when RAG returns no documents. Prompt was: $capturedPrompt"
            }
            assertTrue(capturedPrompt.contains("[Grounding Rules]")) {
                "Grounding rules should still be present when RAG is enabled. Prompt was: $capturedPrompt"
            }
            assertFalse(capturedPrompt.contains("[Retrieved Context]")) {
                "RAG context should not be appended when no documents are found. Prompt was: $capturedPrompt"
            }
        }
    }

    // =========================================================================
    // Tier 1-4: ReAct Loop Controls (unused config wiring)
    // =========================================================================
    @Nested
    inner class ReActControls {

        @Test
        fun `run in STANDARD mode without tools해야 한다`() = runTest {
            val callback = AgentTestFixture.toolCallback("tool1", "Tool 1")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(callback)
            )

            // STANDARD mode은(는) not include tools해야 합니다
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "Test",
                    userPrompt = "Hello",
                    mode = com.arc.reactor.agent.model.AgentMode.STANDARD
                )
            )

            result.assertSuccess()
            // In STANDARD mode, tools은(는) NOT be passed해야 합니다
            verify(exactly = 0) { fixture.requestSpec.tools(*anyVararg<Any>()) }
            verify(exactly = 0) { fixture.requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) }
        }

        @Test
        fun `include tools in REACT mode해야 한다`() = runTest {
            val callback = AgentTestFixture.toolCallback("tool1", "Tool 1")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(callback)
            )

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "Test",
                    userPrompt = "Hello",
                    mode = com.arc.reactor.agent.model.AgentMode.REACT
                )
            )

            result.assertSuccess()
            verify { fixture.requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) }
        }
    }
}
