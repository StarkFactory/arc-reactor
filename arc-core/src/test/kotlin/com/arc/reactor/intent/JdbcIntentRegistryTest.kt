package com.arc.reactor.intent

import com.arc.reactor.intent.impl.JdbcIntentRegistry
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentProfile
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import java.sql.Timestamp
import java.time.Instant

class JdbcIntentRegistryTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var registry: JdbcIntentRegistry

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()

        jdbcTemplate = JdbcTemplate(dataSource)
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS intent_definitions (
                name              VARCHAR(100) PRIMARY KEY,
                description       TEXT NOT NULL,
                examples          TEXT NOT NULL DEFAULT '[]',
                keywords          TEXT NOT NULL DEFAULT '[]',
                synonyms          TEXT NOT NULL DEFAULT '{}',
                keyword_weights   TEXT NOT NULL DEFAULT '{}',
                negative_keywords TEXT NOT NULL DEFAULT '[]',
                profile           TEXT NOT NULL DEFAULT '{}',
                enabled           BOOLEAN NOT NULL DEFAULT TRUE,
                created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_intent_definitions_enabled ON intent_definitions(enabled)"
        )

        registry = JdbcIntentRegistry(jdbcTemplate)
    }

    @Test
    fun `should reuse snapshot across reads until save invalidates it`() {
        rawInsert(createIntent("greeting", enabled = true))

        assertEquals(listOf("greeting"), registry.listEnabled().map { it.name }) {
            "Initial enabled snapshot should contain the single stored intent"
        }

        rawInsert(createIntent("order", enabled = true))

        assertEquals(listOf("greeting"), registry.listEnabled().map { it.name }) {
            "Repeated enabled reads should reuse the cached snapshot until invalidated"
        }
        assertNull(registry.get("order")) {
            "Direct gets should reuse the same snapshot and not see out-of-band inserts yet"
        }

        registry.save(createIntent("refresh", enabled = true))

        assertEquals(listOf("greeting", "order", "refresh"), registry.listEnabled().map { it.name }) {
            "Saving through the registry should invalidate the snapshot and reload all enabled intents"
        }
        assertNotNull(registry.get("order")) {
            "Reloaded snapshot should expose rows inserted out-of-band after invalidation"
        }
    }

    @Test
    fun `should invalidate enabled snapshot after updating an intent`() {
        registry.save(createIntent("greeting", enabled = true))
        assertEquals(listOf("greeting"), registry.listEnabled().map { it.name }) {
            "Enabled list should contain greeting before the update"
        }

        val updated = registry.save(createIntent("greeting", enabled = false))

        assertFalse(updated.enabled) { "Updated intent should now be disabled" }
        assertTrue(registry.listEnabled().isEmpty()) {
            "Enabled snapshot should be rebuilt after updates and exclude disabled intents"
        }
        assertEquals(false, registry.get("greeting")?.enabled) {
            "Get should observe the rebuilt snapshot after an update"
        }
    }

    @Test
    fun `should invalidate snapshot after delete`() {
        registry.save(createIntent("alpha", enabled = true))
        registry.save(createIntent("beta", enabled = true))
        assertEquals(listOf("alpha", "beta"), registry.list().map { it.name }) {
            "Initial list should contain both saved intents"
        }

        registry.delete("beta")

        assertEquals(listOf("alpha"), registry.list().map { it.name }) {
            "List should be rebuilt after delete and no longer include removed intents"
        }
        assertNull(registry.get("beta")) {
            "Get should not return deleted intents after snapshot invalidation"
        }
    }

    private fun rawInsert(intent: IntentDefinition) {
        jdbcTemplate.update(
            """INSERT INTO intent_definitions
               (name, description, examples, keywords, synonyms, keyword_weights,
                negative_keywords, profile, enabled, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            intent.name,
            intent.description,
            objectMapper.writeValueAsString(intent.examples),
            objectMapper.writeValueAsString(intent.keywords),
            objectMapper.writeValueAsString(intent.synonyms),
            objectMapper.writeValueAsString(intent.keywordWeights),
            objectMapper.writeValueAsString(intent.negativeKeywords),
            objectMapper.writeValueAsString(intent.profile),
            intent.enabled,
            Timestamp.from(intent.createdAt),
            Timestamp.from(intent.updatedAt)
        )
    }

    private fun createIntent(name: String, enabled: Boolean): IntentDefinition {
        return IntentDefinition(
            name = name,
            description = "$name description",
            examples = listOf("$name example"),
            keywords = listOf(name),
            synonyms = mapOf(name to listOf("$name-alt")),
            keywordWeights = mapOf(name to 1.0),
            negativeKeywords = listOf("skip-$name"),
            profile = IntentProfile(maxToolCalls = 1),
            enabled = enabled,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
    }

    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
}
