package com.arc.reactor.persona

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.sql.ResultSet
import java.time.Instant

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
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate
) : PersonaStore {

    override fun list(): List<Persona> {
        return jdbcTemplate.query(
            "SELECT id, name, system_prompt, is_default, description, response_guideline, " +
                "welcome_message, icon, is_active, created_at, updated_at FROM personas ORDER BY created_at ASC",
            ROW_MAPPER
        )
    }

    override fun get(personaId: String): Persona? {
        val results = jdbcTemplate.query(
            "SELECT id, name, system_prompt, is_default, description, response_guideline, " +
                "welcome_message, icon, is_active, created_at, updated_at FROM personas WHERE id = ?",
            ROW_MAPPER,
            personaId
        )
        return results.firstOrNull()
    }

    override fun getDefault(): Persona? {
        val results = jdbcTemplate.query(
            "SELECT id, name, system_prompt, is_default, description, response_guideline, " +
                "welcome_message, icon, is_active, created_at, updated_at " +
                "FROM personas WHERE is_default = TRUE LIMIT 1",
            ROW_MAPPER
        )
        return results.firstOrNull()
    }

    override fun save(persona: Persona): Persona {
        transactionTemplate.execute {
            if (persona.isDefault) {
                clearDefault()
            }
            jdbcTemplate.update(
                "INSERT INTO personas (id, name, system_prompt, is_default, description, " +
                    "response_guideline, welcome_message, icon, is_active, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                persona.id,
                persona.name,
                persona.systemPrompt,
                persona.isDefault,
                persona.description,
                persona.responseGuideline,
                persona.welcomeMessage,
                persona.icon,
                persona.isActive,
                java.sql.Timestamp.from(persona.createdAt),
                java.sql.Timestamp.from(persona.updatedAt)
            )
        }
        return persona
    }

    override fun update(
        personaId: String,
        name: String?,
        systemPrompt: String?,
        isDefault: Boolean?,
        description: String?,
        responseGuideline: String?,
        welcomeMessage: String?,
        icon: String?,
        isActive: Boolean?
    ): Persona? {
        val existing = get(personaId) ?: return null

        val updatedName = name ?: existing.name
        val updatedPrompt = systemPrompt ?: existing.systemPrompt
        val updatedDefault = isDefault ?: existing.isDefault
        val updatedDescription = resolveNullableField(description, existing.description)
        val updatedGuideline = resolveNullableField(responseGuideline, existing.responseGuideline)
        val updatedWelcome = resolveNullableField(welcomeMessage, existing.welcomeMessage)
        val updatedIcon = resolveNullableField(icon, existing.icon)
        val updatedActive = isActive ?: existing.isActive
        val updatedAt = Instant.now()

        transactionTemplate.execute {
            if (isDefault == true) {
                clearDefault()
            }
            jdbcTemplate.update(
                "UPDATE personas SET name = ?, system_prompt = ?, is_default = ?, description = ?, " +
                    "response_guideline = ?, welcome_message = ?, icon = ?, is_active = ?, " +
                    "updated_at = ? WHERE id = ?",
                updatedName,
                updatedPrompt,
                updatedDefault,
                updatedDescription,
                updatedGuideline,
                updatedWelcome,
                updatedIcon,
                updatedActive,
                java.sql.Timestamp.from(updatedAt),
                personaId
            )
        }

        return existing.copy(
            name = updatedName,
            systemPrompt = updatedPrompt,
            isDefault = updatedDefault,
            description = updatedDescription,
            responseGuideline = updatedGuideline,
            welcomeMessage = updatedWelcome,
            icon = updatedIcon,
            isActive = updatedActive,
            updatedAt = updatedAt
        )
    }

    override fun delete(personaId: String) {
        jdbcTemplate.update("DELETE FROM personas WHERE id = ?", personaId)
    }

    private fun resolveNullableField(newValue: String?, existing: String?): String? {
        return when {
            newValue == null -> existing
            newValue.isEmpty() -> null
            else -> newValue
        }
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
                description = rs.getString("description"),
                responseGuideline = rs.getString("response_guideline"),
                welcomeMessage = rs.getString("welcome_message"),
                icon = rs.getString("icon"),
                isActive = rs.getBoolean("is_active"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant()
            )
        }
    }
}
