package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.hook.model.HookContext
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.util.UUID

internal data class AgentRunContext(
    val runId: String,
    val hookContext: HookContext
)

internal class AgentRunContextManager(
    private val runIdSupplier: () -> String = { UUID.randomUUID().toString() }
) {

    suspend fun open(command: AgentCommand, toolsUsed: MutableList<String>): AgentRunContext {
        val runId = runIdSupplier()
        val userId = command.userId ?: "anonymous"
        val userEmail = resolveUserEmail(command.metadata)

        // Build MDC map explicitly and pass to MDCContext(map) instead of relying
        // on MDCContext() capturing thread-local state. This avoids a race where
        // concurrent coroutines on the same thread overwrite each other's
        // thread-local MDC between MDC.put() and MDCContext snapshot.
        val mdcMap = buildMap {
            put("runId", runId)
            put("userId", userId)
            userEmail?.let { put("userEmail", it) }
            command.metadata["sessionId"]?.toString()?.let { put("sessionId", it) }
        }
        // Also set thread-local MDC for logging before the next suspend point.
        mdcMap.forEach { (k, v) -> MDC.put(k, v) }

        val hookContext = HookContext(
            runId = runId,
            userId = userId,
            userEmail = userEmail,
            userPrompt = command.userPrompt,
            channel = command.metadata["channel"]?.toString(),
            toolsUsed = toolsUsed
        )
        hookContext.metadata.putAll(command.metadata)
        hookContext.metadata["runId"] = runId

        return withContext(MDCContext(mdcMap)) {
            AgentRunContext(runId = runId, hookContext = hookContext)
        }
    }

    fun close() {
        MDC.remove("runId")
        MDC.remove("userId")
        MDC.remove("userEmail")
        MDC.remove("sessionId")
    }

    private fun resolveUserEmail(metadata: Map<String, Any>): String? {
        val candidates = listOf(
            "requesterEmail",
            "slackUserEmail",
            "userEmail",
            "requesterAccountId",
            "accountId"
        )
        return candidates.asSequence()
            .mapNotNull { key -> metadata[key]?.toString()?.trim()?.takeIf { it.isNotBlank() } }
            .firstOrNull()
    }
}
