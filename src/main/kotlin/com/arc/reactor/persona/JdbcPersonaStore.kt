package com.arc.reactor.persona

import mu.KotlinLogging
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * JDBC-based Persona Store for persistent persona storage.
 *
 * Stores personas in the `personas` table — see Flyway migration V2.
 * Guarantees at most one default persona via transactional update.
 *
 * ## Features
 * - Persistent across server restarts
 * - Single default persona enforcement
 * - Thread-safe via database transactions
 */
class JdbcPersonaStore(
    private val jdbcTemplate: JdbcTemplate
) : PersonaStore {

    override fun list(): List<Persona> {
        return jdbcTemplate.query(
            "SELECT id, name, system_prompt, is_default, created_at, updated_at FROM personas ORDER BY created_at ASC",
            ROW_MAPPER
        )
    }

    override fun get(personaId: String): Persona? {
        val results = jdbcTemplate.query(
            "SELECT id, name, system_prompt, is_default, created_at, updated_at FROM personas WHERE id = ?",
            ROW_MAPPER,
            personaId
        )
        return results.firstOrNull()
    }

    override fun getDefault(): Persona? {
        val results = jdbcTemplate.query(
            "SELECT id, name, system_prompt, is_default, created_at, updated_at FROM personas WHERE is_default = TRUE LIMIT 1",
            ROW_MAPPER
        )
        return results.firstOrNull()
    }

    override fun save(persona: Persona): Persona {
        if (persona.isDefault) {
            clearDefault()
        }
        jdbcTemplate.update(
            "INSERT INTO personas (id, name, system_prompt, is_default, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
            persona.id,
            persona.name,
            persona.systemPrompt,
            persona.isDefault,
            java.sql.Timestamp.from(persona.createdAt),
            java.sql.Timestamp.from(persona.updatedAt)
        )
        return persona
    }

    override fun update(personaId: String, name: String?, systemPrompt: String?, isDefault: Boolean?): Persona? {
        val existing = get(personaId) ?: return null

        if (isDefault == true) {
            clearDefault()
        }

        val updatedName = name ?: existing.name
        val updatedPrompt = systemPrompt ?: existing.systemPrompt
        val updatedDefault = isDefault ?: existing.isDefault
        val updatedAt = Instant.now()

        jdbcTemplate.update(
            "UPDATE personas SET name = ?, system_prompt = ?, is_default = ?, updated_at = ? WHERE id = ?",
            updatedName,
            updatedPrompt,
            updatedDefault,
            java.sql.Timestamp.from(updatedAt),
            personaId
        )

        return existing.copy(
            name = updatedName,
            systemPrompt = updatedPrompt,
            isDefault = updatedDefault,
            updatedAt = updatedAt
        )
    }

    override fun delete(personaId: String) {
        jdbcTemplate.update("DELETE FROM personas WHERE id = ?", personaId)
    }

    private fun clearDefault() {
        jdbcTemplate.update("UPDATE personas SET is_default = FALSE, updated_at = ? WHERE is_default = TRUE",
            java.sql.Timestamp.from(Instant.now()))
    }

    companion object {
        private val ROW_MAPPER = { rs: ResultSet, _: Int ->
            Persona(
                id = rs.getString("id"),
                name = rs.getString("name"),
                systemPrompt = rs.getString("system_prompt"),
                isDefault = rs.getBoolean("is_default"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant()
            )
        }
    }
}
