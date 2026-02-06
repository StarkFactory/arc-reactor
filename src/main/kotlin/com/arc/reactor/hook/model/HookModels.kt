package com.arc.reactor.hook.model

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hook execution result
 */
sealed class HookResult {
    /** Continue processing */
    data object Continue : HookResult()

    /** Reject execution */
    data class Reject(val reason: String) : HookResult()

    /** Continue after modifying parameters */
    data class Modify(val modifiedParams: Map<String, Any>) : HookResult()

    /** Pending approval (async approval workflow) */
    data class PendingApproval(
        val approvalId: String,
        val message: String
    ) : HookResult()
}

/**
 * Agent execution hook context
 */
data class HookContext(
    val runId: String,
    val userId: String,
    val userEmail: String? = null,
    val userPrompt: String,
    val channel: String? = null,
    val startedAt: Instant = Instant.now(),
    val toolsUsed: MutableList<String> = CopyOnWriteArrayList(),
    val metadata: MutableMap<String, Any> = ConcurrentHashMap()
) {
    fun durationMs(): Long = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
}

/**
 * Tool call hook context
 */
data class ToolCallContext(
    val agentContext: HookContext,
    val toolName: String,
    val toolParams: Map<String, Any?>,
    val callIndex: Int
) {
    /** Mask sensitive parameters */
    fun maskedParams(): Map<String, Any?> {
        val sensitiveKeys = setOf("password", "token", "secret", "key", "credential", "apikey")
        return toolParams.mapValues { (key, value) ->
            if (sensitiveKeys.any { key.lowercase().contains(it) }) "***" else value
        }
    }
}

/**
 * Tool call result
 */
data class ToolCallResult(
    val success: Boolean,
    val output: String? = null,
    val errorMessage: String? = null,
    val durationMs: Long = 0
)

/**
 * Agent response result
 */
data class AgentResponse(
    val success: Boolean,
    val response: String? = null,
    val errorMessage: String? = null,
    val toolsUsed: List<String> = emptyList(),
    val totalDurationMs: Long = 0
)
