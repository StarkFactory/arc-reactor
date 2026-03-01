package com.arc.reactor.integration

import com.arc.reactor.ArcReactorApplication
import com.arc.reactor.auth.JwtTokenProvider
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserRole
import com.arc.reactor.rag.ingestion.JdbcRagIngestionCandidateStore
import com.arc.reactor.rag.ingestion.JdbcRagIngestionPolicyStore
import com.arc.reactor.rag.ingestion.RagIngestionCandidate
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStatus
import com.arc.reactor.rag.ingestion.RagIngestionCandidateStore
import com.arc.reactor.rag.ingestion.RagIngestionPolicyStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
    classes = [ArcReactorApplication::class, IntegrationTestAgentExecutorConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:ragingestiondb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=" +
            "classpath:test-schema.sql,classpath:test-tool-policy-schema.sql,classpath:test-rag-ingestion-schema.sql",
        "spring.ai.google.genai.api-key=test-key",
        "spring.ai.google.genai.embedding.api-key=test-key",
        "arc.reactor.rag.ingestion.enabled=true",
        "arc.reactor.rag.ingestion.dynamic.enabled=true",
        "arc.reactor.rag.ingestion.dynamic.refresh-ms=600000",
        "arc.reactor.auth.jwt-secret=integration-test-jwt-secret-32-bytes",
        "arc.reactor.postgres.required=false"
    ]
)
@AutoConfigureWebTestClient
@Tag("integration")
class RagIngestionIntegrationTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var ragIngestionPolicyStore: RagIngestionPolicyStore

    @Autowired
    lateinit var ragIngestionCandidateStore: RagIngestionCandidateStore

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    lateinit var adminClient: WebTestClient

    @BeforeEach
    fun reset() {
        jdbcTemplate.update("DELETE FROM rag_ingestion_policy")
        jdbcTemplate.update("DELETE FROM rag_ingestion_candidates")
        val token = jwtTokenProvider.createToken(adminUser())
        adminClient = webTestClient.mutate()
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .build()
    }

    @Test
    fun `wires jdbc rag ingestion stores when datasource and dynamic are enabled`() {
        assertInstanceOf(JdbcRagIngestionPolicyStore::class.java, ragIngestionPolicyStore) {
            "Expected JdbcRagIngestionPolicyStore when datasource and dynamic policy are enabled"
        }
        assertInstanceOf(JdbcRagIngestionCandidateStore::class.java, ragIngestionCandidateStore) {
            "Expected JdbcRagIngestionCandidateStore when datasource and dynamic policy are enabled"
        }
    }

    @Test
    fun `policy api persists and returns effective state`() {
        adminClient.put()
            .uri("/api/rag-ingestion/policy")
            .bodyValue(
                mapOf(
                    "enabled" to true,
                    "requireReview" to true,
                    "allowedChannels" to listOf("slack"),
                    "minQueryChars" to 3,
                    "minResponseChars" to 4,
                    "blockedPatterns" to listOf("secret")
                )
            )
            .exchange()
            .expectStatus().isOk

        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM rag_ingestion_policy", Int::class.java))

        val state = adminClient.get()
            .uri("/api/rag-ingestion/policy")
            .exchange()
            .expectStatus().isOk
            .expectBody(Map::class.java)
            .returnResult()
            .responseBody!!

        @Suppress("UNCHECKED_CAST")
        val effective = state["effective"] as Map<String, Any>
        assertEquals(true, effective["enabled"])
        assertEquals(true, effective["requireReview"])
    }

    @Test
    fun `candidate reject api updates status in database`() {
        val candidate = ragIngestionCandidateStore.save(
            RagIngestionCandidate(
                runId = "run-1",
                userId = "u1",
                channel = "slack",
                query = "q",
                response = "r",
                status = RagIngestionCandidateStatus.PENDING
            )
        )

        adminClient.post()
            .uri("/api/rag-ingestion/candidates/${candidate.id}/reject")
            .bodyValue(mapOf("comment" to "not useful"))
            .exchange()
            .expectStatus().isOk

        val updatedStatus = jdbcTemplate.queryForObject(
            "SELECT status FROM rag_ingestion_candidates WHERE id = ?",
            String::class.java,
            candidate.id
        )
        assertEquals("REJECTED", updatedStatus)
    }

    private fun adminUser() = User(
        id = "integration-admin",
        email = "integration-admin@arc.dev",
        name = "Integration Admin",
        passwordHash = "ignored",
        role = UserRole.ADMIN
    )
}
