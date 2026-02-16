package com.arc.reactor.intent.impl

import com.arc.reactor.intent.IntentRegistry
import com.arc.reactor.intent.model.IntentDefinition
import com.arc.reactor.intent.model.IntentProfile
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * JDBC-based Intent Registry for persistent intent storage.
 *
 * Stores intent definitions in the `intent_definitions` table.
 *
 * ## Table Schema
 * ```sql
 * CREATE TABLE intent_definitions (
 *     name         VARCHAR(100) PRIMARY KEY,
 *     description  TEXT NOT NULL,
 *     examples     TEXT DEFAULT '[]',
 *     keywords     TEXT DEFAULT '[]',
 *     profile      TEXT DEFAULT '{}',
 *     enabled      BOOLEAN DEFAULT TRUE,
 *     created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 *     updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
 * );
 * ```
 */
class JdbcIntentRegistry(
    private val jdbcTemplate: JdbcTemplate
) : IntentRegistry {

    override fun list(): List<IntentDefinition> {
        return jdbcTemplate.query(SELECT_ALL, ROW_MAPPER)
    }

    override fun listEnabled(): List<IntentDefinition> {
        return jdbcTemplate.query("$BASE_SELECT WHERE enabled = TRUE ORDER BY name ASC", ROW_MAPPER)
    }

    override fun get(intentName: String): IntentDefinition? {
        val results = jdbcTemplate.query(
            "$BASE_SELECT WHERE name = ?",
            ROW_MAPPER,
            intentName
        )
        return results.firstOrNull()
    }

    override fun save(intent: IntentDefinition): IntentDefinition {
        val existing = get(intent.name)
        val now = Instant.now()

        if (existing != null) {
            jdbcTemplate.update(
                """UPDATE intent_definitions
                   SET description = ?, examples = ?, keywords = ?, synonyms = ?,
                       keyword_weights = ?, negative_keywords = ?, profile = ?,
                       enabled = ?, updated_at = ?
                   WHERE name = ?""",
                intent.description,
                objectMapper.writeValueAsString(intent.examples),
                objectMapper.writeValueAsString(intent.keywords),
                objectMapper.writeValueAsString(intent.synonyms),
                objectMapper.writeValueAsString(intent.keywordWeights),
                objectMapper.writeValueAsString(intent.negativeKeywords),
                objectMapper.writeValueAsString(intent.profile),
                intent.enabled,
                java.sql.Timestamp.from(now),
                intent.name
            )
            return intent.copy(createdAt = existing.createdAt, updatedAt = now)
        }

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
            java.sql.Timestamp.from(intent.createdAt),
            java.sql.Timestamp.from(intent.updatedAt)
        )
        return intent
    }

    override fun delete(intentName: String) {
        jdbcTemplate.update("DELETE FROM intent_definitions WHERE name = ?", intentName)
    }

    companion object {
        private const val BASE_SELECT =
            "SELECT name, description, examples, keywords, synonyms, keyword_weights, " +
                "negative_keywords, profile, enabled, created_at, updated_at " +
                "FROM intent_definitions"

        private const val SELECT_ALL = "$BASE_SELECT ORDER BY name ASC"

        private val objectMapper = jacksonObjectMapper()

        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            IntentDefinition(
                name = rs.getString("name"),
                description = rs.getString("description"),
                examples = parseJsonList(rs.getString("examples")),
                keywords = parseJsonList(rs.getString("keywords")),
                synonyms = parseJsonMap(rs.getString("synonyms")),
                keywordWeights = parseJsonDoubleMap(rs.getString("keyword_weights")),
                negativeKeywords = parseJsonList(rs.getString("negative_keywords")),
                profile = parseProfile(rs.getString("profile")),
                enabled = rs.getBoolean("enabled"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant()
            )
        }

        private fun parseJsonList(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                objectMapper.readValue(json)
            } catch (e: Exception) {
                logger.debug(e) { "Failed to parse JSON list: $json" }
                emptyList()
            }
        }

        private fun parseJsonMap(json: String?): Map<String, List<String>> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                objectMapper.readValue(json)
            } catch (e: Exception) {
                logger.debug(e) { "Failed to parse JSON map: $json" }
                emptyMap()
            }
        }

        private fun parseJsonDoubleMap(json: String?): Map<String, Double> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                objectMapper.readValue(json)
            } catch (e: Exception) {
                logger.debug(e) { "Failed to parse JSON double map: $json" }
                emptyMap()
            }
        }

        private fun parseProfile(json: String?): IntentProfile {
            if (json.isNullOrBlank()) return IntentProfile()
            return try {
                objectMapper.readValue(json)
            } catch (e: Exception) {
                logger.debug(e) { "Failed to parse intent profile: $json" }
                IntentProfile()
            }
        }
    }
}
