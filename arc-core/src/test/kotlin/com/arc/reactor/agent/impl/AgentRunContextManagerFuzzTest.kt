package com.arc.reactor.agent.impl

import com.arc.reactor.agent.model.AgentCommand
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Tag("matrix")
/**
 * AgentRunContextManager의 퍼즈 테스트.
 *
 * 비정상적인 입력에 대한 컨텍스트 관리 안정성을 검증합니다.
 */
class AgentRunContextManagerFuzzTest {

    @Test
    fun `open and close은(는) maintain MDC and metadata invariants across 300 cases해야 한다`() = runTest {
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
                if (i % 5 == 0) {
                    put("requesterEmail", "user-$i@example.com")
                }
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

            // open()은 withContext(MDCContext)를 사용하므로 thread-local MDC가 아닌
            // hookContext를 통해 메타데이터를 검증한다.
            assertEquals("run-$i", context.runId, "runId should match supplier at index $i")
            assertEquals("run-$i", context.hookContext.metadata["runId"], "hookContext metadata runId at index $i")
            assertEquals(command.userId ?: "anonymous", context.hookContext.userId, "userId should resolve correctly at index $i")
            assertEquals(metadata["channel"]?.toString(), context.hookContext.channel, "channel should match at index $i")
            assertEquals("trace-$i", context.hookContext.metadata["traceId"], "traceId should be copied at index $i")
            assertEquals(metadata["requesterEmail"]?.toString(), context.hookContext.userEmail, "userEmail should resolve at index $i")

            if (includeSession) {
                assertEquals(
                    metadata["sessionId"].toString(),
                    context.hookContext.metadata["sessionId"]?.toString(),
                    "hookContext sessionId should be set at index $i"
                )
            } else {
                assertNull(
                    context.hookContext.metadata["sessionId"],
                    "hookContext sessionId should be null when no sessionId provided at index $i"
                )
            }

            manager.close()
        }
    }

    @Test
    fun `toolsUsed reference은(는) be preserved in hook context해야 한다`() = runTest {
        val manager = AgentRunContextManager(runIdSupplier = { "run-tools" })
        val toolsUsed = CopyOnWriteArrayList<String>()
        val command = AgentCommand(systemPrompt = "sys", userPrompt = "line", userId = "u")

        val context = manager.open(command, toolsUsed)
        toolsUsed.add("tool-a")
        toolsUsed.add("tool-b")

        assertTrue(context.hookContext.toolsUsed === toolsUsed, "hookContext.toolsUsed should be the same reference as the passed-in list")
        assertEquals(listOf("tool-a", "tool-b"), context.hookContext.toolsUsed.toList(), "toolsUsed list contents should match")

        manager.close()
    }
}
