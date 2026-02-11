package com.arc.reactor.cache

import com.arc.reactor.agent.model.AgentCommand
import java.security.MessageDigest

/**
 * Builds deterministic cache keys from agent commands and tool names.
 *
 * Uses SHA-256 hash of: systemPrompt + userPrompt + sorted tool names.
 */
object CacheKeyBuilder {

    fun buildKey(command: AgentCommand, toolNames: List<String>): String {
        val parts = buildList {
            add(command.systemPrompt.orEmpty())
            add(command.userPrompt)
            add(toolNames.sorted().joinToString(","))
            add(command.model.orEmpty())
        }
        val raw = parts.joinToString("|")
        return sha256(raw)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
