package com.arc.reactor.mcp

import com.arc.reactor.mcp.model.McpServer
import com.arc.reactor.mcp.model.McpTransportType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType

class JdbcMcpServerStoreTest {

    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var store: JdbcMcpServerStore

    @BeforeEach
    fun setup() {
        val dataSource = EmbeddedDatabaseBuilder()
            .setType(EmbeddedDatabaseType.H2)
            .generateUniqueName(true)
            .build()

        jdbcTemplate = JdbcTemplate(dataSource)

        // V7 DDL
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS mcp_servers (
                id              VARCHAR(36)     PRIMARY KEY,
                name            VARCHAR(100)    NOT NULL,
                description     VARCHAR(500),
                transport_type  VARCHAR(20)     NOT NULL,
                config          TEXT            NOT NULL DEFAULT '{}',
                version         VARCHAR(50),
                auto_connect    BOOLEAN         NOT NULL DEFAULT FALSE,
                created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())
        jdbcTemplate.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_mcp_servers_name ON mcp_servers(name)"
        )

        store = JdbcMcpServerStore(jdbcTemplate)
    }

    private fun createServer(
        name: String = "test-server",
        description: String? = "A test MCP server",
        transportType: McpTransportType = McpTransportType.SSE,
        config: Map<String, Any> = mapOf("url" to "http://localhost:3000"),
        version: String? = "1.0.0",
        autoConnect: Boolean = false
    ) = McpServer(
        name = name,
        description = description,
        transportType = transportType,
        config = config,
        version = version,
        autoConnect = autoConnect
    )

    @Nested
    inner class BasicCrud {

        @Test
        fun `should save and find by name`() {
            val server = createServer()
            store.save(server)

            val found = store.findByName("test-server")

            assertNotNull(found) { "Saved server should be retrievable by name" }
            assertEquals("test-server", found!!.name) { "Name should match" }
            assertEquals("A test MCP server", found.description) { "Description should match" }
            assertEquals(McpTransportType.SSE, found.transportType) { "Transport type should match" }
            assertEquals("1.0.0", found.version) { "Version should match" }
            assertFalse(found.autoConnect) { "autoConnect should be false" }
        }

        @Test
        fun `should list servers ordered by createdAt`() {
            store.save(createServer(name = "server-a"))
            Thread.sleep(10)
            store.save(createServer(name = "server-b"))

            val list = store.list()

            assertEquals(2, list.size) { "Should have 2 servers" }
            assertEquals("server-a", list[0].name) { "First created should be first" }
            assertEquals("server-b", list[1].name) { "Second created should be second" }
        }

        @Test
        fun `should delete server`() {
            store.save(createServer())
            assertNotNull(store.findByName("test-server")) { "Should exist before delete" }

            store.delete("test-server")

            assertNull(store.findByName("test-server"), "Should be null after delete")
        }

        @Test
        fun `should return null for unknown server`() {
            assertNull(store.findByName("nonexistent"), "Unknown server should return null")
        }

        @Test
        fun `should return empty list when no servers`() {
            val list = store.list()

            assertTrue(list.isEmpty()) { "Should return empty list, got ${list.size}" }
        }
    }

    @Nested
    inner class JsonConfig {

        @Test
        fun `should roundtrip config map`() {
            val config = mapOf("url" to "http://localhost:3000", "apiKey" to "secret-123")
            store.save(createServer(config = config))

            val found = store.findByName("test-server")!!

            assertEquals("http://localhost:3000", found.config["url"]) { "url config should roundtrip" }
            assertEquals("secret-123", found.config["apiKey"]) { "apiKey config should roundtrip" }
        }

        @Test
        fun `should handle empty config`() {
            store.save(createServer(config = emptyMap()))

            val found = store.findByName("test-server")!!

            assertTrue(found.config.isEmpty()) { "Empty config should roundtrip as empty map, got: ${found.config}" }
        }

        @Test
        fun `should handle nested config values`() {
            val config = mapOf(
                "url" to "http://localhost:3000",
                "options" to mapOf("timeout" to 5000, "retries" to 3)
            )
            store.save(createServer(config = config))

            val found = store.findByName("test-server")!!

            assertNotNull(found.config["options"]) { "Nested config should be preserved" }
            @Suppress("UNCHECKED_CAST")
            val options = found.config["options"] as Map<String, Any>
            assertEquals(5000, options["timeout"]) { "Nested timeout value should roundtrip" }
        }
    }

    @Nested
    inner class UpdateBehavior {

        @Test
        fun `should update server and preserve id and createdAt`() {
            val original = createServer()
            store.save(original)
            val saved = store.findByName("test-server")!!

            Thread.sleep(10)
            val updateData = createServer(description = "Updated description", autoConnect = true)
            val updated = store.update("test-server", updateData)

            assertNotNull(updated) { "Update should return updated server" }
            assertEquals(saved.id, updated!!.id) { "ID should be preserved after update" }
            assertEquals(
                saved.createdAt.epochSecond,
                updated.createdAt.epochSecond
            ) { "createdAt should be preserved" }
            assertEquals("Updated description", updated.description) { "Description should be updated" }
            assertTrue(updated.autoConnect) { "autoConnect should be updated" }
        }

        @Test
        fun `should return null when updating nonexistent server`() {
            val result = store.update("nonexistent", createServer())

            assertNull(result, "Updating nonexistent server should return null")
        }

        @Test
        fun `should update transport type`() {
            store.save(createServer(transportType = McpTransportType.SSE))

            store.update("test-server", createServer(transportType = McpTransportType.STDIO))

            val found = store.findByName("test-server")!!
            assertEquals(McpTransportType.STDIO, found.transportType) { "Transport type should be updated" }
        }
    }
}
