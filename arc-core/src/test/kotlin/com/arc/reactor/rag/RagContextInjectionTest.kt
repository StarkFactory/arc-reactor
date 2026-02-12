package com.arc.reactor.rag

import com.arc.reactor.agent.AgentTestFixture
import com.arc.reactor.agent.assertSuccess
import com.arc.reactor.agent.config.AgentProperties
import com.arc.reactor.agent.config.RagProperties
import com.arc.reactor.agent.impl.SpringAiAgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.rag.impl.SimpleContextBuilder
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for RAG context injection quality improvements:
 * 1. System prompt includes instructed RAG context (not bare text)
 * 2. SimpleContextBuilder produces numbered document references
 */
class RagContextInjectionTest {

    @Nested
    inner class RagInstructionInSystemPrompt {

        private lateinit var fixture: AgentTestFixture
        private lateinit var ragPipeline: RagPipeline

        @BeforeEach
        fun setup() {
            fixture = AgentTestFixture()
            ragPipeline = mockk()
        }

        @Test
        fun `should include RAG instructions when context is available`() = runBlocking {
            val systemSlot = slot<String>()
            io.mockk.every { fixture.requestSpec.system(capture(systemSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("Answer based on context")

            coEvery { ragPipeline.retrieve(any()) } returns RagContext(
                context = "[1] Source: docs\nReturn policy: 30 days.",
                documents = listOf(
                    RetrievedDocument(id = "1", content = "Return policy: 30 days.", score = 0.9, source = "docs")
                ),
                totalTokens = 10
            )

            val properties = AgentTestFixture.defaultProperties().let {
                AgentProperties(
                    llm = it.llm,
                    guard = it.guard,
                    rag = RagProperties(enabled = true, topK = 5),
                    concurrency = it.concurrency
                )
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                ragPipeline = ragPipeline
            )

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are a helpful assistant.",
                    userPrompt = "What is the return policy?"
                )
            )

            result.assertSuccess()
            assertTrue(systemSlot.isCaptured) { "System prompt should have been set" }

            val prompt = systemSlot.captured
            assertTrue(prompt.contains("[Retrieved Context]")) {
                "System prompt should contain '[Retrieved Context]' header"
            }
            assertTrue(prompt.contains("knowledge base")) {
                "System prompt should contain RAG instructions mentioning 'knowledge base'"
            }
            assertTrue(prompt.contains("say so rather than guessing")) {
                "System prompt should instruct LLM to not hallucinate"
            }
            assertTrue(prompt.contains("Do not mention the retrieval process")) {
                "System prompt should instruct LLM to not break immersion"
            }
            assertTrue(prompt.contains("Return policy: 30 days.")) {
                "System prompt should contain actual RAG content"
            }
        }

        @Test
        fun `should not include RAG section when pipeline returns empty context`() = runBlocking {
            val systemSlot = slot<String>()
            io.mockk.every { fixture.requestSpec.system(capture(systemSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("No context available")

            coEvery { ragPipeline.retrieve(any()) } returns RagContext.EMPTY

            val properties = AgentTestFixture.defaultProperties().let {
                AgentProperties(
                    llm = it.llm,
                    guard = it.guard,
                    rag = RagProperties(enabled = true),
                    concurrency = it.concurrency
                )
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                ragPipeline = ragPipeline
            )

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are a helpful assistant.",
                    userPrompt = "Hello"
                )
            )

            result.assertSuccess()
            assertFalse(systemSlot.captured.contains("[Retrieved Context]")) {
                "System prompt should NOT contain RAG section when pipeline returns empty"
            }
        }

        @Test
        fun `should not include RAG section when RAG is disabled`() = runBlocking {
            val systemSlot = slot<String>()
            io.mockk.every { fixture.requestSpec.system(capture(systemSlot)) } returns fixture.requestSpec
            fixture.mockCallResponse("Response")

            val properties = AgentTestFixture.defaultProperties() // rag.enabled = false by default

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                ragPipeline = ragPipeline
            )

            val result = executor.execute(
                AgentCommand(
                    systemPrompt = "You are a helpful assistant.",
                    userPrompt = "Hello"
                )
            )

            result.assertSuccess()
            assertFalse(systemSlot.captured.contains("[Retrieved Context]")) {
                "System prompt should NOT contain RAG section when RAG is disabled"
            }
        }

        @Test
        fun `should pass correct topK from properties to RAG pipeline`() = runBlocking {
            val ragQuerySlot = slot<RagQuery>()
            coEvery { ragPipeline.retrieve(capture(ragQuerySlot)) } returns RagContext.EMPTY
            fixture.mockCallResponse("Response")

            val properties = AgentTestFixture.defaultProperties().let {
                AgentProperties(
                    llm = it.llm,
                    guard = it.guard,
                    rag = RagProperties(enabled = true, topK = 7),
                    concurrency = it.concurrency
                )
            }

            val executor = SpringAiAgentExecutor(
                chatClient = fixture.chatClient,
                properties = properties,
                ragPipeline = ragPipeline
            )

            executor.execute(
                AgentCommand(
                    systemPrompt = "You are helpful.",
                    userPrompt = "Search query"
                )
            )

            assertTrue(ragQuerySlot.isCaptured) { "RAG pipeline should be called" }
            assertEquals(7, ragQuerySlot.captured.topK) { "topK should match properties.rag.topK" }
            assertEquals("Search query", ragQuerySlot.captured.query) { "Query should be the user prompt" }
        }
    }

    @Nested
    inner class NumberedDocumentReferences {

        @Test
        fun `should produce numbered document references`() {
            val builder = SimpleContextBuilder()
            val docs = listOf(
                RetrievedDocument(id = "1", content = "First document content", score = 0.9, source = "docs"),
                RetrievedDocument(id = "2", content = "Second document content", score = 0.8, source = "wiki")
            )

            val context = builder.build(docs, 4000)

            assertTrue(context.contains("[1] Source: docs")) {
                "First document should have numbered reference [1] with source"
            }
            assertTrue(context.contains("[2] Source: wiki")) {
                "Second document should have numbered reference [2] with source"
            }
            assertTrue(context.contains("First document content")) {
                "First document content should be present"
            }
            assertTrue(context.contains("Second document content")) {
                "Second document content should be present"
            }
        }

        @Test
        fun `should produce numbered references without source when source is null`() {
            val builder = SimpleContextBuilder()
            val docs = listOf(
                RetrievedDocument(id = "1", content = "Content without source", score = 0.9)
            )

            val context = builder.build(docs, 4000)

            assertTrue(context.contains("[1]\n")) {
                "Document without source should have [1] followed by newline"
            }
            assertFalse(context.contains("Source:")) {
                "Should not contain 'Source:' when source is null"
            }
            assertTrue(context.contains("Content without source")) {
                "Document content should be present"
            }
        }

        @Test
        fun `numbered references should respect maxTokens limit`() {
            val builder = SimpleContextBuilder()

            // Each document: "[N]\n" + "A".repeat(400) = ~100 tokens
            val docs = (1..10).map { i ->
                RetrievedDocument(id = "$i", content = "A".repeat(400), score = 0.9)
            }

            // maxTokens = 250 → should fit ~2 docs (100 tokens each + numbering overhead)
            val context = builder.build(docs, 250)

            val docCount = context.split("A".repeat(400)).size - 1
            assertTrue(docCount in 1..3) {
                "Expected 1-3 docs within 250 token limit, got $docCount"
            }
        }

        @Test
        fun `should use separator between numbered documents`() {
            val builder = SimpleContextBuilder(separator = "\n---\n")
            val docs = listOf(
                RetrievedDocument(id = "1", content = "Doc1", score = 0.9),
                RetrievedDocument(id = "2", content = "Doc2", score = 0.8)
            )

            val context = builder.build(docs, 4000)

            assertTrue(context.contains("\n---\n")) {
                "Separator should appear between documents"
            }
            assertTrue(context.indexOf("[1]") < context.indexOf("[2]")) {
                "Documents should be numbered in order"
            }
        }
    }
}
