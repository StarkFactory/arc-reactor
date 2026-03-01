package com.arc.reactor.integration

import com.arc.reactor.ArcReactorApplication
import com.arc.reactor.agent.AgentExecutor
import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.agent.model.AgentResult
import com.arc.reactor.agent.model.StreamEventMarker
import com.arc.reactor.auth.JwtTokenProvider
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserRole
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

@SpringBootTest(
    classes = [ArcReactorApplication::class, ApiRegressionFlowIntegrationTest.RegressionTestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:apiregressiondb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=" +
            "classpath:test-schema.sql,classpath:db/migration/V3__create_users.sql," +
            "classpath:db/migration/V6__add_user_role.sql",
        "spring.ai.google.genai.api-key=test-key",
        "spring.ai.google.genai.embedding.api-key=test-key",
        "arc.reactor.rag.enabled=true",
        "arc.reactor.output-guard.enabled=true",
        "arc.reactor.output-guard.dynamic-rules-enabled=true",
        "arc.reactor.output-guard.dynamic-rules-refresh-ms=600000",
        "arc.reactor.auth.jwt-secret=integration-test-jwt-secret-32-bytes",
        "arc.reactor.postgres.required=false"
    ]
)
@AutoConfigureWebTestClient
@Tag("integration")
class ApiRegressionFlowIntegrationTest {

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
        jdbcTemplate.update("DELETE FROM output_guard_rule_audits")
        jdbcTemplate.update("DELETE FROM output_guard_rules")
        jdbcTemplate.update("DELETE FROM admin_audits")
        jdbcTemplate.update("DELETE FROM mcp_servers")
        jdbcTemplate.update("DELETE FROM users")

        if (vectorStore is RegressionVectorStore) {
            (vectorStore as RegressionVectorStore).clear()
        }

        val adminToken = jwtTokenProvider.createToken(adminUser())
        adminClient = webTestClient.mutate()
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $adminToken")
            .build()
    }

    @Test
    fun `covers auth chat stream mcp guard and rag API regression flow`() {
        val email = "qa-${UUID.randomUUID()}@example.com"
        val password = "passw0rd!"

        val registerJson = exchangeJson(
            webTestClient.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    mapOf(
                        "email" to email,
                        "password" to password,
                        "name" to "QA User"
                    )
                ),
            expectedStatus = 201
        )
        val registerToken = registerJson["token"].asText("")
        assertTrue(registerToken.isNotBlank()) {
            "Register response should include a JWT token"
        }

        val loginJson = exchangeJson(
            webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    mapOf(
                        "email" to email,
                        "password" to password
                    )
                ),
            expectedStatus = 200
        )
        val userToken = loginJson["token"].asText("")
        assertTrue(userToken.isNotBlank()) {
            "Login response should include a JWT token"
        }

        val userClient = webTestClient.mutate()
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $userToken")
            .build()

        val chatJson = exchangeJson(
            userClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("message" to "Please run an MCP echo check")),
            expectedStatus = 200
        )
        assertTrue(chatJson["success"].asBoolean(false)) {
            "Chat endpoint should return success=true for a valid request"
        }
        val toolsUsed = chatJson["toolsUsed"].map { it.asText() }
        assertTrue(toolsUsed.contains("mock_mcp_echo")) {
            "Chat regression flow should report MCP tool usage in toolsUsed"
        }

        val tenantMismatchJson = exchangeJson(
            userClient.post()
                .uri("/api/chat")
                .header("X-Tenant-Id", "tenant-mismatch")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("message" to "hello")),
            expectedStatus = 400
        )
        assertTrue(tenantMismatchJson["error"].asText("").contains("Tenant header does not match")) {
            "Tenant mismatch should fail-close with a clear 400 error"
        }

        val streamBody = userClient.post()
            .uri("/api/chat/stream")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(mapOf("message" to "stream MCP check"))
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .returnResult()
            .responseBody
            .orEmpty()
        assertTrue(streamBody.contains("tool_start")) {
            "Streaming response should contain tool_start marker for MCP scenario"
        }
        assertTrue(streamBody.contains("tool_end")) {
            "Streaming response should contain tool_end marker for MCP scenario"
        }
        assertTrue(streamBody.contains("done")) {
            "Streaming response should terminate with a done event"
        }

        exchangeJson(
            adminClient.post()
                .uri("/api/mcp/servers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    mapOf(
                        "name" to "qa-mcp-server",
                        "transportType" to "STDIO",
                        "config" to mapOf(
                            "command" to "echo",
                            "args" to listOf("ok")
                        ),
                        "autoConnect" to false
                    )
                ),
            expectedStatus = 201
        )
        val mcpListJson = exchangeJson(adminClient.get().uri("/api/mcp/servers"), expectedStatus = 200)
        val mcpNames = mcpListJson.map { it["name"].asText("") }
        assertTrue(mcpNames.contains("qa-mcp-server")) {
            "MCP list endpoint should include the registered server"
        }

        exchangeJson(
            adminClient.post()
                .uri("/api/output-guard/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    mapOf(
                        "name" to "Block forbidden token",
                        "pattern" to "(?i)forbidden",
                        "action" to "REJECT",
                        "priority" to 1,
                        "enabled" to true
                    )
                ),
            expectedStatus = 201
        )
        val simulatedGuard = exchangeJson(
            adminClient.post()
                .uri("/api/output-guard/rules/simulate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    mapOf(
                        "content" to "This contains forbidden content",
                        "includeDisabled" to false
                    )
                ),
            expectedStatus = 200
        )
        assertTrue(simulatedGuard["blocked"].asBoolean(false)) {
            "Output guard simulation should block content matching REJECT rule"
        }

        exchangeJson(
            adminClient.post()
                .uri("/api/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    mapOf(
                        "content" to "Arc Reactor supports tenant-aware RAG search.",
                        "metadata" to mapOf("source" to "qa")
                    )
                ),
            expectedStatus = 201
        )

        val searchJson = exchangeJson(
            userClient.post()
                .uri("/api/documents/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    mapOf(
                        "query" to "tenant-aware rag",
                        "topK" to 3
                    )
                ),
            expectedStatus = 200
        )
        assertTrue(searchJson.isArray) {
            "Document search should return an array response"
        }
        assertFalse(searchJson.isEmpty) {
            "RAG search should return at least one matching document"
        }
        assertTrue(searchJson.first()["content"].asText("").contains("tenant-aware")) {
            "Top RAG search result should contain expected content"
        }
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
        id = "api-regression-admin",
        email = "api-regression-admin@arc.dev",
        name = "API Regression Admin",
        passwordHash = "ignored",
        role = UserRole.ADMIN
    )

    @TestConfiguration
    class RegressionTestConfig {
        @Bean
        fun regressionAgentExecutor(): AgentExecutor = object : AgentExecutor {
            override suspend fun execute(command: AgentCommand): AgentResult {
                val prompt = command.userPrompt.lowercase()
                return when {
                    "mcp" in prompt -> AgentResult.success(
                        content = "Mock MCP tool executed",
                        toolsUsed = listOf("mock_mcp_echo")
                    )

                    "guard" in prompt -> AgentResult.failure("Blocked by mock guard")
                    "rag" in prompt -> AgentResult.success("Mock RAG answer for ${command.userPrompt}")
                    else -> AgentResult.success("Mock answer: ${command.userPrompt}")
                }
            }

            override fun executeStream(command: AgentCommand): Flow<String> = flow {
                emit("stream-start")
                if (command.userPrompt.contains("mcp", ignoreCase = true)) {
                    emit(StreamEventMarker.toolStart("mock_mcp_echo"))
                    emit(StreamEventMarker.toolEnd("mock_mcp_echo"))
                }
                emit("stream-end")
            }
        }

        @Bean
        fun regressionVectorStore(): VectorStore = RegressionVectorStore()
    }

    private class RegressionVectorStore : VectorStore {
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

        private fun score(document: Document, normalizedQuery: String): Double {
            if (normalizedQuery.isBlank()) return 0.0
            val content = (document.text ?: "").lowercase()
            if (content.isBlank()) return 0.0

            if (content.contains(normalizedQuery)) {
                return 1.0
            }

            val queryTokens = normalizedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (queryTokens.isEmpty()) return 0.0
            val matched = queryTokens.count { token -> content.contains(token) }
            return min(1.0, max(0.0, matched.toDouble() / queryTokens.size.toDouble()))
        }
    }
}
