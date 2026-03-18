package com.arc.reactor.integration

import com.arc.reactor.ArcReactorApplication
import com.arc.reactor.auth.JwtTokenProvider
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserRole
import com.arc.reactor.rag.chunking.DocumentChunker
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.Filter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

@SpringBootTest(
    classes = [
        ArcReactorApplication::class,
        IntegrationTestAgentExecutorConfig::class,
        DocumentChunkingIntegrationTest.ChunkingTestConfig::class
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:chunkingdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql",
        "spring.ai.google.genai.api-key=test-key",
        "spring.ai.google.genai.embedding.api-key=test-key",
        "arc.reactor.rag.enabled=true",
        "arc.reactor.rag.chunking.enabled=true",
        "arc.reactor.rag.chunking.chunk-size=512",
        "arc.reactor.rag.chunking.overlap=50",
        "arc.reactor.auth.jwt-secret=integration-test-jwt-secret-32-bytes",
        "arc.reactor.postgres.required=false"
    ]
)
@AutoConfigureWebTestClient
@ActiveProfiles("document-chunking-test")
@Tag("integration")
/**
 * 문서 청킹 통합 테스트.
 *
 * 문서 청킹 파이프라인의 E2E 동작을 검증합니다.
 */
class DocumentChunkingIntegrationTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    @Autowired
    lateinit var vectorStore: VectorStore

    lateinit var adminClient: WebTestClient

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("DELETE FROM users")
        jdbcTemplate.update(
            "INSERT INTO users (id, email, name, password_hash, role, created_at) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
            "chunking-test-admin",
            "chunking-admin@arc.dev",
            "Chunking Test Admin",
            "ignored",
            "ADMIN"
        )
        if (vectorStore is ChunkingVectorStore) {
            (vectorStore as ChunkingVectorStore).clear()
        }
        val adminToken = jwtTokenProvider.createToken(adminUser())
        adminClient = webTestClient.mutate()
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $adminToken")
            .build()
    }

    @Test
    fun `long document은(는) chunked and stored as multiple entries이다`() {
        val longContent = buildLongContent(6000)

        val response = exchangeJson(
            adminClient.post()
                .uri("/api/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("content" to longContent, "metadata" to mapOf("source" to "test"))),
            expectedStatus = 201
        )

        val chunkCount = response["chunkCount"].asInt(0)
        assertTrue(chunkCount > 1, "Long document should be split into multiple chunks, got: $chunkCount")

        val chunkIds = response["chunkIds"].map { it.asText() }
        assertEquals(chunkCount, chunkIds.size, "chunkIds count should match chunkCount")

        // chunks are individually stored in VectorStore 확인
        val store = vectorStore as ChunkingVectorStore
        for (chunkId in chunkIds) {
            assertTrue(store.contains(chunkId), "VectorStore should contain chunk: $chunkId")
        }
    }

    @Test
    fun `short document은(는) not chunked이다`() {
        val shortContent = "This is a short document for testing purposes."

        val response = exchangeJson(
            adminClient.post()
                .uri("/api/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("content" to shortContent)),
            expectedStatus = 201
        )

        val chunkCount = response["chunkCount"].asInt(0)
        assertEquals(1, chunkCount, "Short document should not be chunked")
    }

    @Test
    fun `search은(는) returns chunks with parent metadata`() {
        val longContent = buildLongContent(6000)

        val addResponse = exchangeJson(
            adminClient.post()
                .uri("/api/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("content" to longContent)),
            expectedStatus = 201
        )
        val parentId = addResponse["id"].asText()

        val searchResults = exchangeJson(
            adminClient.post()
                .uri("/api/documents/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("query" to "artificial intelligence machine learning", "topK" to 5)),
            expectedStatus = 200
        )

        assertTrue(searchResults.isArray, "Search should return an array")
        assertFalse(searchResults.isEmpty, "Search should return at least one result")

        val firstResult = searchResults.first()
        assertEquals(
            parentId, firstResult["metadata"]["parent_document_id"]?.asText(),
            "Search result should include parent_document_id in metadata"
        )
    }

    @Test
    fun `with parent ID removes all chunks를 삭제한다`() {
        val longContent = buildLongContent(6000)

        val addResponse = exchangeJson(
            adminClient.post()
                .uri("/api/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("content" to longContent)),
            expectedStatus = 201
        )
        val parentId = addResponse["id"].asText()
        val chunkIds = addResponse["chunkIds"].map { it.asText() }
        assertTrue(chunkIds.size > 1, "Should have multiple chunks to test deletion")

        // using parent ID 삭제
        adminClient.method(org.springframework.http.HttpMethod.DELETE)
            .uri("/api/documents")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("ids" to listOf(parentId)))
            .exchange()
            .expectStatus().isNoContent

        // all chunks are removed 확인
        val store = vectorStore as ChunkingVectorStore
        for (chunkId in chunkIds) {
            assertFalse(store.contains(chunkId), "Chunk $chunkId should be deleted after parent deletion")
        }
    }

    @Test
    fun `배치 add chunks each document independently`() {
        val shortContent = "Short document that should not be split."
        val longContent = buildLongContent(6000)

        exchangeJson(
            adminClient.post()
                .uri("/api/documents/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    mapOf(
                        "documents" to listOf(
                            mapOf("content" to shortContent),
                            mapOf("content" to longContent)
                        )
                    )
                ),
            expectedStatus = 201
        )

        // VectorStore은(는) have more than 2 entries (1 short + N long chunks)해야 합니다
        val store = vectorStore as ChunkingVectorStore
        assertTrue(
            store.size() > 2,
            "Batch should produce more than 2 stored entries (1 short + multiple chunks), got: ${store.size()}"
        )

        // At least one entry은(는) have chunking metadata해야 합니다
        val chunkedEntries = store.allDocs().filter { it.metadata["chunked"] == true }
        assertTrue(
            chunkedEntries.isNotEmpty(),
            "At least one entry should have chunked=true metadata"
        )
    }

    private fun exchangeJson(spec: WebTestClient.RequestHeadersSpec<*>, expectedStatus: Int): JsonNode {
        val response = spec.exchange()
            .expectStatus().value { actual ->
                assertEquals(expectedStatus, actual) {
                    "Unexpected HTTP status. expected=$expectedStatus actual=$actual"
                }
            }
            .expectBody()
            .returnResult()
            .responseBody

        val bytes = requireNotNull(response) { "Response body should not be null" }
        return objectMapper.readTree(bytes)
    }

    private fun adminUser() = User(
        id = "chunking-test-admin",
        email = "chunking-admin@arc.dev",
        name = "Chunking Test Admin",
        passwordHash = "ignored",
        role = UserRole.ADMIN
    )

    private fun buildLongContent(targetLength: Int): String {
        val paragraph = "This is a paragraph about artificial intelligence and machine learning. " +
            "It covers topics such as neural networks, deep learning, and natural language processing. " +
            "These technologies are transforming industries across the globe.\n\n"
        return buildString {
            while (length < targetLength) append(paragraph)
        }.take(targetLength)
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Profile("document-chunking-test")
    class ChunkingTestConfig {
        @Bean
        fun chunkingVectorStore(): VectorStore = ChunkingVectorStore()
    }

    class ChunkingVectorStore : VectorStore {
        private val docs = ConcurrentHashMap<String, Document>()

        override fun add(documents: List<Document>) {
            for (document in documents) {
                docs[document.id] = document
            }
        }

        override fun similaritySearch(request: SearchRequest): List<Document> {
            val normalizedQuery = request.query.lowercase().trim()
            val topK = request.topK
            return docs.values
                .map { doc -> doc to score(doc, normalizedQuery) }
                .filter { (_, score) -> score > 0.0 }
                .sortedByDescending { (_, score) -> score }
                .take(topK)
                .map { (doc, score) ->
                    val metadata = doc.metadata.toMutableMap()
                    metadata["distance"] = 1.0 - score
                    Document(doc.id, doc.text ?: "", metadata)
                }
        }

        override fun delete(ids: List<String>) {
            for (id in ids) {
                docs.remove(id)
            }
        }

        override fun delete(filterExpression: Filter.Expression) {
            docs.clear()
        }

        fun clear() {
            docs.clear()
        }

        fun contains(id: String): Boolean = docs.containsKey(id)

        fun size(): Int = docs.size

        fun allDocs(): Collection<Document> = docs.values

        private fun score(document: Document, normalizedQuery: String): Double {
            if (normalizedQuery.isBlank()) return 0.0
            val content = (document.text ?: "").lowercase()
            if (content.isBlank()) return 0.0
            if (content.contains(normalizedQuery)) return 1.0
            val queryTokens = normalizedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (queryTokens.isEmpty()) return 0.0
            val matched = queryTokens.count { token -> content.contains(token) }
            return min(1.0, max(0.0, matched.toDouble() / queryTokens.size.toDouble()))
        }
    }
}
