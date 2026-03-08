package com.arc.reactor.cache

import com.arc.reactor.agent.model.AgentCommand
import java.security.MessageDigest

/**
 * Builds deterministic cache keys from agent commands and tool names.
 *
 * Uses SHA-256 hash of: systemPrompt + userPrompt + sorted tool names.
 */
object CacheKeyBuilder {
    private const val SESSION_ID_KEY = "sessionId"
    private const val TENANT_ID_KEY = "tenantId"
    private val IDENTITY_METADATA_KEYS = listOf("requesterEmail", "userEmail", "slackUserEmail")

    fun buildKey(command: AgentCommand, toolNames: List<String>): String {
        val scopeFingerprint = buildScopeFingerprint(command, toolNames)
        val raw = listOf(scopeFingerprint, command.userPrompt).joinToString("|")
        return sha256(raw)
    }

    /**
     * Build a stable scope fingerprint used by semantic caches.
     *
     * The scope includes all fields that can materially change an answer even when the user prompt
     * is semantically similar: persona/system prompt, model, structured output contract, tool set,
     * and identity/tenant/session boundaries.
     */
    fun buildScopeFingerprint(command: AgentCommand, toolNames: List<String>): String {
        val parts = buildList {
            add(command.systemPrompt.orEmpty())
            add(toolNames.sorted().joinToString(","))
            add(command.model.orEmpty())
            add(command.mode.name)
            add(command.responseFormat.name)
            add(command.responseSchema.orEmpty())
            add(command.userId.orEmpty())
            add(command.metadata[SESSION_ID_KEY]?.toString().orEmpty())
            add(command.metadata[TENANT_ID_KEY]?.toString().orEmpty())
            add(resolveIdentityScope(command))
        }
        return sha256(parts.joinToString("|"))
    }

    private fun resolveIdentityScope(command: AgentCommand): String {
        return IDENTITY_METADATA_KEYS.asSequence()
            .mapNotNull { key -> command.metadata[key]?.toString()?.trim()?.takeIf { it.isNotBlank() } }
            .firstOrNull()
            ?.lowercase()
            .orEmpty()
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
