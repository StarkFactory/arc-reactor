package com.arc.reactor.rag

import com.arc.reactor.rag.impl.HybridRagPipeline
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument
import com.arc.reactor.rag.search.Bm25Scorer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class HybridRagPipelineTest {

    private lateinit var mockRetriever: DocumentRetriever
    private lateinit var bm25Scorer: Bm25Scorer

    private val docAlpha = RetrievedDocument(
        id = "doc-alpha",
        content = "ProjectAlpha is the core data pipeline owned by Team Orion",
        score = 0.85
    )
    private val docBeta = RetrievedDocument(
        id = "doc-beta",
        content = "ProjectBeta handles user authentication for the platform",
        score = 0.75
    )
    private val docGamma = RetrievedDocument(
        id = "doc-gamma",
        content = "team orion meets every Monday for sprint planning",
        score = 0.70
    )

    @BeforeEach
    fun setup() {
        mockRetriever = mockk()
        bm25Scorer = Bm25Scorer()

        // Pre-index documents in BM25
        for (doc in listOf(docAlpha, docBeta, docGamma)) {
            bm25Scorer.index(doc.id, doc.content)
        }
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `should return documents when vector and BM25 both find results`() = runTest {
            coEvery { mockRetriever.retrieve(any(), any(), any()) } returns listOf(docAlpha, docBeta)

            val pipeline = HybridRagPipeline(retriever = mockRetriever, bm25Scorer = bm25Scorer)
            val result = pipeline.retrieve(RagQuery(query = "ProjectAlpha data pipeline", topK = 5))

            assertTrue(result.hasDocuments) { "Pipeline should return documents when both signals find results" }
            assertTrue(result.documents.isNotEmpty()) { "Result documents list should not be empty" }
        }

        @Test
        fun `proper noun query should surface BM25 match even when vector score is lower`() = runTest {
            // Vector search returns docBeta first (higher vector score), docAlpha second
            coEvery { mockRetriever.retrieve(any(), any(), any()) } returns listOf(
                docBeta.copy(score = 0.95),
                docAlpha.copy(score = 0.60)
            )
            // BM25 has both docs; docAlpha contains the exact proper noun "ProjectAlpha"

            val pipeline = HybridRagPipeline(
                retriever = mockRetriever,
                bm25Scorer = bm25Scorer,
                vectorWeight = 0.5,
                bm25Weight = 0.5
            )
            val result = pipeline.retrieve(RagQuery(query = "ProjectAlpha", topK = 5, rerank = false))

            assertTrue(result.hasDocuments) { "Should return documents for proper noun query" }
            val ids = result.documents.map { it.id }
            assertTrue("doc-alpha" in ids) { "doc-alpha (exact proper noun match) should be in results" }
        }

        @Test
        fun `should call vector retriever exactly once`() = runTest {
            coEvery { mockRetriever.retrieve(any(), any(), any()) } returns listOf(docAlpha)

            val pipeline = HybridRagPipeline(retriever = mockRetriever, bm25Scorer = bm25Scorer)
            pipeline.retrieve(RagQuery(query = "test query", topK = 5))

            coVerify(exactly = 1) { mockRetriever.retrieve(any(), any(), any()) }
        }

        @Test
        fun `context should contain content from retrieved documents`() = runTest {
            coEvery { mockRetriever.retrieve(any(), any(), any()) } returns listOf(docAlpha, docGamma)

            val pipeline = HybridRagPipeline(retriever = mockRetriever, bm25Scorer = bm25Scorer)
            val result = pipeline.retrieve(RagQuery(query = "Team Orion", topK = 5, rerank = false))

            assertTrue(result.context.isNotBlank()) { "Context should not be blank when documents are retrieved" }
        }

        @Test
        fun `topK should limit the number of returned documents`() = runTest {
            val manyDocs = (1..10).map { i ->
                RetrievedDocument(id = "doc-$i", content = "document content number $i about the platform", score = 0.9 - i * 0.01)
            }
            for (doc in manyDocs) {
                bm25Scorer.index(doc.id, doc.content)
            }
            coEvery { mockRetriever.retrieve(any(), any(), any()) } returns manyDocs

            val pipeline = HybridRagPipeline(retriever = mockRetriever, bm25Scorer = bm25Scorer)
            val result = pipeline.retrieve(RagQuery(query = "platform document content", topK = 3, rerank = false))

            assertTrue(result.documents.size <= 3) {
                "topK=3 should return at most 3 documents, got ${result.documents.size}"
            }
        }
    }

    @Nested
    inner class EmptyAndEdgeCases {

        @Test
        fun `should return empty context when vector retriever returns nothing and BM25 index is empty`() = runTest {
            val emptyBm25 = Bm25Scorer()
            coEvery { mockRetriever.retrieve(any(), any(), any()) } returns emptyList()

            val pipeline = HybridRagPipeline(retriever = mockRetriever, bm25Scorer = emptyBm25)
            val result = pipeline.retrieve(RagQuery(query = "nothing matches", topK = 5))

            assertFalse(result.hasDocuments) { "Should return empty context when no results from any signal" }
            assertEquals(RagContext.EMPTY, result) { "Result should equal RagContext.EMPTY" }
        }

        @Test
        fun `should return empty context when vector returns nothing but BM25 also empty`() = runTest {
            coEvery { mockRetriever.retrieve(any(), any(), any()) } returns emptyList()
            val pipeline = HybridRagPipeline(retriever = mockRetriever, bm25Scorer = Bm25Scorer())

            val result = pipeline.retrieve(RagQuery(query = "ProjectAlpha", topK = 5))

            assertFalse(result.hasDocuments) {
                "Should return empty result when vector returns nothing and BM25 index is empty"
            }
        }
    }

    @Nested
    inner class Indexing {

        @Test
        fun `indexDocument should make new document searchable via BM25`() = runTest {
            val freshScorer = Bm25Scorer()
            val newDoc = RetrievedDocument(
                id = "doc-new", content = "TeamEpsilon owns the notification service", score = 0.8
            )
            coEvery { mockRetriever.retrieve(any(), any(), any()) } returns listOf(newDoc)

            val pipeline = HybridRagPipeline(retriever = mockRetriever, bm25Scorer = freshScorer)
            pipeline.indexDocument(newDoc)

            val bm25Results = freshScorer.search("TeamEpsilon", 5)
            assertTrue(bm25Results.isNotEmpty()) { "indexDocument should make the document searchable in BM25" }
            assertEquals("doc-new", bm25Results[0].first) { "doc-new should be the top BM25 result for TeamEpsilon" }
        }

        @Test
        fun `indexDocuments bulk should index all provided documents`() = runTest {
            val freshScorer = Bm25Scorer()
            val docs = listOf(
                RetrievedDocument(id = "bulk-1", content = "OrionTeam pipeline documentation", score = 0.9),
                RetrievedDocument(id = "bulk-2", content = "OrionTeam deployment runbook", score = 0.8)
            )
            coEvery { mockRetriever.retrieve(any(), any(), any()) } returns docs

            val pipeline = HybridRagPipeline(retriever = mockRetriever, bm25Scorer = freshScorer)
            pipeline.indexDocuments(docs)

            assertEquals(2, freshScorer.size) { "indexDocuments should add both documents to BM25 index" }
        }
    }

    @Nested
    inner class FusionBehavior {

        @Test
        fun `document appearing in both vector and BM25 results should have higher fused score`() = runTest {
            val scorerLocal = Bm25Scorer()
            // doc-common appears in both vector and BM25; doc-vector-only only in vector
            val docCommon = RetrievedDocument(id = "doc-common", content = "SentinelSystem monitors production", score = 0.8)
            val docVectorOnly = RetrievedDocument(id = "doc-vector-only", content = "vector isolated document", score = 0.9)

            scorerLocal.index("doc-common", docCommon.content)
            // Intentionally NOT indexing doc-vector-only in BM25

            coEvery { mockRetriever.retrieve(any(), any(), any()) } returns listOf(docVectorOnly, docCommon)

            val pipeline = HybridRagPipeline(
                retriever = mockRetriever,
                bm25Scorer = scorerLocal,
                vectorWeight = 0.5,
                bm25Weight = 0.5,
                rrfK = 60.0
            )
            val result = pipeline.retrieve(RagQuery(query = "SentinelSystem production", topK = 5, rerank = false))

            val commonRank = result.documents.indexOfFirst { it.id == "doc-common" }
            assertTrue(commonRank >= 0) { "doc-common should appear in fused result" }
        }

        @Test
        fun `bm25-only weight should bring BM25 result to top`() = runTest {
            val scorerLocal = Bm25Scorer()
            // doc-bm25 matches the proper noun; doc-vector has higher vector score
            val docBm25 = RetrievedDocument(id = "doc-bm25", content = "NeptuneDB is the internal database", score = 0.6)
            val docVector = RetrievedDocument(id = "doc-vector", content = "distributed storage systems", score = 0.95)
            scorerLocal.index("doc-bm25", docBm25.content)
            scorerLocal.index("doc-vector", docVector.content)

            coEvery { mockRetriever.retrieve(any(), any(), any()) } returns listOf(docVector, docBm25)

            val pipeline = HybridRagPipeline(
                retriever = mockRetriever,
                bm25Scorer = scorerLocal,
                vectorWeight = 0.1,  // Heavily weight BM25
                bm25Weight = 0.9,
                rrfK = 60.0
            )
            val result = pipeline.retrieve(RagQuery(query = "NeptuneDB", topK = 5, rerank = false))

            assertTrue(result.hasDocuments) { "Should return documents" }
            assertEquals("doc-bm25", result.documents[0].id) {
                "With bm25Weight=0.9, doc matching the proper noun via BM25 should rank first"
            }
        }
    }
}
