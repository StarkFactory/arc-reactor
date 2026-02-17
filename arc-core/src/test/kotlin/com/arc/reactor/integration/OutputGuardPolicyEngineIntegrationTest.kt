package com.arc.reactor.integration

import com.arc.reactor.ArcReactorApplication
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
    classes = [ArcReactorApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.url=jdbc:h2:mem:policydb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql",
        "spring.ai.google.genai.api-key=test-key",
        "spring.ai.google.genai.embedding.api-key=test-key",
        "arc.reactor.output-guard.enabled=true",
        "arc.reactor.output-guard.dynamic-rules-enabled=true",
        "arc.reactor.output-guard.dynamic-rules-refresh-ms=600000"
    ]
)
@AutoConfigureWebTestClient
@Tag("integration")
class OutputGuardPolicyEngineIntegrationTest {

    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUpSchema() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS output_guard_rules (
                id VARCHAR(36) PRIMARY KEY,
                name VARCHAR(120) NOT NULL,
                pattern TEXT NOT NULL,
                action VARCHAR(16) NOT NULL,
                priority INT NOT NULL DEFAULT 100,
                enabled BOOLEAN NOT NULL DEFAULT TRUE,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS output_guard_rule_audits (
                id VARCHAR(36) PRIMARY KEY,
                rule_id VARCHAR(36),
                action VARCHAR(32) NOT NULL,
                actor VARCHAR(120) NOT NULL,
                detail TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        jdbcTemplate.execute("DELETE FROM output_guard_rule_audits")
        jdbcTemplate.execute("DELETE FROM output_guard_rules")
    }

    @Test
    fun `admin policy engine persists rules and applies changes immediately`() {
        val rejectRule = createRule(
            name = "Block secret",
            pattern = "(?i)secret",
            action = "REJECT",
            priority = 1,
            enabled = true
        )
        val maskRule = createRule(
            name = "Mask password",
            pattern = "(?i)password\\s*:\\s*\\S+",
            action = "MASK",
            priority = 10,
            enabled = true
        )

        assertEquals(2, countRows("output_guard_rules"))
        assertEquals(1, queryInt("SELECT priority FROM output_guard_rules WHERE id = ?", rejectRule["id"].asText()))
        assertEquals(10, queryInt("SELECT priority FROM output_guard_rules WHERE id = ?", maskRule["id"].asText()))

        val blockedSim = simulate("SECRET password: p@ss", includeDisabled = false)
        assertTrue(blockedSim["blocked"].asBoolean())
        assertEquals(rejectRule["id"].asText(), blockedSim["blockedByRuleId"].asText())

        updateRule(
            id = rejectRule["id"].asText(),
            body = mapOf("enabled" to false, "priority" to 50)
        )

        assertFalse(queryBoolean("SELECT enabled FROM output_guard_rules WHERE id = ?", rejectRule["id"].asText()))
        assertEquals(50, queryInt("SELECT priority FROM output_guard_rules WHERE id = ?", rejectRule["id"].asText()))

        val maskedSim = simulate("SECRET password: p@ss", includeDisabled = false)
        assertFalse(maskedSim["blocked"].asBoolean())
        assertTrue(maskedSim["modified"].asBoolean())
        assertTrue(maskedSim["resultContent"].asText().contains("[REDACTED]"))

        deleteRule(maskRule["id"].asText())

        assertEquals(1, countRows("output_guard_rules"))
        assertEquals(0, queryInt("SELECT COUNT(*) FROM output_guard_rules WHERE id = ?", maskRule["id"].asText()))

        val finalSim = simulate("SECRET password: p@ss", includeDisabled = false)
        assertFalse(finalSim["blocked"].asBoolean())
        assertFalse(finalSim["modified"].asBoolean())
        assertEquals("SECRET password: p@ss", finalSim["resultContent"].asText())

        val audits = listAudits(limit = 50)
        val actions = audits.map { it["action"].asText() }
        assertTrue(actions.count { it == "CREATE" } >= 2)
        assertTrue(actions.any { it == "UPDATE" })
        assertTrue(actions.any { it == "DELETE" })
        assertTrue(actions.count { it == "SIMULATE" } >= 3)
        assertEquals(audits.size, countRows("output_guard_rule_audits"))
    }

    private fun createRule(
        name: String,
        pattern: String,
        action: String,
        priority: Int,
        enabled: Boolean
    ): JsonNode {
        val result = webTestClient.post()
            .uri("/api/output-guard/rules")
            .bodyValue(
                mapOf(
                    "name" to name,
                    "pattern" to pattern,
                    "action" to action,
                    "priority" to priority,
                    "enabled" to enabled
                )
            )
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .returnResult()

        return toJson(result.responseBody)
    }

    private fun updateRule(id: String, body: Map<String, Any>) {
        webTestClient.put()
            .uri("/api/output-guard/rules/$id")
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk
    }

    private fun deleteRule(id: String) {
        webTestClient.delete()
            .uri("/api/output-guard/rules/$id")
            .exchange()
            .expectStatus().isNoContent
    }

    private fun simulate(content: String, includeDisabled: Boolean): JsonNode {
        val result = webTestClient.post()
            .uri("/api/output-guard/rules/simulate")
            .bodyValue(
                mapOf(
                    "content" to content,
                    "includeDisabled" to includeDisabled
                )
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .returnResult()
        return toJson(result.responseBody)
    }

    private fun listAudits(limit: Int): List<JsonNode> {
        val result = webTestClient.get()
            .uri("/api/output-guard/rules/audits?limit=$limit")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .returnResult()
        val root = toJson(result.responseBody)
        return root.map { it }
    }

    private fun toJson(bytes: ByteArray?): JsonNode {
        val body = requireNotNull(bytes)
        return objectMapper.readTree(body)
    }

    private fun countRows(table: String): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java) ?: 0

    private fun queryInt(sql: String, vararg args: Any): Int =
        jdbcTemplate.queryForObject(sql, Int::class.java, *args) ?: 0

    private fun queryBoolean(sql: String, vararg args: Any): Boolean =
        jdbcTemplate.queryForObject(sql, Boolean::class.java, *args) ?: false
}
