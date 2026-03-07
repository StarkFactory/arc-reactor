package com.arc.reactor.persona

import com.arc.reactor.prompt.PromptTemplateStore
import mu.KotlinLogging
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Persona — a named system prompt template.
 *
 * Personas allow administrators to manage system prompts centrally.
 * Users select a persona by ID instead of typing system prompts manually.
 *
 * @param id Unique identifier (UUID or "default")
 * @param name Display name (e.g. "Customer Support Agent", "Python Expert")
 * @param systemPrompt The actual system prompt text sent to the LLM
 * @param isDefault Whether this is the default persona (at most one)
 * @param description Short description of what this persona does
 * @param responseGuideline Additional response style/format instructions appended to systemPrompt
 * @param welcomeMessage Initial greeting message shown when persona is selected
 * @param icon Emoji or short icon identifier for UI display
 * @param isActive Whether this persona is available for selection
 * @param promptTemplateId Optional linked versioned prompt template that overrides systemPrompt at runtime
 * @param createdAt Creation timestamp
 * @param updatedAt Last modification timestamp
 */
data class Persona(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val isDefault: Boolean = false,
    val description: String? = null,
    val responseGuideline: String? = null,
    val welcomeMessage: String? = null,
    val icon: String? = null,
    val isActive: Boolean = true,
    val promptTemplateId: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

/**
 * Persona Store Interface
 *
 * Manages CRUD operations for personas.
 * Implementations must guarantee that at most one persona has [isDefault] = true.
 *
 * @see InMemoryPersonaStore for default implementation
 */
interface PersonaStore {

    /**
     * List all personas, ordered by creation time ascending.
     */
    fun list(): List<Persona>

    /**
     * Get a persona by ID.
     *
     * @return Persona if found, null otherwise
     */
    fun get(personaId: String): Persona?

    /**
     * Get the default persona (the one with isDefault = true).
     *
     * @return Default persona if one exists, null otherwise
     */
    fun getDefault(): Persona?

    /**
     * Save a new persona. If [Persona.isDefault] is true, any existing default is cleared.
     *
     * @return The saved persona
     */
    fun save(persona: Persona): Persona

    /**
     * Partially update a persona. Only non-null fields are applied.
     * If [isDefault] is set to true, any existing default is cleared.
     *
     * @return Updated persona, or null if not found
     */
    fun update(
        personaId: String,
        name: String? = null,
        systemPrompt: String? = null,
        isDefault: Boolean? = null,
        description: String? = null,
        responseGuideline: String? = null,
        welcomeMessage: String? = null,
        icon: String? = null,
        promptTemplateId: String? = null,
        isActive: Boolean? = null
    ): Persona?

    /**
     * Delete a persona by ID. Idempotent — no error if not found.
     */
    fun delete(personaId: String)
}

/**
 * Default system prompt — same as ChatController's fallback.
 */
internal const val DEFAULT_SYSTEM_PROMPT =
    "You are a helpful AI assistant. You can use tools when needed. " +
        "Answer in the same language as the user's message."

/**
 * In-Memory Persona Store
 *
 * Thread-safe implementation using [ConcurrentHashMap].
 * Pre-loads a default persona on construction.
 * Not persistent — data is lost on server restart.
 */
class InMemoryPersonaStore : PersonaStore {

    private val personas = ConcurrentHashMap<String, Persona>()

    init {
        val defaultPersona = Persona(
            id = "default",
            name = "Default Assistant",
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            isDefault = true
        )
        personas[defaultPersona.id] = defaultPersona
    }

    override fun list(): List<Persona> {
        return personas.values.sortedBy { it.createdAt }
    }

    override fun get(personaId: String): Persona? = personas[personaId]

    override fun getDefault(): Persona? = personas.values.firstOrNull { it.isDefault }

    override fun save(persona: Persona): Persona {
        synchronized(this) {
            if (persona.isDefault) {
                clearDefault()
            }
            personas[persona.id] = persona
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
        promptTemplateId: String?,
        isActive: Boolean?
    ): Persona? {
        synchronized(this) {
            val existing = personas[personaId] ?: return null

            if (isDefault == true) {
                clearDefault()
            }

            val updated = existing.copy(
                name = name ?: existing.name,
                systemPrompt = systemPrompt ?: existing.systemPrompt,
                isDefault = isDefault ?: existing.isDefault,
                description = resolveNullableField(description, existing.description),
                responseGuideline = resolveNullableField(responseGuideline, existing.responseGuideline),
                welcomeMessage = resolveNullableField(welcomeMessage, existing.welcomeMessage),
                icon = resolveNullableField(icon, existing.icon),
                promptTemplateId = resolveNullableField(promptTemplateId, existing.promptTemplateId),
                isActive = isActive ?: existing.isActive,
                updatedAt = Instant.now()
            )
            personas[personaId] = updated
            return updated
        }
    }

    override fun delete(personaId: String) {
        personas.remove(personaId)
    }

    /**
     * Resolve nullable field update: null = no change, empty = clear to null, value = set.
     */
    private fun resolveNullableField(newValue: String?, existing: String?): String? {
        return when {
            newValue == null -> existing
            newValue.isEmpty() -> null
            else -> newValue
        }
    }

    private fun clearDefault() {
        for ((id, persona) in personas) {
            if (persona.isDefault) {
                personas[id] = persona.copy(isDefault = false, updatedAt = Instant.now())
            }
        }
    }
}

fun Persona.resolveEffectivePrompt(promptTemplateStore: PromptTemplateStore?): String {
    val base = resolveBasePrompt(promptTemplateStore)
    val guideline = responseGuideline?.trim()
    return if (!guideline.isNullOrBlank()) "$base\n\n$guideline" else base
}

private fun Persona.resolveBasePrompt(promptTemplateStore: PromptTemplateStore?): String {
    if (promptTemplateId.isNullOrBlank() || promptTemplateStore == null) return systemPrompt
    return try {
        promptTemplateStore.getActiveVersion(promptTemplateId)?.content?.takeIf { it.isNotBlank() } ?: systemPrompt
    } catch (e: Exception) {
        logger.warn(e) { "Prompt template lookup failed for personaId='$id' promptTemplateId='$promptTemplateId'" }
        systemPrompt
    }
}
