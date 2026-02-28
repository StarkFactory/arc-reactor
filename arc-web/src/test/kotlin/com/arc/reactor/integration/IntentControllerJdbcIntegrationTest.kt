package com.arc.reactor.integration

import com.arc.reactor.ArcReactorApplication
import com.arc.reactor.auth.JwtTokenProvider
import com.arc.reactor.auth.User
import com.arc.reactor.auth.UserRole
import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.impl.JdbcIntentRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.WebTestClient
import javax.sql.DataSource

@SpringBootTest(
    classes = [ArcReactorApplication::class, IntegrationTestAgentExecutorConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:intentdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-intent-schema.sql",
        "spring.ai.google.genai.api-key=test-key",
        "spring.ai.google.genai.embedding.api-key=test-key",
        "arc.reactor.intent.enabled=true",
        "arc.reactor.auth.enabled=true",
        "arc.reactor.auth.jwt-secret=integration-test-jwt-secret-32-bytes",
        "arc.reactor.postgres.required=false"
    ]
)
@AutoConfigureWebTestClient
@Tag("integration")
class IntentControllerJdbcIntegrationTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var intentRegistry: IntentRegistry

    @Autowired
    lateinit var jwtTokenProvider: JwtTokenProvider

    lateinit var adminClient: WebTestClient

    @BeforeEach
    fun cleanDb() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM intent_definitions").use { it.executeUpdate() }
        }
        val token = jwtTokenProvider.createToken(adminUser())
        adminClient = webTestClient.mutate()
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .build()
    }

    @Test
    fun `wires JdbcIntentRegistry when datasource is configured`() {
        assertInstanceOf(JdbcIntentRegistry::class.java, intentRegistry) {
            "Expected JdbcIntentRegistry when datasource is configured"
        }
    }

    @Test
    fun `intent CRUD persists to DB and is reflected in APIs`() {
        // Create
        adminClient.post()
            .uri("/api/intents")
            .bodyValue(
                mapOf(
                    "name" to "refund",
                    "description" to "Refund requests",
                    "examples" to listOf("I want a refund"),
                    "keywords" to listOf("refund", "return"),
                    "profile" to mapOf(
                        "systemPrompt" to "You are a refund specialist.",
                        "maxToolCalls" to 5,
                        "allowedTools" to listOf("checkOrder", "processRefund")
                    ),
                    "enabled" to true
                )
            )
            .exchange()
            .expectStatus().isCreated

        assertEquals(1, countRows("intent_definitions"))

        // List
        val list = adminClient.get()
            .uri("/api/intents")
            .exchange()
            .expectStatus().isOk
            .expectBodyList(Map::class.java)
            .returnResult()
            .responseBody
            .orEmpty()
        assertTrue(list.any { it["name"] == "refund" }, "Intent list should contain the created 'refund' intent")

        // Update
        adminClient.put()
            .uri("/api/intents/refund")
            .bodyValue(
                mapOf(
                    "enabled" to false,
                    "profile" to mapOf(
                        "systemPrompt" to "Follow the refund policy strictly.",
                        "allowedTools" to listOf("processRefund")
                    )
                )
            )
            .exchange()
            .expectStatus().isOk

        assertFalse(queryBoolean("SELECT enabled FROM intent_definitions WHERE name = ?", "refund"), "Intent should be disabled after PUT update")

        // Get
        val get = adminClient.get()
            .uri("/api/intents/refund")
            .exchange()
            .expectStatus().isOk
            .expectBody(Map::class.java)
            .returnResult()
            .responseBody!!
        assertEquals("refund", get["name"])
        @Suppress("UNCHECKED_CAST")
        val profile = get["profile"] as Map<String, Any>
        assertEquals("Follow the refund policy strictly.", profile["systemPrompt"])

        // Delete
        adminClient.delete()
            .uri("/api/intents/refund")
            .exchange()
            .expectStatus().isNoContent

        assertEquals(0, countRows("intent_definitions"))
        adminClient.get()
            .uri("/api/intents/refund")
            .exchange()
            .expectStatus().isNotFound
    }

    private fun adminUser() = User(
        id = "integration-admin",
        email = "integration-admin@arc.dev",
        name = "Integration Admin",
        passwordHash = "ignored",
        role = UserRole.ADMIN
    )

    private fun countRows(table: String): Int =
        queryInt("SELECT COUNT(*) FROM $table")

    private fun queryInt(sql: String, vararg args: Any): Int {
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                args.forEachIndexed { idx, arg -> ps.setObject(idx + 1, arg) }
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    private fun queryBoolean(sql: String, vararg args: Any): Boolean {
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                args.forEachIndexed { idx, arg -> ps.setObject(idx + 1, arg) }
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getBoolean(1) else false
                }
            }
        }
    }
}
