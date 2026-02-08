package com.arc.reactor.prompt

import java.time.Instant

/**
 * Prompt Template — a named container for versioned system prompts.
 *
 * Templates have a unique [name] and contain multiple [PromptVersion]s.
 * Only one version per template can be [VersionStatus.ACTIVE] at a time.
 *
 * @param id Unique identifier (UUID)
 * @param name Unique name key (e.g., "customer-support", "code-reviewer")
 * @param description Human-readable description of this template's purpose
 * @param createdAt Creation timestamp
 * @param updatedAt Last modification timestamp
 */
data class PromptTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * Prompt Version — a specific version of a [PromptTemplate].
 *
 * Versions are numbered sequentially (1, 2, 3...) per template.
 * Status transitions: [VersionStatus.DRAFT] → [VersionStatus.ACTIVE] → [VersionStatus.ARCHIVED].
 *
 * @param id Unique identifier (UUID)
 * @param templateId Foreign key to [PromptTemplate.id]
 * @param version Sequential version number (auto-incremented per template)
 * @param content The actual system prompt text sent to the LLM
 * @param status Current lifecycle status
 * @param changeLog Description of changes in this version
 * @param createdAt Creation timestamp
 */
data class PromptVersion(
    val id: String,
    val templateId: String,
    val version: Int,
    val content: String,
    val status: VersionStatus = VersionStatus.DRAFT,
    val changeLog: String = "",
    val createdAt: Instant = Instant.now()
)

/**
 * Version lifecycle status.
 *
 * Each template can have at most one [ACTIVE] version at a time.
 * Activating a new version automatically archives the previous active version.
 */
enum class VersionStatus {
    /** Work in progress — not yet deployed to production */
    DRAFT,

    /** Currently serving in production (at most one per template) */
    ACTIVE,

    /** Retired — preserved for history, no longer in use */
    ARCHIVED
}
