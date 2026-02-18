package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import com.arc.reactor.hook.model.HookContext
import org.slf4j.MDC
import java.util.UUID

internal data class AgentRunContext(
    val runId: String,
    val hookContext: HookContext
)

internal class AgentRunContextManager(
    private val runIdSupplier: () -> String = { UUID.randomUUID().toString() }
) {

    fun open(command: AgentCommand, toolsUsed: MutableList<String>): AgentRunContext {
        val runId = runIdSupplier()
        val userId = command.userId ?: "anonymous"

        MDC.put("runId", runId)
        MDC.put("userId", userId)
        command.metadata["sessionId"]?.toString()?.let { MDC.put("sessionId", it) }

        val hookContext = HookContext(
            runId = runId,
            userId = userId,
            userPrompt = command.userPrompt,
            channel = command.metadata["channel"]?.toString(),
            toolsUsed = toolsUsed
        )
        hookContext.metadata.putAll(command.metadata)
        return AgentRunContext(runId = runId, hookContext = hookContext)
    }

    fun close() {
        MDC.remove("runId")
        MDC.remove("userId")
        MDC.remove("sessionId")
    }
}
