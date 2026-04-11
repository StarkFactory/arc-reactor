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

/**
 * JdbcIntentRegistry에 대한 테스트.
 *
 * JDBC 기반 인텐트 레지스트리의 동작을 검증합니다.
 */
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
    fun `reuse snapshot across reads until save invalidates it해야 한다`() {
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
    fun `updating an intent 후 invalidate enabled snapshot해야 한다`() {
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
    fun `R306 invalidate 후 즉시 read는 최신 DB 상태를 읽어야 한다`() {
        // R306 회귀: invalidateSnapshot이 synchronized 밖에서 @Volatile var = null을 썼을 때,
        // 첫 체크를 통과한 reader가 stale snapshot을 반환할 수 있었다. AtomicReference로
        // 전환한 이후에는 invalidate → 다음 read가 항상 새 snapshot을 로드해야 한다.
        registry.save(createIntent("a", enabled = true))
        registry.save(createIntent("b", enabled = true))
        assertEquals(listOf("a", "b"), registry.list().map { it.name }) {
            "Initial state must contain both intents"
        }

        // 외부에서 rawInsert로 c를 추가 (캐시 우회) — 이 시점에 캐시는 여전히 [a, b].
        rawInsert(createIntent("c", enabled = true))
        assertEquals(listOf("a", "b"), registry.list().map { it.name }) {
            "Snapshot should still reflect pre-rawInsert state before invalidation"
        }

        // 레지스트리를 통한 save는 invalidate를 트리거 → 다음 read가 reload
        registry.save(createIntent("d", enabled = true))
        val afterInvalidate = registry.list().map { it.name }
        assertTrue(afterInvalidate.contains("c")) {
            "After invalidate, reload must include out-of-band inserted 'c', got $afterInvalidate"
        }
        assertTrue(afterInvalidate.contains("d")) {
            "After save, reload must include newly saved 'd', got $afterInvalidate"
        }
    }

    @Test
    fun `delete 후 invalidate snapshot해야 한다`() {
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
