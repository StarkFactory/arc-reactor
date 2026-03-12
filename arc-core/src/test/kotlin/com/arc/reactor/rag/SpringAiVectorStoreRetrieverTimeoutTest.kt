package com.arc.reactor.rag

import com.arc.reactor.rag.impl.SpringAiVectorStoreRetriever
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore

class SpringAiVectorStoreRetrieverTimeoutTest {

    @Test
    fun `returns empty list when vector store exceeds timeout`() = runBlocking {
        val vectorStore = mockk<VectorStore>()
        every { vectorStore.similaritySearch(any<SearchRequest>()) } answers {
            Thread.sleep(500)
            listOf(Document("test content"))
        }

        val retriever = SpringAiVectorStoreRetriever(
            vectorStore = vectorStore,
            defaultSimilarityThreshold = 0.5,
            timeoutMs = 200
        )

        val results = retriever.retrieve(
            queries = listOf("test query"),
            topK = 5,
            filters = emptyMap()
        )

        assertTrue(results.isEmpty()) {
            "Should return empty list when vector store exceeds timeout, got ${results.size} results"
        }
    }

    @Test
    fun `returns documents when vector store responds within timeout`() = runBlocking {
        val vectorStore = mockk<VectorStore>()
        every { vectorStore.similaritySearch(any<SearchRequest>()) } returns listOf(
            Document.builder().text("test content").metadata("score", 0.9).build()
        )

        val retriever = SpringAiVectorStoreRetriever(
            vectorStore = vectorStore,
            defaultSimilarityThreshold = 0.5,
            timeoutMs = 5000
        )

        val results = retriever.retrieve(
            queries = listOf("test query"),
            topK = 5,
            filters = emptyMap()
        )

        assertTrue(results.isNotEmpty()) {
            "Should return documents when vector store responds within timeout"
        }
    }

    @Test
    fun `returns empty list when vector store throws exception`() = runBlocking {
        val vectorStore = mockk<VectorStore>()
        every { vectorStore.similaritySearch(any<SearchRequest>()) } throws
            RuntimeException("connection refused")

        val retriever = SpringAiVectorStoreRetriever(
            vectorStore = vectorStore,
            defaultSimilarityThreshold = 0.5,
            timeoutMs = 5000
        )

        val results = retriever.retrieve(
            queries = listOf("test query"),
            topK = 5,
            filters = emptyMap()
        )

        assertTrue(results.isEmpty()) {
            "Should return empty list when vector store throws exception, got ${results.size} results"
        }
    }
}
