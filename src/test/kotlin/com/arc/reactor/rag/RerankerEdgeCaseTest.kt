package com.arc.reactor.rag

import com.arc.reactor.rag.impl.DiversityReranker
import com.arc.reactor.rag.impl.InMemoryDocumentRetriever
import com.arc.reactor.rag.impl.KeywordWeightedReranker
import com.arc.reactor.rag.impl.SimpleScoreReranker
import com.arc.reactor.rag.model.RetrievedDocument
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RerankerEdgeCaseTest {

    @Test
    fun `SimpleScoreReranker should handle empty list`() = runBlocking {
        val reranker = SimpleScoreReranker()
        val result = reranker.rerank("query", emptyList(), 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `SimpleScoreReranker should respect topK limit`() = runBlocking {
        val reranker = SimpleScoreReranker()
        val docs = (1..10).map {
            RetrievedDocument(id = "doc-$it", content = "content $it", score = it.toDouble())
        }
        val result = reranker.rerank("query", docs, 3)
        assertEquals(3, result.size)
        assertEquals(10.0, result[0].score)
    }

    @Test
    fun `KeywordWeightedReranker should handle empty query`() = runBlocking {
        val reranker = KeywordWeightedReranker()
        val docs = listOf(
            RetrievedDocument(id = "1", content = "test content", score = 0.8)
        )
        val result = reranker.rerank("", docs, 5)
        assertEquals(1, result.size)
    }

    @Test
    fun `KeywordWeightedReranker should handle empty documents`() = runBlocking {
        val reranker = KeywordWeightedReranker()
        val result = reranker.rerank("test query", emptyList(), 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `KeywordWeightedReranker should boost documents with matching keywords`() = runBlocking {
        val reranker = KeywordWeightedReranker(keywordWeight = 0.8)
        val docs = listOf(
            RetrievedDocument(id = "1", content = "unrelated topic about weather", score = 0.9),
            RetrievedDocument(id = "2", content = "kotlin programming language guide", score = 0.5)
        )
        val result = reranker.rerank("kotlin programming", docs, 2)
        assertEquals("2", result[0].id) // keyword match should win with high keyword weight
    }

    @Test
    fun `DiversityReranker should handle empty list`() = runBlocking {
        val reranker = DiversityReranker()
        val result = reranker.rerank("query", emptyList(), 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `DiversityReranker should handle single document`() = runBlocking {
        val reranker = DiversityReranker()
        val docs = listOf(
            RetrievedDocument(id = "1", content = "only document", score = 0.8)
        )
        val result = reranker.rerank("query", docs, 5)
        assertEquals(1, result.size)
        assertEquals("1", result[0].id)
    }

    @Test
    fun `DiversityReranker should prefer diverse content`() = runBlocking {
        val reranker = DiversityReranker(lambda = 0.3) // Strong diversity preference
        val docs = listOf(
            RetrievedDocument(id = "1", content = "kotlin programming language features", score = 0.95),
            RetrievedDocument(id = "2", content = "kotlin programming language syntax", score = 0.90),
            RetrievedDocument(id = "3", content = "spring boot web framework deployment", score = 0.80)
        )
        val result = reranker.rerank("programming", docs, 3)
        // First should be highest score
        assertEquals("1", result[0].id)
        // Second should be diverse (spring boot) rather than similar (kotlin syntax)
        assertEquals("3", result[1].id)
    }

    @Test
    fun `DiversityReranker should respect topK`() = runBlocking {
        val reranker = DiversityReranker()
        val docs = (1..10).map {
            RetrievedDocument(id = "doc-$it", content = "unique content $it", score = it.toDouble() / 10)
        }
        val result = reranker.rerank("query", docs, 3)
        assertEquals(3, result.size)
    }

    @Test
    fun `InMemoryDocumentRetriever should handle multiple queries`() = runBlocking {
        val retriever = InMemoryDocumentRetriever()
        retriever.addDocuments(listOf(
            RetrievedDocument(id = "1", content = "kotlin language"),
            RetrievedDocument(id = "2", content = "java framework"),
            RetrievedDocument(id = "3", content = "python scripting")
        ))

        val results = retriever.retrieve(listOf("kotlin", "java"), 10)
        assertTrue(results.any { it.id == "1" })
        assertTrue(results.any { it.id == "2" })
    }

    @Test
    fun `InMemoryDocumentRetriever should return empty for no matches`() = runBlocking {
        val retriever = InMemoryDocumentRetriever()
        retriever.addDocument(RetrievedDocument(id = "1", content = "kotlin language"))

        val results = retriever.retrieve(listOf("xyz"), 10)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `InMemoryDocumentRetriever should respect topK`() = runBlocking {
        val retriever = InMemoryDocumentRetriever()
        retriever.addDocuments((1..20).map {
            RetrievedDocument(id = "doc-$it", content = "common keyword content $it")
        })

        val results = retriever.retrieve(listOf("common keyword"), 5)
        assertEquals(5, results.size)
    }
}
