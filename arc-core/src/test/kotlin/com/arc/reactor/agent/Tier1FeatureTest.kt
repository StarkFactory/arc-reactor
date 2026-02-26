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
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * TDD tests for Tier 1 core features:
 * 1-1: ToolSelector + ToolCallback integration
 * 1-2: AgentMetrics integration
 * 1-3: RAG → Agent integration
 * 1-4: ReAct loop controls (unused config properties)
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
        fun `should call ToolSelector with toolCallbacks`() = runBlocking {
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
        fun `should include MCP callbacks in ToolSelector filtering`() = runBlocking {
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

            // ToolSelector should receive both custom and MCP callbacks
            verify {
                selector.select("query", match { it.size == 2 && it.any { cb -> cb.name == "mcp-tool" } })
            }
        }

        @Test
        fun `should pass filtered ToolCallbacks as Spring AI tools`() = runBlocking {
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
        fun `should use AllToolSelector when no selector provided`() = runBlocking {
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
        fun `should not call tools when no callbacks and no local tools`() = runBlocking {
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "query"))

            // Neither tools() nor toolCallbacks() should be called when no tools
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
        fun `should call recordExecution on success`() = runBlocking {
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
        fun `should call recordExecution on failure`() = runBlocking {
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
        fun `should call recordGuardRejection when guard rejects`() = runBlocking {
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
        fun `should work with NoOpAgentMetrics by default`() = runBlocking {
            // No agentMetrics parameter → should use NoOpAgentMetrics
            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties
            )

            val result = executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))

            result.assertSuccess() // Should not throw even without metrics
        }
    }

    // =========================================================================
    // Tier 1-3: RAG → Agent Integration
    // =========================================================================
    @Nested
    inner class RagIntegration {

        @Test
        fun `should inject RAG context into system prompt`() = runBlocking {
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

            executor.execute(AgentCommand(systemPrompt = "You are helpful.", userPrompt = "What is the return policy?"))

            // RAG pipeline should be called
            coVerify { ragPipeline.retrieve(match { it.query == "What is the return policy?" }) }

            // System prompt should include RAG context
            verify {
                fixture.requestSpec.system(match<String> { it.contains("Return policy allows 30-day returns.") })
            }
        }

        @Test
        fun `should pass metadata filters to RAG query`() = runBlocking {
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
        fun `should not call RAG when disabled`() = runBlocking {
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
        fun `should not call RAG when pipeline is null`() = runBlocking {
            val ragProperties = RagProperties(enabled = true)
            val props = properties.copy(rag = ragProperties)

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props
                // No ragPipeline provided
            )

            // Should execute successfully without RAG
            val result = executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))
            result.assertSuccess()
        }

        @Test
        fun `should handle RAG failure gracefully`() = runBlocking {
            val ragPipeline = mockk<RagPipeline>()
            coEvery { ragPipeline.retrieve(any()) } throws RuntimeException("Vector store unavailable")

            val ragProperties = RagProperties(enabled = true)
            val props = properties.copy(rag = ragProperties)

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props,
                ragPipeline = ragPipeline
            )

            // Should still execute successfully (RAG failure should not block agent)
            val result = executor.execute(AgentCommand(systemPrompt = "Test", userPrompt = "Hello"))
            result.assertSuccess()
        }

        @Test
        fun `should skip RAG context when no documents found`() = runBlocking {
            val ragPipeline = mockk<RagPipeline>()
            coEvery { ragPipeline.retrieve(any()) } returns RagContext.EMPTY

            val ragProperties = RagProperties(enabled = true)
            val props = properties.copy(rag = ragProperties)

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = props,
                ragPipeline = ragPipeline
            )

            executor.execute(AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Hello"))

            // System prompt should be the original, without RAG context appended
            verify { fixture.requestSpec.system("You are helpful.") }
        }
    }

    // =========================================================================
    // Tier 1-4: ReAct Loop Controls (unused config wiring)
    // =========================================================================
    @Nested
    inner class ReActControls {

        @Test
        @Disabled("Verified via integration test")
        fun `should apply maxToolCalls from AgentCommand`() = runBlocking {
        }

        @Test
        fun `should run in STANDARD mode without tools`() = runBlocking {
            val callback = AgentTestFixture.toolCallback("tool1", "Tool 1")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                toolCallbacks = listOf(callback)
            )

            // STANDARD mode should not include tools
            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "Test",
                    userPrompt = "Hello",
                    mode = com.arc.reactor.agent.model.AgentMode.STANDARD
                )
            )

            result.assertSuccess()
            // In STANDARD mode, tools should NOT be passed
            verify(exactly = 0) { fixture.requestSpec.tools(*anyVararg<Any>()) }
            verify(exactly = 0) { fixture.requestSpec.toolCallbacks(any<List<org.springframework.ai.tool.ToolCallback>>()) }
        }

        @Test
        fun `should include tools in REACT mode`() = runBlocking {
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
