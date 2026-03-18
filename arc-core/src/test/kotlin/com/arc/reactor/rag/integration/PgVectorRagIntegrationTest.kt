package com.arc.reactor.rag.integration

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * PGVector + RAG 파이프라인 E2E 통합 테스트.
 *
 * 실제 PostgreSQL + PGVector 확장을 사용하여
 * 벡터 검색, 유사도 정렬, 메타데이터 필터링을 검증한다.
 *
 * 사전 조건: PostgreSQL 14+ (localhost:5432, arcreactor, arc/arc), pgvector 확장 설치 완료.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgVectorRagIntegrationTest {

    private lateinit var jdbc: JdbcTemplate
    private val table = "vector_store_rag_integ_test"

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

    private fun insert(id: UUID, content: String, embedding: String, metadata: String = "{}") {
        PgVectorTestSupport.insertDocument(jdbc, table, id.toString(), content, embedding, metadata)
    }

    @Test
    fun `pgvector 확장이 활성화되어 있어야 한다`() {
        val result = jdbc.queryForObject(
            "SELECT extname FROM pg_extension WHERE extname = 'vector'",
            String::class.java
        )
        assertEquals("vector", result, "pgvector extension should be installed")
    }

    @Test
    fun `벡터 삽입 및 코사인 유사도 검색이 동작해야 한다`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val id3 = UUID.randomUUID()

        insert(id1, "Kotlin coroutines guide", "[1,0,0]", """{"source":"docs"}""")
        insert(id2, "Java threading tutorial", "[0,1,0]", """{"source":"blog"}""")
        insert(id3, "Kotlin async programming", "[0.9,0.1,0]", """{"source":"docs"}""")

        val results = PgVectorTestSupport.vectorSearch(jdbc, table, "[1,0,0]", 2)

        assertEquals(2, results.size, "Should return exactly 2 results")
        assertEquals(
            id1.toString(), results[0].id,
            "First result should be the most similar document (id1)"
        )
        assertTrue(results[0].score > 0.9, "Cosine similarity for exact match should be > 0.9, got: ${results[0].score}")
    }

    @Test
    fun `벡터 삽입 후 SQL 유사도 검색으로 문서를 조회할 수 있어야 한다`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()

        insert(id1, "Spring Boot auto-configuration guide", "[1,0,0]", """{"source":"spring-docs"}""")
        insert(id2, "Docker container basics", "[0,1,0]", """{"source":"devops"}""")

        val results = PgVectorTestSupport.vectorSearch(jdbc, table, "[1,0,0]", 1)

        assertEquals(1, results.size, "Should return 1 result")
        assertEquals(id1.toString(), results[0].id, "Closest document to [1,0,0] should be id1")
        assertTrue(
            results[0].content.contains("Spring Boot"),
            "Retrieved content should match the stored document"
        )
    }

    @Test
    fun `메타데이터 필터링이 SQL 레벨에서 동작해야 한다`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val id3 = UUID.randomUUID()

        insert(id1, "Kotlin guide", "[1,0,0]", """{"source":"docs","category":"language"}""")
        insert(id2, "Java guide", "[0,1,0]", """{"source":"docs","category":"language"}""")
        insert(id3, "Docker tutorial", "[0,0,1]", """{"source":"blog","category":"devops"}""")

        val results = jdbc.queryForList(
            """
            SELECT id, content FROM $table
            WHERE metadata->>'source' = 'docs'
            ORDER BY embedding <=> '[1,0,0]'::vector
            """.trimIndent()
        )

        assertEquals(2, results.size, "Metadata filter should return 2 docs with source=docs")
        assertTrue(
            results.none { it["content"].toString().contains("Docker") },
            "Filtered results should not contain Docker document"
        )
    }

    @Test
    fun `유사도 순위가 올바르게 정렬되어야 한다`() {
        val ids = (1..5).map { UUID.randomUUID() }
        val embeddings = listOf(
            "[1,0,0]", "[0.95,0.05,0]", "[0.7,0.3,0]", "[0.3,0.7,0]", "[0,1,0]"
        )

        for (i in ids.indices) {
            insert(ids[i], "Document $i", embeddings[i], """{"rank":$i}""")
        }

        val results = PgVectorTestSupport.vectorSearch(jdbc, table, "[1,0,0]", 5)

        assertEquals(5, results.size, "Should return all 5 documents")
        val similarities = results.map { it.score }
        for (i in 0 until similarities.size - 1) {
            assertTrue(
                similarities[i] >= similarities[i + 1],
                "Similarity should be descending: index $i (${similarities[i]}) >= index ${i + 1} (${similarities[i + 1]})"
            )
        }
    }

    @Test
    fun `빈 테이블에서 검색하면 빈 결과를 반환해야 한다`() {
        val results = PgVectorTestSupport.vectorSearch(jdbc, table, "[1,0,0]", 10)
        assertTrue(results.isEmpty(), "Empty table should return empty results")
    }

    @Test
    fun `JSONB 메타데이터에 중첩 필드를 저장하고 조회할 수 있어야 한다`() {
        val id = UUID.randomUUID()
        val metadata = """{"source":"docs","tags":["kotlin","spring"],"version":3}"""

        insert(id, "Complex metadata document", "[1,0,0]", metadata)

        val hasKotlinTag = jdbc.queryForObject(
            "SELECT count(*) FROM $table WHERE metadata->'tags' @> '\"kotlin\"'::jsonb",
            Long::class.java
        )
        assertEquals(1L, hasKotlinTag, "JSONB array should contain 'kotlin' tag")

        val version = jdbc.queryForObject(
            "SELECT metadata->>'version' FROM $table WHERE id = ?::uuid",
            String::class.java,
            id.toString()
        )
        assertEquals("3", version, "JSONB number field should be queryable")
    }
}
