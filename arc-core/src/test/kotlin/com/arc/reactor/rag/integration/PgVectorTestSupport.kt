package com.arc.reactor.rag.integration

import com.arc.reactor.rag.model.RetrievedDocument
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

/**
 * PGVector 통합 테스트 공통 유틸리티.
 *
 * DB 연결 확인, 테이블 생성/삭제, 문서 삽입/검색 헬퍼를 제공한다.
 */
object PgVectorTestSupport {

    const val DB_URL = "jdbc:postgresql://localhost:5432/arcreactor"
    const val DB_USER = "arc"
    const val DB_PASS = "arc"
    const val DIMENSIONS = 3

    private val INSERT_SQL =
        "INSERT INTO %s (id, content, metadata, embedding) VALUES (?::uuid, ?, ?::jsonb, ?::vector)"

    /** DB 연결 가능 여부와 pgvector 확장 설치 여부를 한 번에 확인한다. */
    fun checkPrerequisites(): Pair<Boolean, Boolean> {
        return try {
            val ds = DriverManagerDataSource(DB_URL, DB_USER, DB_PASS)
            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("SELECT 1")
                }
                val hasPgVector = conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery(
                        "SELECT 1 FROM pg_extension WHERE extname = 'vector'"
                    )
                    rs.next()
                }
                true to hasPgVector
            }
        } catch (e: Exception) {
            false to false
        }
    }

    /** 새 JdbcTemplate을 생성한다. */
    fun createJdbcTemplate(): JdbcTemplate {
        return JdbcTemplate(DriverManagerDataSource(DB_URL, DB_USER, DB_PASS))
    }

    /** 테스트 전용 벡터 테이블을 생성한다. */
    fun createTestTable(jdbc: JdbcTemplate, tableName: String) {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector")
        jdbc.execute("DROP TABLE IF EXISTS $tableName")
        jdbc.execute(
            """
            CREATE TABLE $tableName (
                id UUID PRIMARY KEY,
                content TEXT,
                metadata JSONB,
                embedding vector($DIMENSIONS)
            )
            """.trimIndent()
        )
    }

    /** 테스트 전용 벡터 테이블을 삭제한다. */
    fun dropTestTable(jdbc: JdbcTemplate, tableName: String) {
        jdbc.execute("DROP TABLE IF EXISTS $tableName")
    }

    /** 단일 문서를 벡터 테이블에 삽입한다. */
    fun insertDocument(
        jdbc: JdbcTemplate,
        tableName: String,
        id: String,
        content: String,
        embedding: String,
        metadata: String = "{}"
    ) {
        jdbc.update(
            INSERT_SQL.format(tableName),
            id, content, metadata, embedding
        )
    }

    /** 코사인 유사도 기반 벡터 검색을 수행한다. */
    fun vectorSearch(
        jdbc: JdbcTemplate,
        tableName: String,
        queryEmbedding: String,
        limit: Int
    ): List<RetrievedDocument> {
        val results = jdbc.queryForList(
            """
            SELECT id, content, 1 - (embedding <=> ?::vector) AS similarity
            FROM $tableName
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """.trimIndent(),
            queryEmbedding, queryEmbedding, limit
        )
        return results.map { row ->
            RetrievedDocument(
                id = row["id"].toString(),
                content = row["content"].toString(),
                score = (row["similarity"] as Number).toDouble()
            )
        }
    }
}
