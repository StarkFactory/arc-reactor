package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.MDC

@Tag("matrix")
class AgentRunContextManagerFuzzTest {

    @AfterEach
    fun cleanup() {
        MDC.remove("runId")
        MDC.remove("userId")
        MDC.remove("sessionId")
    }

    @Test
    fun `open and close should maintain MDC and metadata invariants across 300 cases`() {
        var runCounter = 0
        val manager = AgentRunContextManager(runIdSupplier = { "run-${runCounter++}" })
        val sessionValues = listOf<Any>("s-1", 42, 99L, true, object {
            override fun toString(): String = "obj-session"
        })

        repeat(300) { i ->
            val toolsUsed = CopyOnWriteArrayList<String>()
            val includeSession = i % 3 != 0
            val metadata = buildMap<String, Any> {
                put("channel", if (i % 2 == 0) "web" else "slack")
                put("traceId", "trace-$i")
                if (includeSession) {
                    put("sessionId", sessionValues[i % sessionValues.size])
                }
            }

            val command = AgentCommand(
                systemPrompt = "sys",
                userPrompt = "line-$i",
                userId = if (i % 4 == 0) null else "user-$i",
                metadata = metadata
            )

            val context = manager.open(command, toolsUsed)

            assertEquals("run-$i", context.runId)
            assertEquals("run-$i", MDC.get("runId"))
            assertEquals(command.userId ?: "anonymous", context.hookContext.userId)
            assertEquals(command.userId ?: "anonymous", MDC.get("userId"))
            assertEquals(metadata["channel"]?.toString(), context.hookContext.channel)
            assertEquals("trace-$i", context.hookContext.metadata["traceId"])

            if (includeSession) {
                assertEquals(metadata["sessionId"].toString(), MDC.get("sessionId"))
            } else {
                assertNull(MDC.get("sessionId"))
            }

            manager.close()
            assertNull(MDC.get("runId"))
            assertNull(MDC.get("userId"))
            assertNull(MDC.get("sessionId"))
        }
    }

    @Test
    fun `toolsUsed reference should be preserved in hook context`() {
        val manager = AgentRunContextManager(runIdSupplier = { "run-tools" })
        val toolsUsed = CopyOnWriteArrayList<String>()
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "line", userId = "u")

        val context = manager.open(command, toolsUsed)
        toolsUsed.add("tool-a")
        toolsUsed.add("tool-b")

        assertTrue(context.hookContext.toolsUsed === toolsUsed)
        assertEquals(listOf("tool-a", "tool-b"), context.hookContext.toolsUsed.toList())

        manager.close()
    }
}
