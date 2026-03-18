package com.arc.reactor.rag.integration

import com.arc.reactor.rag.chunking.TokenBasedDocumentChunker
import com.arc.reactor.rag.impl.DefaultRagPipeline
import com.arc.reactor.rag.impl.InMemoryDocumentRetriever
import com.arc.reactor.rag.model.RagQuery
import com.arc.reactor.rag.model.RetrievedDocument
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.ai.document.Document
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * RAG 문서 수집(ingestion) 통합 테스트.
 *
 * 문서 청킹 -> PGVector 테이블 저장 -> 검색 파이프라인의
 * 전체 흐름을 실제 PostgreSQL에서 검증한다.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagIngestionIntegrationTest {

    private lateinit var jdbc: JdbcTemplate
    private val table = "vector_store_ingest_integ_test"
    private val objectMapper = jacksonObjectMapper()

    @BeforeAll
    fun checkDbAndSetup() {
        val (dbOk, pgvOk) = PgVectorTestSupport.checkPrerequisites()
        assumeTrue(dbOk, "PostgreSQL not available, skipping integration test")
        assumeTrue(pgvOk, "pgvector extension not installed, skipping integration test")

        jdbc = PgVectorTestSupport.createJdbcTemplate()
        PgVectorTestSupport.createTestTable(jdbc, table)
    }

    @BeforeEach
    fun cleanTable() {
        if (::jdbc.isInitialized) {
            jdbc.execute("TRUNCATE TABLE $table")
        }
    }

    @AfterAll
    fun tearDown() {
        if (::jdbc.isInitialized) {
            PgVectorTestSupport.dropTestTable(jdbc, table)
        }
    }

    /**
     * 청크된 문서를 벡터 테이블에 저장하는 헬퍼.
     * 실제 EmbeddingModel 없이 더미 임베딩을 할당한다.
     */
    private fun storeChunkedDocuments(chunks: List<Document>) {
        for ((index, chunk) in chunks.withIndex()) {
            val id = try {
                UUID.fromString(chunk.id).toString()
            } catch (e: IllegalArgumentException) {
                UUID.nameUUIDFromBytes(chunk.id.toByteArray()).toString()
            }

            val metadata = objectMapper.writeValueAsString(chunk.metadata)
            val variation = index * 0.05
            val embedding = "[${1.0 - variation},${variation},0]"

            PgVectorTestSupport.insertDocument(jdbc, table, id, chunk.text.orEmpty(), embedding, metadata)
        }
    }

    @Test
    fun `문서 청킹 후 PGVector에 저장하면 모든 청크가 조회되어야 한다`() {
        val longContent = buildLongContent(50)
        val document = Document(UUID.randomUUID().toString(), longContent, mapOf("source" to "test"))
        val chunker = TokenBasedDocumentChunker(chunkSize = 128, overlap = 20, minChunkThreshold = 100)
        val chunks = chunker.chunk(document)

        assertTrue(chunks.size > 1, "Long document should be split into multiple chunks, got ${chunks.size}")

        storeChunkedDocuments(chunks)

        val storedCount = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)
        assertEquals(
            chunks.size.toLong(), storedCount,
            "All ${chunks.size} chunks should be stored in the DB"
        )
    }

    @Test
    fun `청크 메타데이터에 parent_document_id와 chunk_index가 포함되어야 한다`() {
        val longContent = buildLongContent(30)
        val parentId = UUID.randomUUID().toString()
        val document = Document(parentId, longContent, mapOf("source" to "spring-docs"))
        val chunker = TokenBasedDocumentChunker(chunkSize = 128, overlap = 20, minChunkThreshold = 100)
        val chunks = chunker.chunk(document)

        assertTrue(chunks.size > 1, "Should produce multiple chunks")

        val firstChunk = chunks[0]
        assertEquals(parentId, firstChunk.metadata["parent_document_id"], "Chunk should have parent_document_id")
        assertEquals(0, firstChunk.metadata["chunk_index"], "First chunk should have chunk_index=0")
        assertEquals(chunks.size, firstChunk.metadata["chunk_total"], "chunk_total should equal total chunks")
    }

    @Test
    fun `저장된 청크를 InMemoryDocumentRetriever로 검색할 수 있어야 한다`() = runTest {
        val longContent = buildLongContent(20)
        val document = Document(UUID.randomUUID().toString(), longContent, mapOf("source" to "kotlin-docs"))
        val chunker = TokenBasedDocumentChunker(chunkSize = 128, overlap = 20, minChunkThreshold = 100)
        val chunks = chunker.chunk(document)

        storeChunkedDocuments(chunks)

        val storedDocs = PgVectorTestSupport.vectorSearch(jdbc, table, "[1,0,0]", chunks.size)
        val retriever = InMemoryDocumentRetriever()
        retriever.addDocuments(storedDocs)

        val pipeline = DefaultRagPipeline(retriever = retriever, maxContextTokens = 4000)
        val result = pipeline.retrieve(
            RagQuery(query = "kotlin coroutines", topK = 5, rerank = false)
        )

        assertTrue(result.hasDocuments, "Should retrieve stored chunks via pipeline")
        assertTrue(
            result.context.contains("coroutines") || result.context.contains("Kotlin"),
            "Retrieved context should contain relevant content"
        )
    }

    @Test
    fun `짧은 문서는 청킹 없이 단일 문서로 저장되어야 한다`() {
        val shortContent = "Short FAQ: How to reset password? Go to Settings > Account > Reset."
        val document = Document(UUID.randomUUID().toString(), shortContent, mapOf("source" to "faq"))
        val chunker = TokenBasedDocumentChunker(chunkSize = 512, minChunkThreshold = 512)
        val chunks = chunker.chunk(document)

        assertEquals(1, chunks.size, "Short document should not be chunked")
        assertEquals(shortContent, chunks[0].text, "Content should remain unchanged")

        storeChunkedDocuments(chunks)

        val storedCount = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)
        assertEquals(1L, storedCount, "Single document should be stored as one row")
    }

    @Test
    fun `문서 삭제 후 검색 결과에서 제거되어야 한다`() {
        val id = UUID.randomUUID().toString()
        PgVectorTestSupport.insertDocument(jdbc, table, id, "Temporary document to be deleted", "[1,0,0]")

        val beforeCount = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)
        assertEquals(1L, beforeCount, "Document should exist before deletion")

        jdbc.update("DELETE FROM $table WHERE id = ?::uuid", id)

        val afterCount = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)
        assertEquals(0L, afterCount, "Document should be removed after deletion")
    }

    @Test
    fun `동일 ID로 문서를 덮어쓰면 내용이 갱신되어야 한다`() {
        val id = UUID.randomUUID().toString()
        PgVectorTestSupport.insertDocument(jdbc, table, id, "Original content version 1", "[1,0,0]")

        jdbc.update(
            """
            INSERT INTO $table (id, content, metadata, embedding)
            VALUES (?::uuid, ?, ?::jsonb, ?::vector)
            ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, embedding = EXCLUDED.embedding
            """.trimIndent(),
            id, "Updated content version 2", "{}", "[0.9,0.1,0]"
        )

        val content = jdbc.queryForObject(
            "SELECT content FROM $table WHERE id = ?::uuid",
            String::class.java,
            id
        )
        assertEquals("Updated content version 2", content, "Content should be updated via upsert")

        val totalCount = jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java)
        assertEquals(1L, totalCount, "Upsert should not create duplicate rows")
    }

    /** 테스트용 장문 콘텐츠를 생성한다. */
    private fun buildLongContent(paragraphs: Int): String = buildString {
        repeat(paragraphs) { i ->
            append("Topic $i: Kotlin coroutines provide structured concurrency. ")
            append("They enable non-blocking asynchronous programming patterns. ")
            append("\n\n")
        }
    }
}
