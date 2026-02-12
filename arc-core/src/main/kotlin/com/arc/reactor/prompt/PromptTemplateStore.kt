package com.arc.reactor.prompt

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Prompt Template Store Interface
 *
 * Manages CRUD operations for [PromptTemplate]s and their [PromptVersion]s.
 * Implementations must guarantee that at most one version per template has [VersionStatus.ACTIVE].
 *
 * @see InMemoryPromptTemplateStore for default in-memory implementation
 * @see JdbcPromptTemplateStore for PostgreSQL persistent implementation
 */
interface PromptTemplateStore {

    // ---- Template CRUD ----

    /**
     * List all templates, ordered by creation time ascending.
     */
    fun listTemplates(): List<PromptTemplate>

    /**
     * Get a template by ID.
     *
     * @return Template if found, null otherwise
     */
    fun getTemplate(id: String): PromptTemplate?

    /**
     * Get a template by unique name.
     *
     * @return Template if found, null otherwise
     */
    fun getTemplateByName(name: String): PromptTemplate?

    /**
     * Save a new template.
     *
     * @return The saved template
     */
    fun saveTemplate(template: PromptTemplate): PromptTemplate

    /**
     * Partially update a template. Only non-null fields are applied.
     *
     * @return Updated template, or null if not found
     */
    fun updateTemplate(id: String, name: String?, description: String?): PromptTemplate?

    /**
     * Delete a template and all its versions. Idempotent — no error if not found.
     */
    fun deleteTemplate(id: String)

    // ---- Version Management ----

    /**
     * List all versions for a template, ordered by version number ascending.
     */
    fun listVersions(templateId: String): List<PromptVersion>

    /**
     * Get a specific version by its ID.
     *
     * @return Version if found, null otherwise
     */
    fun getVersion(versionId: String): PromptVersion?

    /**
     * Get the currently active version for a template.
     *
     * @return Active version if one exists, null otherwise
     */
    fun getActiveVersion(templateId: String): PromptVersion?

    /**
     * Create a new version for a template. Version number is auto-incremented.
     * New versions start in [VersionStatus.DRAFT] status.
     *
     * @return Created version, or null if template not found
     */
    fun createVersion(templateId: String, content: String, changeLog: String = ""): PromptVersion?

    /**
     * Activate a version. The previously active version (if any) is archived.
     * Only one version per template can be active at a time.
     *
     * @return Activated version, or null if template or version not found
     */
    fun activateVersion(templateId: String, versionId: String): PromptVersion?

    /**
     * Archive a version. Sets status to [VersionStatus.ARCHIVED].
     *
     * @return Archived version, or null if not found
     */
    fun archiveVersion(versionId: String): PromptVersion?
}

/**
 * In-Memory Prompt Template Store
 *
 * Thread-safe implementation using [ConcurrentHashMap].
 * Not persistent — data is lost on server restart.
 */
class InMemoryPromptTemplateStore : PromptTemplateStore {

    private val templates = ConcurrentHashMap<String, PromptTemplate>()
    private val versions = ConcurrentHashMap<String, PromptVersion>()

    override fun listTemplates(): List<PromptTemplate> {
        return templates.values.sortedBy { it.createdAt }
    }

    override fun getTemplate(id: String): PromptTemplate? = templates[id]

    override fun getTemplateByName(name: String): PromptTemplate? {
        return templates.values.firstOrNull { it.name == name }
    }

    override fun saveTemplate(template: PromptTemplate): PromptTemplate {
        templates[template.id] = template
        return template
    }

    override fun updateTemplate(id: String, name: String?, description: String?): PromptTemplate? {
        val existing = templates[id] ?: return null
        val updated = existing.copy(
            name = name ?: existing.name,
            description = description ?: existing.description,
            updatedAt = Instant.now()
        )
        templates[id] = updated
        return updated
    }

    override fun deleteTemplate(id: String) {
        templates.remove(id)
        versions.values.removeIf { it.templateId == id }
    }

    override fun listVersions(templateId: String): List<PromptVersion> {
        return versions.values
            .filter { it.templateId == templateId }
            .sortedBy { it.version }
    }

    override fun getVersion(versionId: String): PromptVersion? = versions[versionId]

    override fun getActiveVersion(templateId: String): PromptVersion? {
        return versions.values.firstOrNull {
            it.templateId == templateId && it.status == VersionStatus.ACTIVE
        }
    }

    override fun createVersion(templateId: String, content: String, changeLog: String): PromptVersion? {
        if (templates[templateId] == null) return null

        val nextVersion = versions.values
            .filter { it.templateId == templateId }
            .maxOfOrNull { it.version }
            ?.plus(1) ?: 1

        val version = PromptVersion(
            id = UUID.randomUUID().toString(),
            templateId = templateId,
            version = nextVersion,
            content = content,
            status = VersionStatus.DRAFT,
            changeLog = changeLog
        )
        versions[version.id] = version
        return version
    }

    override fun activateVersion(templateId: String, versionId: String): PromptVersion? {
        val version = versions[versionId] ?: return null
        if (version.templateId != templateId) return null

        // Archive the currently active version
        versions.values
            .filter { it.templateId == templateId && it.status == VersionStatus.ACTIVE }
            .forEach { versions[it.id] = it.copy(status = VersionStatus.ARCHIVED) }

        val activated = version.copy(status = VersionStatus.ACTIVE)
        versions[versionId] = activated
        return activated
    }

    override fun archiveVersion(versionId: String): PromptVersion? {
        val version = versions[versionId] ?: return null
        val archived = version.copy(status = VersionStatus.ARCHIVED)
        versions[versionId] = archived
        return archived
    }
}
