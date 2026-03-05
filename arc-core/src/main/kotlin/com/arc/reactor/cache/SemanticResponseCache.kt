package com.arc.reactor.cache

import com.arc.reactor.agent.model.AgentCommand

/**
 * Optional extension for semantic cache lookup/write.
 *
 * Implementations can return a cached response for semantically similar prompts when exact key
 * matching misses, while still respecting request scope boundaries.
 */
interface SemanticResponseCache : ResponseCache {

    suspend fun getSemantic(command: AgentCommand, toolNames: List<String>, exactKey: String): CachedResponse?

    suspend fun putSemantic(
        command: AgentCommand,
        toolNames: List<String>,
        exactKey: String,
        response: CachedResponse
    )
}
