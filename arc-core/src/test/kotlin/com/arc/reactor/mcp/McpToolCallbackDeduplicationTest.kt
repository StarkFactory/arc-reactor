package com.arc.reactor.mcp

import com.arc.reactor.tool.ToolCallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpToolCallbackDeduplicationTest {

    @Test
    fun `keeps first duplicate by sorted server name and preserves deterministic callback order`() {
        val duplicateFromZ = testCallback("duplicate-tool")
        val duplicateFromA = testCallback("duplicate-tool")
        val callbacksByServer = mapOf(
            "z-server" to listOf(duplicateFromZ, testCallback("z-only-tool")),
            "a-server" to listOf(duplicateFromA, testCallback("a-only-tool"))
        )
        val duplicateEvents = mutableListOf<String>()

        val deduplicated = deduplicateCallbacksByName(callbacksByServer) { tool, kept, dropped ->
            duplicateEvents.add("$tool:$kept:$dropped")
        }

        assertEquals(
            listOf("duplicate-tool", "a-only-tool", "z-only-tool"),
            deduplicated.map { it.name },
            "Expected deterministic tool order from sorted server names with duplicate removed"
        )
        assertTrue(
            deduplicated.first() === duplicateFromA,
            "Expected duplicate resolution to keep callback from lexicographically first server"
        )
        assertEquals(
            listOf("duplicate-tool:a-server:z-server"),
            duplicateEvents,
            "Expected duplicate callback metadata to include kept/dropped servers"
        )
    }

    @Test
    fun `returns empty list for empty server map`() {
        val deduplicated = deduplicateCallbacksByName(emptyMap())
        assertTrue(deduplicated.isEmpty(), "Expected empty deduplicated callbacks when no servers are provided")
    }

    private fun testCallback(name: String): ToolCallback {
        return object : ToolCallback {
            override val name: String = name
            override val description: String = "test-$name"
            override suspend fun call(arguments: Map<String, Any?>): Any? = "ok"
        }
    }
}
