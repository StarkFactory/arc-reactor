package com.arc.reactor.integration

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.AgentTestFixture.Companion.textChunk
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
 * 에이전트 + RAG 파이프라인에 대한 통합 테스트.
 *
 * RAG 컨텍스트가 에이전트의 시스템 프롬프트에 올바르게 주입되고,
 * 에이전트가 RAG 파이프라인 실패를 우아하게 처리하는지 검증합니다.
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
        fun `agent은(는) include RAG context in system prompt해야 한다`() = runBlocking {
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
        fun `agent은(는) work normally when RAG returns empty context해야 한다`() = runBlocking {
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
        fun `agent은(는) continue without RAG when pipeline throws해야 한다`() = runBlocking {
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
        fun `agent은(는) use RAG context AND tool calls together해야 한다`() = runBlocking {
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
        fun `RAG query은(는) use topK from properties해야 한다`() = runBlocking {
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
        fun `streaming agent은(는) include RAG context in system prompt해야 한다`() = runBlocking {
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
