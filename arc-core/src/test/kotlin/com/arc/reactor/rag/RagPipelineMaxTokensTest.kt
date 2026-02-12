package com.arc.reactor.rag

import com.arc.reactor.rag.impl.DefaultRagPipeline
import com.arc.reactor.rag.impl.SimpleContextBuilder
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RagPipelineMaxTokensTest {

    @Test
    fun `should pass maxContextTokens to contextBuilder`() = runBlocking {
        val contextBuilder = mockk<ContextBuilder>()
        val retriever = mockk<DocumentRetriever>()

        val docs = listOf(
            RetrievedDocument(id = "1", content = "Hello world", score = 0.9)
        )

        coEvery { retriever.retrieve(any(), any()) } returns docs
        io.mockk.every { contextBuilder.build(any(), eq(2000)) } returns "Hello world"

        val pipeline = DefaultRagPipeline(
            retriever = retriever,
            contextBuilder = contextBuilder,
            maxContextTokens = 2000
        )

        pipeline.retrieve(RagQuery(query = "test", topK = 10))

        // Verify contextBuilder was called with maxContextTokens = 2000
        verify { contextBuilder.build(any(), 2000) }
    }

    @Test
    fun `should use default maxContextTokens of 4000`() = runBlocking {
        val contextBuilder = mockk<ContextBuilder>()
        val retriever = mockk<DocumentRetriever>()

        val docs = listOf(
            RetrievedDocument(id = "1", content = "Test", score = 0.9)
        )

        coEvery { retriever.retrieve(any(), any()) } returns docs
        io.mockk.every { contextBuilder.build(any(), eq(4000)) } returns "Test"

        val pipeline = DefaultRagPipeline(
            retriever = retriever,
            contextBuilder = contextBuilder
            // maxContextTokens defaults to 4000
        )

        pipeline.retrieve(RagQuery(query = "test", topK = 10))

        verify { contextBuilder.build(any(), 4000) }
    }

    @Test
    fun `SimpleContextBuilder should respect maxTokens limit`() {
        val builder = SimpleContextBuilder()

        // Each document has estimatedTokens = content.length / 4
        // "A".repeat(400) = 100 tokens
        val docs = (1..10).map { i ->
            RetrievedDocument(
                id = "$i",
                content = "A".repeat(400), // 100 tokens each
                score = 0.9
            )
        }

        // maxTokens = 250 → should fit ~2 docs (200 tokens) but not 3 (300 tokens)
        val context = builder.build(docs, 250)

        // Should contain at most 2 documents worth of content
        val docCount = context.split("A".repeat(400)).size - 1
        assertTrue(docCount <= 2, "Expected at most 2 docs, got $docCount")
        assertTrue(docCount >= 1, "Expected at least 1 doc")
    }
}
