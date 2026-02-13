package com.arc.reactor.intent

import com.arc.reactor.intent.model.IntentDefinition
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Intent Registry Interface
 *
 * Manages CRUD operations for intent definitions.
 * Intent definitions describe the types of user requests the system can handle
 * and the pipeline configuration to apply for each.
 *
 * @see InMemoryIntentRegistry for default implementation
 */
interface IntentRegistry {

    /**
     * List all intent definitions, ordered by name.
     */
    fun list(): List<IntentDefinition>

    /**
     * List only enabled intent definitions.
     */
    fun listEnabled(): List<IntentDefinition>

    /**
     * Get an intent definition by name.
     *
     * @return IntentDefinition if found, null otherwise
     */
    fun get(intentName: String): IntentDefinition?

    /**
     * Register or update an intent definition.
     *
     * @return The saved intent definition
     */
    fun save(intent: IntentDefinition): IntentDefinition

    /**
     * Delete an intent definition by name. Idempotent.
     */
    fun delete(intentName: String)
}

/**
 * In-Memory Intent Registry
 *
 * Thread-safe implementation using [ConcurrentHashMap].
 * Not persistent — data is lost on server restart.
 */
class InMemoryIntentRegistry : IntentRegistry {

    private val intents = ConcurrentHashMap<String, IntentDefinition>()

    override fun list(): List<IntentDefinition> {
        return intents.values.sortedBy { it.name }
    }

    override fun listEnabled(): List<IntentDefinition> {
        return intents.values.filter { it.enabled }.sortedBy { it.name }
    }

    override fun get(intentName: String): IntentDefinition? = intents[intentName]

    override fun save(intent: IntentDefinition): IntentDefinition {
        val existing = intents[intent.name]
        val toSave = if (existing != null) {
            intent.copy(createdAt = existing.createdAt, updatedAt = Instant.now())
        } else {
            intent
        }
        intents[toSave.name] = toSave
        return toSave
    }

    override fun delete(intentName: String) {
        intents.remove(intentName)
    }
}
