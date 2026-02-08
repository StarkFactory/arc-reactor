package com.arc.reactor.controller

import com.arc.reactor.prompt.PromptTemplate
import com.arc.reactor.prompt.PromptTemplateStore
import com.arc.reactor.prompt.PromptVersion
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Prompt Template Management API Controller
 *
 * Provides REST APIs for managing versioned system prompt templates.
 * Templates contain multiple versions with lifecycle status (DRAFT → ACTIVE → ARCHIVED).
 *
 * ## Template Endpoints
 * - GET    /api/prompt-templates          : List all templates
 * - POST   /api/prompt-templates          : Create a new template
 * - GET    /api/prompt-templates/{id}     : Get template with all versions
 * - PUT    /api/prompt-templates/{id}     : Update template metadata
 * - DELETE /api/prompt-templates/{id}     : Delete template and all versions
 *
 * ## Version Endpoints
 * - POST   /api/prompt-templates/{id}/versions                  : Create a new version
 * - PUT    /api/prompt-templates/{id}/versions/{vid}/activate   : Activate a version
 * - PUT    /api/prompt-templates/{id}/versions/{vid}/archive    : Archive a version
 */
@RestController
@RequestMapping("/api/prompt-templates")
class PromptTemplateController(
    private val promptTemplateStore: PromptTemplateStore
) {

    // ---- Template Endpoints ----

    /**
     * List all templates.
     */
    @GetMapping
    fun listTemplates(): List<TemplateResponse> {
        return promptTemplateStore.listTemplates().map { it.toResponse() }
    }

    /**
     * Get a template by ID, including all its versions.
     */
    @GetMapping("/{templateId}")
    fun getTemplate(@PathVariable templateId: String): ResponseEntity<TemplateDetailResponse> {
        val template = promptTemplateStore.getTemplate(templateId)
            ?: return ResponseEntity.notFound().build()
        val versions = promptTemplateStore.listVersions(templateId)
        val activeVersion = versions.firstOrNull { it.status == com.arc.reactor.prompt.VersionStatus.ACTIVE }
        return ResponseEntity.ok(template.toDetailResponse(versions, activeVersion))
    }

    /**
     * Create a new template.
     */
    @PostMapping
    fun createTemplate(
        @Valid @RequestBody request: CreateTemplateRequest
    ): ResponseEntity<TemplateResponse> {
        val template = PromptTemplate(
            id = UUID.randomUUID().toString(),
            name = request.name,
            description = request.description
        )
        val saved = promptTemplateStore.saveTemplate(template)
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toResponse())
    }

    /**
     * Update template metadata. Only provided fields are changed.
     */
    @PutMapping("/{templateId}")
    fun updateTemplate(
        @PathVariable templateId: String,
        @Valid @RequestBody request: UpdateTemplateRequest
    ): ResponseEntity<TemplateResponse> {
        val updated = promptTemplateStore.updateTemplate(
            id = templateId,
            name = request.name,
            description = request.description
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(updated.toResponse())
    }

    /**
     * Delete a template and all its versions. Idempotent — returns 204 even if not found.
     */
    @DeleteMapping("/{templateId}")
    fun deleteTemplate(@PathVariable templateId: String): ResponseEntity<Void> {
        promptTemplateStore.deleteTemplate(templateId)
        return ResponseEntity.noContent().build()
    }

    // ---- Version Endpoints ----

    /**
     * Create a new version for a template. Starts in DRAFT status.
     */
    @PostMapping("/{templateId}/versions")
    fun createVersion(
        @PathVariable templateId: String,
        @Valid @RequestBody request: CreateVersionRequest
    ): ResponseEntity<VersionResponse> {
        val version = promptTemplateStore.createVersion(
            templateId = templateId,
            content = request.content,
            changeLog = request.changeLog
        ) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.status(HttpStatus.CREATED).body(version.toResponse())
    }

    /**
     * Activate a version. The previously active version (if any) is archived.
     */
    @PutMapping("/{templateId}/versions/{versionId}/activate")
    fun activateVersion(
        @PathVariable templateId: String,
        @PathVariable versionId: String
    ): ResponseEntity<VersionResponse> {
        val activated = promptTemplateStore.activateVersion(templateId, versionId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(activated.toResponse())
    }

    /**
     * Archive a version.
     */
    @PutMapping("/{templateId}/versions/{versionId}/archive")
    fun archiveVersion(
        @PathVariable templateId: String,
        @PathVariable versionId: String
    ): ResponseEntity<VersionResponse> {
        val archived = promptTemplateStore.archiveVersion(versionId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(archived.toResponse())
    }
}

// --- Request DTOs ---

data class CreateTemplateRequest(
    @field:NotBlank(message = "name must not be blank")
    val name: String,
    val description: String = ""
)

data class UpdateTemplateRequest(
    val name: String? = null,
    val description: String? = null
)

data class CreateVersionRequest(
    @field:NotBlank(message = "content must not be blank")
    val content: String,
    val changeLog: String = ""
)

// --- Response DTOs ---

data class TemplateResponse(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class TemplateDetailResponse(
    val id: String,
    val name: String,
    val description: String,
    val activeVersion: VersionResponse?,
    val versions: List<VersionResponse>,
    val createdAt: Long,
    val updatedAt: Long
)

data class VersionResponse(
    val id: String,
    val templateId: String,
    val version: Int,
    val content: String,
    val status: String,
    val changeLog: String,
    val createdAt: Long
)

// --- Mapping extensions ---

private fun PromptTemplate.toResponse() = TemplateResponse(
    id = id,
    name = name,
    description = description,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)

private fun PromptTemplate.toDetailResponse(
    versions: List<PromptVersion>,
    activeVersion: PromptVersion?
) = TemplateDetailResponse(
    id = id,
    name = name,
    description = description,
    activeVersion = activeVersion?.toResponse(),
    versions = versions.map { it.toResponse() },
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli()
)

private fun PromptVersion.toResponse() = VersionResponse(
    id = id,
    templateId = templateId,
    version = version,
    content = content,
    status = status.name,
    changeLog = changeLog,
    createdAt = createdAt.toEpochMilli()
)
