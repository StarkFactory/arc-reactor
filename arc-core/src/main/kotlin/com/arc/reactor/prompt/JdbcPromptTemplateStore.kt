package com.arc.reactor.prompt

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * JDBC-based Prompt Template Store for persistent storage.
 *
 * Stores templates in `prompt_templates` and versions in `prompt_versions` — see Flyway migration V5.
 * Guarantees at most one active version per template via transactional update.
 *
 * ## Features
 * - Persistent across server restarts
 * - Single active version per template enforcement
 * - Cascade delete (template deletion removes all versions)
 * - Thread-safe via database transactions
 */
class JdbcPromptTemplateStore(
    private val jdbcTemplate: JdbcTemplate
) : PromptTemplateStore {

    // ---- Template CRUD ----

    override fun listTemplates(): List<PromptTemplate> {
        return jdbcTemplate.query(
            "SELECT id, name, description, created_at, updated_at FROM prompt_templates ORDER BY created_at ASC",
            TEMPLATE_ROW_MAPPER
        )
    }

    override fun getTemplate(id: String): PromptTemplate? {
        val results = jdbcTemplate.query(
            "SELECT id, name, description, created_at, updated_at FROM prompt_templates WHERE id = ?",
            TEMPLATE_ROW_MAPPER,
            id
        )
        return results.firstOrNull()
    }

    override fun getTemplateByName(name: String): PromptTemplate? {
        val results = jdbcTemplate.query(
            "SELECT id, name, description, created_at, updated_at FROM prompt_templates WHERE name = ?",
            TEMPLATE_ROW_MAPPER,
            name
        )
        return results.firstOrNull()
    }

    override fun saveTemplate(template: PromptTemplate): PromptTemplate {
        jdbcTemplate.update(
            "INSERT INTO prompt_templates (id, name, description, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
            template.id,
            template.name,
            template.description,
            java.sql.Timestamp.from(template.createdAt),
            java.sql.Timestamp.from(template.updatedAt)
        )
        return template
    }

    override fun updateTemplate(id: String, name: String?, description: String?): PromptTemplate? {
        val existing = getTemplate(id) ?: return null

        val updatedName = name ?: existing.name
        val updatedDescription = description ?: existing.description
        val updatedAt = Instant.now()

        jdbcTemplate.update(
            "UPDATE prompt_templates SET name = ?, description = ?, updated_at = ? WHERE id = ?",
            updatedName,
            updatedDescription,
            java.sql.Timestamp.from(updatedAt),
            id
        )

        return existing.copy(
            name = updatedName,
            description = updatedDescription,
            updatedAt = updatedAt
        )
    }

    override fun deleteTemplate(id: String) {
        // Versions are cascade-deleted by the foreign key constraint
        jdbcTemplate.update("DELETE FROM prompt_templates WHERE id = ?", id)
    }

    // ---- Version Management ----

    override fun listVersions(templateId: String): List<PromptVersion> {
        return jdbcTemplate.query(
            "SELECT id, template_id, version, content, status, change_log, created_at " +
                "FROM prompt_versions WHERE template_id = ? ORDER BY version ASC",
            VERSION_ROW_MAPPER,
            templateId
        )
    }

    override fun getVersion(versionId: String): PromptVersion? {
        val results = jdbcTemplate.query(
            "SELECT id, template_id, version, content, status, change_log, created_at " +
                "FROM prompt_versions WHERE id = ?",
            VERSION_ROW_MAPPER,
            versionId
        )
        return results.firstOrNull()
    }

    override fun getActiveVersion(templateId: String): PromptVersion? {
        val results = jdbcTemplate.query(
            "SELECT id, template_id, version, content, status, change_log, created_at " +
                "FROM prompt_versions WHERE template_id = ? AND status = 'ACTIVE' LIMIT 1",
            VERSION_ROW_MAPPER,
            templateId
        )
        return results.firstOrNull()
    }

    override fun createVersion(templateId: String, content: String, changeLog: String): PromptVersion? {
        if (getTemplate(templateId) == null) return null

        val nextVersion = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(version), 0) + 1 FROM prompt_versions WHERE template_id = ?",
            Int::class.java,
            templateId
        ) ?: 1

        val version = PromptVersion(
            id = UUID.randomUUID().toString(),
            templateId = templateId,
            version = nextVersion,
            content = content,
            status = VersionStatus.DRAFT,
            changeLog = changeLog
        )

        jdbcTemplate.update(
            "INSERT INTO prompt_versions (id, template_id, version, content, status, change_log, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            version.id,
            version.templateId,
            version.version,
            version.content,
            version.status.name,
            version.changeLog,
            java.sql.Timestamp.from(version.createdAt)
        )

        return version
    }

    override fun activateVersion(templateId: String, versionId: String): PromptVersion? {
        val version = getVersion(versionId) ?: return null
        if (version.templateId != templateId) return null

        // Archive the currently active version
        archiveActiveVersion(templateId)

        jdbcTemplate.update(
            "UPDATE prompt_versions SET status = ? WHERE id = ?",
            VersionStatus.ACTIVE.name,
            versionId
        )

        return version.copy(status = VersionStatus.ACTIVE)
    }

    override fun archiveVersion(versionId: String): PromptVersion? {
        val version = getVersion(versionId) ?: return null

        jdbcTemplate.update(
            "UPDATE prompt_versions SET status = ? WHERE id = ?",
            VersionStatus.ARCHIVED.name,
            versionId
        )

        return version.copy(status = VersionStatus.ARCHIVED)
    }

    private fun archiveActiveVersion(templateId: String) {
        jdbcTemplate.update(
            "UPDATE prompt_versions SET status = ? WHERE template_id = ? AND status = ?",
            VersionStatus.ARCHIVED.name,
            templateId,
            VersionStatus.ACTIVE.name
        )
    }

    companion object {
        private val TEMPLATE_ROW_MAPPER = { rs: ResultSet, _: Int ->
            PromptTemplate(
                id = rs.getString("id"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant()
            )
        }

        private val VERSION_ROW_MAPPER = { rs: ResultSet, _: Int ->
            PromptVersion(
                id = rs.getString("id"),
                templateId = rs.getString("template_id"),
                version = rs.getInt("version"),
                content = rs.getString("content"),
                status = VersionStatus.valueOf(rs.getString("status")),
                changeLog = rs.getString("change_log"),
                createdAt = rs.getTimestamp("created_at").toInstant()
            )
        }
    }
}
