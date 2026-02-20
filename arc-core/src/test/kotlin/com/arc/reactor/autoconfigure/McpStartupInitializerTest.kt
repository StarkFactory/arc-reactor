package com.arc.reactor.autoconfigure

import com.arc.reactor.mcp.McpManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class McpStartupInitializerTest {

    private lateinit var mcpManager: McpManager

    @BeforeEach
    fun setup() {
        mcpManager = mockk()
        coEvery { mcpManager.initializeFromStore() } returns Unit
    }

    @Test
    fun `should initialize runtime from store on startup`() {
        val initializer = McpStartupInitializer(mcpManager)

        assertDoesNotThrow({ initializer.initialize() }) {
            "Startup initializer should initialize MCP runtime without throwing"
        }
        coVerify(exactly = 1) { mcpManager.initializeFromStore() }
    }
}
