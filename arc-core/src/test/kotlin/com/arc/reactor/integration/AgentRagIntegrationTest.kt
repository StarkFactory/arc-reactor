package com.arc.reactor.integration

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.AgentTestFixture.Companion.textChunk
import com.arc.reactor.agent.AgentTestFixture.Companion.toolCallChunk
import com.arc.reactor.agent.TrackingTool
import com.arc.reactor.agent.assertSuccess
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import reactor.core.publisher.Flux

/**
 * Integration tests for Agent + RAG Pipeline.
 *
 * Verifies that RAG context is correctly injected into the agent's system prompt,
 * and that the agent handles RAG pipeline failures gracefully.
 */
@Tag("integration")
class AgentRagIntegrationTest {

    private lateinit var fixture: AgentTestFixture
    private lateinit var ragPipeline: RagPipeline

    @BeforeEach
    fun setup() {
        fixture = AgentTestFixture()
        ragPipeline = mockk()
    }

    private fun ragProperties(enabled: Boolean = true, topK: Int = 5): AgentProperties {
        val base = AgentTestFixture.defaultProperties()
        return AgentProperties(
            llm = base.llm,
            guard = base.guard,
            rag = RagProperties(enabled = enabled, topK = topK),
            concurrency = base.concurrency
        )
    }

    private fun ragContext(content: String): RagContext = RagContext(
        context = content,
        documents = listOf(
            RetrievedDocument(id = "1", content = content, score = 0.9, source = "docs")
        ),
        totalTokens = content.length / 4
    )

    @Nested
    inner class NonStreamingWithRag {

        @Test
        fun `agent should include RAG context in system prompt`() = runBlocking {
            val systemSlot = slot<String>()
            every { fixture.requestSpec.system(capture(systemSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("Based on the policy, returns are accepted within 30 days.")

            coEvery { ragPipeline.retrieve(any()) } returns ragContext("Return policy: 30 days.")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = ragProperties(),
                ragPipeline = ragPipeline
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Return policy?")
            )

            result.assertSuccess()
            assertTrue(systemSlot.captured.contains("[Retrieved Context]")) {
                "System prompt should contain RAG context header"
            }
            assertTrue(systemSlot.captured.contains("Return policy: 30 days.")) {
                "System prompt should contain actual RAG content"
            }
        }

        @Test
        fun `agent should work normally when RAG returns empty context`() = runBlocking {
            val systemSlot = slot<String>()
            every { fixture.requestSpec.system(capture(systemSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("I don't have specific policy info.")

            coEvery { ragPipeline.retrieve(any()) } returns RagContext.EMPTY

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = ragProperties(),
                ragPipeline = ragPipeline
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Policy?")
            )

            result.assertSuccess()
            assertFalse(systemSlot.captured.contains("[Retrieved Context]")) {
                "System prompt should NOT contain RAG section when empty"
            }
        }

        @Test
        fun `agent should continue without RAG when pipeline throws`() = runBlocking {
            val systemSlot = slot<String>()
            every { fixture.requestSpec.system(capture(systemSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("I'll help without context.")

            coEvery { ragPipeline.retrieve(any()) } throws RuntimeException("Vector store unavailable")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = ragProperties(),
                ragPipeline = ragPipeline
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Help me")
            )

            result.assertSuccess("Agent should succeed despite RAG failure")
            assertFalse(systemSlot.captured.contains("[Retrieved Context]")) {
                "System prompt should NOT contain RAG section when pipeline fails"
            }
        }

        @Test
        fun `agent should use RAG context AND tool calls together`() = runBlocking {
            val systemSlot = slot<String>()
            every { fixture.requestSpec.system(capture(systemSlot)) } returns fixture.requestSpec

            coEvery { ragPipeline.retrieve(any()) } returns ragContext("Product catalog data")

            val toolCall = AssistantMessage.ToolCall("call-1", "function", "lookup", """{"id":"123"}""")
            val toolCallSpec = fixture.mockToolCallResponse(listOf(toolCall))
            val finalSpec = fixture.mockFinalResponse("Found product 123 from catalog.")

            every { fixture.requestSpec.call() } returnsMany listOf(toolCallSpec, finalSpec)

            val tool = TrackingTool("lookup", "Product: Widget")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = ragProperties(),
                toolCallbacks = listOf(tool),
                ragPipeline = ragPipeline
            )

            val result = executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Find product 123")
            )

            result.assertSuccess()
            assertTrue(systemSlot.captured.contains("[Retrieved Context]")) {
                "System prompt should contain RAG context"
            }
            assertTrue(systemSlot.captured.contains("Product catalog data")) {
                "RAG content should be present"
            }
            assertEquals(1, tool.callCount) { "Tool should be called once" }
        }

        @Test
        fun `RAG query should use topK from properties`() = runBlocking {
            val ragQuerySlot = slot<RagQuery>()
            coEvery { ragPipeline.retrieve(capture(ragQuerySlot)) } returns RagContext.EMPTY
            fixture.mockCallResponse("Response")

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = ragProperties(topK = 15),
                ragPipeline = ragPipeline
            )

            executor.execute(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Query")
            )

            assertEquals(15, ragQuerySlot.captured.topK) { "topK should match properties" }
        }
    }

    @Nested
    inner class StreamingWithRag {

        @Test
        fun `streaming agent should include RAG context in system prompt`() = runBlocking {
            val systemSlot = slot<String>()
            every { fixture.requestSpec.system(capture(systemSlot)) } returns fixture.requestSpec

            coEvery { ragPipeline.retrieve(any()) } returns ragContext("Streaming RAG data")

            every { fixture.streamResponseSpec.chatResponse() } returns
                Flux.just(textChunk("Response with context"))

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = ragProperties(),
                ragPipeline = ragPipeline
            )

            val chunks = executor.executeStream(
                AgentCommand(systemPrompt = "You are helpful.", userPrompt = "Stream query")
            ).toList()

            assertTrue(chunks.isNotEmpty()) { "Stream should produce output" }
            assertTrue(systemSlot.captured.contains("[Retrieved Context]")) {
                "Streaming system prompt should contain RAG context"
            }
            assertTrue(systemSlot.captured.contains("Streaming RAG data")) {
                "RAG content should be present in streaming prompt"
            }
        }
    }
}
