package com.arc.reactor.autoconfigure

import com.arc.reactor.mcp.McpManager
import com.arc.reactor.tool.SemanticToolSelector
import com.arc.reactor.tool.ToolCallback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.embedding.EmbeddingModel

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

    @Test
    fun `should prewarm semantic tool selector after startup initialization`() {
        val tools = listOf(mockk<ToolCallback> {
            every { name } returns "jira_search_issues"
            every { description } returns "Search Jira issues"
        })
        val embeddingModel = mockk<EmbeddingModel>()
        every { mcpManager.getAllToolCallbacks() } returns tools
        every { embeddingModel.embed(any<List<String>>()) } returns listOf(floatArrayOf(0.5f, 0.5f))

        val initializer = McpStartupInitializer(mcpManager, SemanticToolSelector(embeddingModel, maxResults = 1))

        assertDoesNotThrow({ initializer.initialize() }) {
            "Startup initializer should prewarm semantic tool embeddings without throwing"
        }
        coVerify(exactly = 1) { mcpManager.initializeFromStore() }
        verify(exactly = 1) { mcpManager.getAllToolCallbacks() }
        verify(exactly = 1) { embeddingModel.embed(any<List<String>>()) }
    }
}
