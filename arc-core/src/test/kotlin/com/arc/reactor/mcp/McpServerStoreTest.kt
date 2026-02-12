package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpTransportType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class McpServerStoreTest {

    private lateinit var store: InMemoryMcpServerStore

    @BeforeEach
    fun setUp() {
        store = InMemoryMcpServerStore()
    }

    @Nested
    inner class Save {

        @Test
        fun `should save and retrieve server by name`() {
            val server = McpServer(
                name = "test-server",
                description = "Test",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://localhost:8081/sse")
            )

            val saved = store.save(server)
            val found = store.findByName("test-server")

            assertNotNull(found) { "Saved server should be retrievable by name" }
            assertEquals("test-server", found!!.name, "Server name should match")
            assertEquals("Test", found.description, "Description should match")
            assertEquals(McpTransportType.SSE, found.transportType, "Transport type should match")
        }

        @Test
        fun `should reject duplicate server name`() {
            store.save(McpServer(name = "dup", transportType = McpTransportType.SSE))

            assertThrows(IllegalArgumentException::class.java) {
                store.save(McpServer(name = "dup", transportType = McpTransportType.SSE))
            }
        }

        @Test
        fun `should set timestamps on save`() {
            val saved = store.save(McpServer(name = "ts-test", transportType = McpTransportType.SSE))

            assertNotNull(saved.createdAt) { "createdAt should be set" }
            assertNotNull(saved.updatedAt) { "updatedAt should be set" }
        }
    }

    @Nested
    inner class List {

        @Test
        fun `should return empty list when no servers`() {
            val servers = store.list()
            assertTrue(servers.isEmpty()) { "Expected empty list, got ${servers.size}" }
        }

        @Test
        fun `should list all saved servers`() {
            store.save(McpServer(name = "server-1", transportType = McpTransportType.SSE))
            store.save(McpServer(name = "server-2", transportType = McpTransportType.STDIO, config = mapOf("command" to "echo")))

            val servers = store.list()
            assertEquals(2, servers.size, "Should list 2 servers")
        }

        @Test
        fun `should list servers sorted by createdAt`() {
            store.save(McpServer(name = "b-server", transportType = McpTransportType.SSE))
            store.save(McpServer(name = "a-server", transportType = McpTransportType.SSE))

            val servers = store.list()
            assertTrue(
                servers[0].createdAt <= servers[1].createdAt
            ) { "Servers should be sorted by createdAt ascending" }
        }
    }

    @Nested
    inner class FindByName {

        @Test
        fun `should return null for unknown server`() {
            val found = store.findByName("nonexistent")
            assertNull(found) { "Should return null for unknown server" }
        }
    }

    @Nested
    inner class Update {

        @Test
        fun `should update server config`() {
            store.save(McpServer(
                name = "update-test",
                description = "Original",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://old:8081/sse")
            ))

            val updated = store.update("update-test", McpServer(
                name = "update-test",
                description = "Updated",
                transportType = McpTransportType.SSE,
                config = mapOf("url" to "http://new:8081/sse"),
                autoConnect = true
            ))

            assertNotNull(updated) { "Update should return updated server" }
            assertEquals("Updated", updated!!.description, "Description should be updated")
            assertEquals(mapOf("url" to "http://new:8081/sse"), updated.config, "Config should be updated")
            assertTrue(updated.autoConnect) { "autoConnect should be updated" }
        }

        @Test
        fun `should preserve id and createdAt on update`() {
            val saved = store.save(McpServer(name = "preserve-test", transportType = McpTransportType.SSE))
            val updated = store.update("preserve-test", McpServer(
                name = "preserve-test",
                description = "Changed",
                transportType = McpTransportType.SSE
            ))

            assertEquals(saved.id, updated!!.id, "ID should be preserved on update")
            assertEquals(saved.createdAt, updated.createdAt, "createdAt should be preserved on update")
            assertTrue(updated.updatedAt >= saved.updatedAt) { "updatedAt should be updated" }
        }

        @Test
        fun `should return null for unknown server update`() {
            val result = store.update("nonexistent", McpServer(name = "nonexistent", transportType = McpTransportType.SSE))
            assertNull(result) { "Update of nonexistent server should return null" }
        }
    }

    @Nested
    inner class Delete {

        @Test
        fun `should delete existing server`() {
            store.save(McpServer(name = "delete-test", transportType = McpTransportType.SSE))

            store.delete("delete-test")

            assertNull(store.findByName("delete-test")) { "Deleted server should not be found" }
            assertTrue(store.list().isEmpty()) { "List should be empty after delete" }
        }

        @Test
        fun `should handle delete of nonexistent server gracefully`() {
            // Should not throw
            store.delete("nonexistent")
        }
    }
}
