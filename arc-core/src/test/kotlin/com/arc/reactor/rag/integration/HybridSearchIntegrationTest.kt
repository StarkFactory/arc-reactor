package com.arc.reactor.rag.integration

import com.arc.reactor.rag.impl.DefaultRagPipeline
import com.arc.reactor.rag.impl.HybridRagPipeline
import com.arc.reactor.rag.impl.InMemoryDocumentRetriever
import com.arc.reactor.rag.model.RagContext
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.search.Bm25Scorer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * BM25 + Vector 하이브리드 검색 통합 테스트.
 *
 * 실제 PostgreSQL 벡터 테이블과 인메모리 BM25 스코어러를 결합하여
 * HybridRagPipeline의 RRF 융합이 올바르게 동작하는지 검증한다.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HybridSearchIntegrationTest {

    private lateinit var jdbc: JdbcTemplate
    private lateinit var bm25Scorer: Bm25Scorer
    private val table = "vector_store_hybrid_integ_test"

    @BeforeAll
    fun checkDbAndSetup() {
        val (dbOk, pgvOk) = PgVectorTestSupport.checkPrerequisites()
        assumeTrue(dbOk, "PostgreSQL not available, skipping integration test")
        assumeTrue(pgvOk, "pgvector extension not installed, skipping integration test")

        jdbc = PgVectorTestSupport.createJdbcTemplate()
        PgVectorTestSupport.createTestTable(jdbc, table)
    }

    @BeforeEach
    fun resetState() {
        if (::jdbc.isInitialized) {
            jdbc.execute("TRUNCATE TABLE $table")
        }
        bm25Scorer = Bm25Scorer()
    }

    @AfterAll
    fun tearDown() {
        if (::jdbc.isInitialized) {
            PgVectorTestSupport.dropTestTable(jdbc, table)
        }
    }

    /** 문서를 PGVector 테이블과 BM25 인덱스에 동시에 추가한다. */
    private fun indexDocument(id: String, content: String, embedding: String) {
        PgVectorTestSupport.insertDocument(jdbc, table, id, content, embedding)
        bm25Scorer.index(id, content)
    }

    /** PGVector 검색 결과를 InMemoryDocumentRetriever로 감싸서 반환한다. */
    private fun retrieverFromVectorSearch(queryEmbedding: String, limit: Int): InMemoryDocumentRetriever {
        val results = PgVectorTestSupport.vectorSearch(jdbc, table, queryEmbedding, limit)
        val retriever = InMemoryDocumentRetriever()
        retriever.addDocuments(results)
        return retriever
    }

    @Test
    fun `벡터 검색과 BM25 모두 결과를 반환할 때 RRF 융합이 동작해야 한다`() = runTest {
        val id1 = UUID.randomUUID().toString()
        val id2 = UUID.randomUUID().toString()
        val id3 = UUID.randomUUID().toString()

        indexDocument(id1, "ProjectAlpha data pipeline system", "[1,0,0]")
        indexDocument(id2, "generic data processing framework", "[0.9,0.1,0]")
        indexDocument(id3, "ProjectAlpha configuration guide", "[0,1,0]")

        val pipeline = HybridRagPipeline(
            retriever = retrieverFromVectorSearch("[1,0,0]", 10),
            bm25Scorer = bm25Scorer,
            vectorWeight = 0.5,
            bm25Weight = 0.5
        )

        val result = pipeline.retrieve(
            RagQuery(query = "ProjectAlpha data", topK = 5, rerank = false)
        )

        assertTrue(result.hasDocuments, "RRF fusion should return documents")
        assertTrue(
            id1 in result.documents.map { it.id },
            "Document matching both signals (id1) should appear in results"
        )
    }

    @Test
    fun `고유명사 쿼리에서 BM25가 벡터 검색 약점을 보완해야 한다`() = runTest {
        val id1 = UUID.randomUUID().toString()
        val id2 = UUID.randomUUID().toString()

        indexDocument(id1, "distributed systems monitoring and alerting", "[1,0,0]")
        indexDocument(id2, "NeptuneDB internal database administration", "[0,0.8,0.2]")

        val pipeline = HybridRagPipeline(
            retriever = retrieverFromVectorSearch("[1,0,0]", 10),
            bm25Scorer = bm25Scorer,
            vectorWeight = 0.3,
            bm25Weight = 0.7
        )

        val result = pipeline.retrieve(
            RagQuery(query = "NeptuneDB", topK = 5, rerank = false)
        )

        assertTrue(result.hasDocuments, "Should return documents for proper noun query")
        assertTrue(
            id2 in result.documents.map { it.id },
            "BM25 should surface NeptuneDB document despite low vector similarity"
        )
    }

    @Test
    fun `벡터 테이블과 BM25 인덱스 모두 비어있으면 빈 결과를 반환해야 한다`() = runTest {
        val pipeline = HybridRagPipeline(
            retriever = InMemoryDocumentRetriever(),
            bm25Scorer = Bm25Scorer()
        )

        val result = pipeline.retrieve(RagQuery(query = "nothing", topK = 5))

        assertFalse(result.hasDocuments, "Empty sources should return empty context")
        assertEquals(RagContext.EMPTY, result, "Result should equal RagContext.EMPTY")
    }

    @Test
    fun `DefaultRagPipeline이 PGVector에서 가져온 문서로 컨텍스트를 빌드해야 한다`() = runTest {
        val id1 = UUID.randomUUID().toString()
        val id2 = UUID.randomUUID().toString()

        PgVectorTestSupport.insertDocument(jdbc, table, id1, "Spring Boot auto-configuration mechanism explained", "[1,0,0]")
        PgVectorTestSupport.insertDocument(jdbc, table, id2, "Kubernetes pod scheduling overview", "[0,1,0]")

        val pipeline = DefaultRagPipeline(
            retriever = retrieverFromVectorSearch("[1,0,0]", 5),
            maxContextTokens = 4000
        )

        val result = pipeline.retrieve(
            RagQuery(query = "Spring Boot", topK = 5, rerank = false)
        )

        assertTrue(result.hasDocuments, "Pipeline should return documents from PGVector-backed search")
        assertTrue(
            result.context.contains("Spring Boot"),
            "Context should contain document content, got: ${result.context.take(200)}"
        )
    }

    @Test
    fun `topK 제한이 RRF 융합 결과에 적용되어야 한다`() = runTest {
        val ids = (1..10).map { UUID.randomUUID().toString() }
        for (i in ids.indices) {
            val embedding = "[${1.0 - i * 0.1},${i * 0.1},0]"
            indexDocument(ids[i], "Document number $i about the platform", embedding)
        }

        val pipeline = HybridRagPipeline(
            retriever = retrieverFromVectorSearch("[1,0,0]", 10),
            bm25Scorer = bm25Scorer,
            vectorWeight = 0.5,
            bm25Weight = 0.5
        )

        val result = pipeline.retrieve(
            RagQuery(query = "platform Document", topK = 3, rerank = false)
        )

        assertTrue(
            result.documents.size <= 3,
            "topK=3 should limit results to at most 3 documents, got ${result.documents.size}"
        )
    }
}
