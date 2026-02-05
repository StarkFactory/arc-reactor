package com.arc.reactor.rag

import com.arc.reactor.rag.impl.DefaultRagPipeline
import com.arc.reactor.rag.impl.DiversityReranker
import com.arc.reactor.rag.impl.InMemoryDocumentRetriever
import com.arc.reactor.rag.impl.KeywordWeightedReranker
import com.arc.reactor.rag.impl.SimpleScoreReranker
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RagPipelineTest {

    @Test
    fun `should retrieve documents from in-memory retriever`() = runBlocking {
        val retriever = InMemoryDocumentRetriever()
        retriever.addDocument(RetrievedDocument(
            id = "doc-1",
            content = "Kotlin is a modern programming language",
            score = 0.9
        ))
        retriever.addDocument(RetrievedDocument(
            id = "doc-2",
            content = "Spring Boot is a framework for Java",
            score = 0.8
        ))

        val pipeline = DefaultRagPipeline(retriever = retriever)

        val result = pipeline.retrieve(RagQuery(
            query = "kotlin programming",
            topK = 10,
            rerank = false
        ))

        assertTrue(result.hasDocuments)
        assertEquals(1, result.documents.size)
        assertEquals("doc-1", result.documents[0].id)
    }

    @Test
    fun `should return empty context when no documents found`() = runBlocking {
        val retriever = InMemoryDocumentRetriever()
        val pipeline = DefaultRagPipeline(retriever = retriever)

        val result = pipeline.retrieve(RagQuery(
            query = "nonexistent topic",
            topK = 5
        ))

        assertFalse(result.hasDocuments)
        assertEquals(RagContext.EMPTY, result)
    }

    @Test
    fun `should rerank documents with SimpleScoreReranker`() = runBlocking {
        val retriever = InMemoryDocumentRetriever()
        retriever.addDocuments(listOf(
            RetrievedDocument(id = "1", content = "kotlin spring boot programming", score = 0.7),
            RetrievedDocument(id = "2", content = "kotlin coroutines async programming", score = 0.9),
            RetrievedDocument(id = "3", content = "java programming language", score = 0.5)
        ))

        val pipeline = DefaultRagPipeline(
            retriever = retriever,
            reranker = SimpleScoreReranker()
        )

        val result = pipeline.retrieve(RagQuery(
            query = "kotlin",
            topK = 10,
            rerank = true
        ))

        assertTrue(result.documents.isNotEmpty())
        // After reranking, should be sorted by score (highest first)
        if (result.documents.size > 1) {
            assertTrue(result.documents[0].score >= result.documents[1].score)
        }
    }

    @Test
    fun `should apply keyword weighted reranker`() = runBlocking {
        val retriever = InMemoryDocumentRetriever()
        retriever.addDocuments(listOf(
            RetrievedDocument(id = "1", content = "Java is a programming language", score = 0.9),
            RetrievedDocument(id = "2", content = "Kotlin is a modern language for JVM", score = 0.7)
        ))

        val pipeline = DefaultRagPipeline(
            retriever = retriever,
            reranker = KeywordWeightedReranker(keywordWeight = 0.5)
        )

        val result = pipeline.retrieve(RagQuery(
            query = "kotlin modern",
            topK = 10,
            rerank = true
        ))

        assertTrue(result.documents.isNotEmpty())
        // Kotlin document should rank higher due to keyword match
        assertEquals("2", result.documents[0].id)
    }

    @Test
    fun `should apply diversity reranker`() = runBlocking {
        val retriever = InMemoryDocumentRetriever()
        retriever.addDocuments(listOf(
            RetrievedDocument(id = "1", content = "kotlin programming language features", score = 0.9),
            RetrievedDocument(id = "2", content = "kotlin programming language syntax", score = 0.85),
            RetrievedDocument(id = "3", content = "spring boot web framework java", score = 0.8)
        ))

        val pipeline = DefaultRagPipeline(
            retriever = retriever,
            reranker = DiversityReranker(lambda = 0.5)
        )

        val result = pipeline.retrieve(RagQuery(
            query = "programming",
            topK = 10,
            rerank = true
        ))

        assertTrue(result.documents.isNotEmpty())
        // MMR should prefer diverse documents
    }

    @Test
    fun `should build context from documents`() = runBlocking {
        val retriever = InMemoryDocumentRetriever()
        retriever.addDocument(RetrievedDocument(
            id = "1",
            content = "Important information about Kotlin",
            source = "kotlin-docs.md"
        ))

        val pipeline = DefaultRagPipeline(retriever = retriever)

        val result = pipeline.retrieve(RagQuery(
            query = "kotlin information",
            topK = 10,
            rerank = false
        ))

        assertTrue(result.context.contains("Kotlin"))
        assertTrue(result.context.contains("kotlin-docs.md"))
    }

    @Test
    fun `should estimate token count`() = runBlocking {
        val retriever = InMemoryDocumentRetriever()
        retriever.addDocument(RetrievedDocument(
            id = "1",
            content = "A".repeat(400), // ~100 tokens
            score = 0.9
        ))

        val pipeline = DefaultRagPipeline(retriever = retriever)

        val result = pipeline.retrieve(RagQuery(
            query = "a",
            topK = 10,
            rerank = false
        ))

        assertTrue(result.totalTokens > 0)
        assertTrue(result.totalTokens <= 400 / 4 + 50) // Approximate with some buffer
    }

    @Test
    fun `InMemoryDocumentRetriever should clear documents`() = runBlocking {
        val retriever = InMemoryDocumentRetriever()
        retriever.addDocument(RetrievedDocument(id = "1", content = "test"))

        retriever.clear()

        val results = retriever.retrieve(listOf("test"), 10)
        assertTrue(results.isEmpty())
    }
}
