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

/**
 * Spring AI VectorStore Retriever 타임아웃에 대한 테스트.
 *
 * 벡터 저장소 조회 시 타임아웃 처리를 검증합니다.
 */
class SpringAiVectorStoreRetrieverTimeoutTest {

    @Test
    fun `vector store exceeds timeout일 때 empty list를 반환한다`() = runBlocking {
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
    fun `vector store responds within timeout일 때 documents를 반환한다`() = runBlocking {
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
    fun `vector store throws exception일 때 empty list를 반환한다`() = runBlocking {
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
