package com.arc.reactor.guard.output.policy

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime-manageable output guard rule.
 */
data class OutputGuardRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val pattern: String,
    val action: OutputGuardRuleAction = OutputGuardRuleAction.MASK,
    val enabled: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
)

enum class OutputGuardRuleAction {
    MASK,
    REJECT
}

/**
 * Store for dynamic output guard rules.
 */
interface OutputGuardRuleStore {
    fun list(): List<OutputGuardRule>
    fun findById(id: String): OutputGuardRule?
    fun save(rule: OutputGuardRule): OutputGuardRule
    fun update(id: String, rule: OutputGuardRule): OutputGuardRule?
    fun delete(id: String)
}

class InMemoryOutputGuardRuleStore : OutputGuardRuleStore {
    private val rules = ConcurrentHashMap<String, OutputGuardRule>()

    override fun list(): List<OutputGuardRule> {
        return rules.values.sortedBy { it.createdAt }
    }

    override fun findById(id: String): OutputGuardRule? = rules[id]

    override fun save(rule: OutputGuardRule): OutputGuardRule {
        val now = Instant.now()
        val toSave = rule.copy(createdAt = now, updatedAt = now)
        rules[toSave.id] = toSave
        return toSave
    }

    override fun update(id: String, rule: OutputGuardRule): OutputGuardRule? {
        val existing = rules[id] ?: return null
        val updated = rule.copy(
            id = id,
            createdAt = existing.createdAt,
            updatedAt = Instant.now()
        )
        rules[id] = updated
        return updated
    }

    override fun delete(id: String) {
        rules.remove(id)
    }
}
