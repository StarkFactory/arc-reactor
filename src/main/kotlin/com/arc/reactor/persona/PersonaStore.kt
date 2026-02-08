package com.arc.reactor.persona

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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
 * @param createdAt Creation timestamp
 * @param updatedAt Last modification timestamp
 */
data class Persona(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val isDefault: Boolean = false,
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
    fun update(personaId: String, name: String?, systemPrompt: String?, isDefault: Boolean?): Persona?

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
        if (persona.isDefault) {
            clearDefault()
        }
        personas[persona.id] = persona
        return persona
    }

    override fun update(personaId: String, name: String?, systemPrompt: String?, isDefault: Boolean?): Persona? {
        val existing = personas[personaId] ?: return null

        if (isDefault == true) {
            clearDefault()
        }

        val updated = existing.copy(
            name = name ?: existing.name,
            systemPrompt = systemPrompt ?: existing.systemPrompt,
            isDefault = isDefault ?: existing.isDefault,
            updatedAt = Instant.now()
        )
        personas[personaId] = updated
        return updated
    }

    override fun delete(personaId: String) {
        personas.remove(personaId)
    }

    private fun clearDefault() {
        for ((id, persona) in personas) {
            if (persona.isDefault) {
                personas[id] = persona.copy(isDefault = false, updatedAt = Instant.now())
            }
        }
    }
}
