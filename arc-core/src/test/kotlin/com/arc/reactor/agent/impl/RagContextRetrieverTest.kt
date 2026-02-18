package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.rag.RagPipeline
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RagContextRetrieverTest {

    @Test
    fun `returns null and does not call pipeline when rag is disabled`() = runBlocking {
        val pipeline = mockk<RagPipeline>()
        val retriever = RagContextRetriever(
            enabled = false,
            topK = 3,
            rerankEnabled = true,
            ragPipeline = pipeline
        )

        val result = retriever.retrieve(AgentCommand(systemPrompt = "sys", userPrompt = "hello"))

        assertNull(result)
        coVerify(exactly = 0) { pipeline.retrieve(any()) }
    }

    @Test
    fun `returns context and merges explicit filters with prefixed filters`() = runBlocking {
        val pipeline = mockk<RagPipeline>()
        val querySlot = slot<RagQuery>()
        coEvery { pipeline.retrieve(capture(querySlot)) } returns RagContext(
            context = "retrieved context",
            documents = listOf(RetrievedDocument(id = "doc-1", content = "content"))
        )
        val retriever = RagContextRetriever(
            enabled = true,
            topK = 7,
            rerankEnabled = false,
            ragPipeline = pipeline
        )
        val command = AgentCommand(
            systemPrompt = "sys",
            userPrompt = "refund policy",
            metadata = mapOf(
                "ragFilters" to mapOf("tenant" to "acme", "channel" to "slack"),
                "rag.filter.channel" to "discord",
                "rag.filter.region" to "us"
            )
        )

        val result = retriever.retrieve(command)

        assertEquals("retrieved context", result)
        assertEquals("refund policy", querySlot.captured.query)
        assertEquals(7, querySlot.captured.topK)
        assertEquals(false, querySlot.captured.rerank)
        assertEquals("acme", querySlot.captured.filters["tenant"])
        assertEquals("slack", querySlot.captured.filters["channel"])
        assertEquals("us", querySlot.captured.filters["region"])
    }

    @Test
    fun `returns null when pipeline fails with non-cancellation exception`() = runBlocking {
        val pipeline = mockk<RagPipeline>()
        coEvery { pipeline.retrieve(any()) } throws IllegalStateException("rag failed")
        val retriever = RagContextRetriever(
            enabled = true,
            topK = 5,
            rerankEnabled = true,
            ragPipeline = pipeline
        )

        val result = retriever.retrieve(AgentCommand(systemPrompt = "sys", userPrompt = "hello"))

        assertNull(result)
    }

    @Test
    fun `rethrows cancellation exception from pipeline`() {
        val pipeline = mockk<RagPipeline>()
        coEvery { pipeline.retrieve(any()) } throws CancellationException("cancelled")
        val retriever = RagContextRetriever(
            enabled = true,
            topK = 5,
            rerankEnabled = true,
            ragPipeline = pipeline
        )

        assertThrows(CancellationException::class.java) {
            runBlocking {
                retriever.retrieve(AgentCommand(systemPrompt = "sys", userPrompt = "hello"))
            }
        }
    }
}
