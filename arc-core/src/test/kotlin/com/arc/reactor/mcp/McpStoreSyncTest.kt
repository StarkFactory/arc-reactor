package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpTransportType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class McpStoreSyncTest {

    private val runtimeServer = McpServer(
        name = "runtime-only",
        transportType = McpTransportType.STDIO,
        config = mapOf("command" to "echo")
    )

    @Test
    fun `loadAll should return empty list when store list throws`() {
        val failingStore = mockk<McpServerStore>()
        every { failingStore.list() } throws RuntimeException("relation mcp_servers does not exist")

        val sync = McpStoreSync(failingStore)
        val loaded = sync.loadAll()

        assertEquals(emptyList<McpServer>(), loaded) {
            "loadAll should fail open and return empty list when store.list throws"
        }
    }

    @Test
    fun `listOr should fallback to runtime servers when store list throws`() {
        val failingStore = mockk<McpServerStore>()
        every { failingStore.list() } throws RuntimeException("column config does not exist")

        val sync = McpStoreSync(failingStore)
        val listed = sync.listOr(listOf(runtimeServer))

        assertEquals(listOf(runtimeServer), listed) {
            "listOr should return runtime registry servers when store.list throws"
        }
    }

    @Test
    fun `saveIfAbsent should swallow findByName exception`() {
        val failingStore = mockk<McpServerStore>()
        every { failingStore.findByName(any()) } throws RuntimeException("invalid table schema")

        val sync = McpStoreSync(failingStore)

        assertDoesNotThrow({ sync.saveIfAbsent(runtimeServer) }) {
            "saveIfAbsent should not propagate store.findByName exceptions"
        }
        verify(exactly = 0) { failingStore.save(any()) }
    }

    @Test
    fun `delete should swallow store delete exception`() {
        val failingStore = mockk<McpServerStore>()
        every { failingStore.delete(any()) } throws RuntimeException("delete failed")

        val sync = McpStoreSync(failingStore)

        assertDoesNotThrow({ sync.delete("runtime-only") }) {
            "delete should not propagate store.delete exceptions"
        }
        verify(exactly = 1) { failingStore.delete("runtime-only") }
    }
}
