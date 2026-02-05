package com.arc.reactor.tool

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ToolSelectorTest {

    private fun createMockTool(toolName: String, toolDescription: String): ToolCallback {
        return object : ToolCallback {
            override val name = toolName
            override val description = toolDescription
            override suspend fun call(arguments: Map<String, Any?>): Any? = null
        }
    }

    @Test
    fun `AllToolSelector should return all tools`() {
        val selector = AllToolSelector()
        val tools = listOf(
            createMockTool("tool1", "Description 1"),
            createMockTool("tool2", "Description 2"),
            createMockTool("tool3", "Description 3")
        )

        val selected = selector.select("any prompt", tools)

        assertEquals(3, selected.size)
        assertEquals(tools, selected)
    }

    @Test
    fun `KeywordBasedToolSelector should return all tools when no category map`() {
        val selector = KeywordBasedToolSelector()
        val tools = listOf(
            createMockTool("tool1", "Description 1"),
            createMockTool("tool2", "Description 2")
        )

        val selected = selector.select("any prompt", tools)

        assertEquals(2, selected.size)
    }

    @Test
    fun `KeywordBasedToolSelector should filter by matched category`() {
        // Create test categories as ToolCategory implementations
        val searchCategory = object : ToolCategory {
            override val name = "search"
            override val keywords = setOf("search", "find", "query")
        }
        val calcCategory = object : ToolCategory {
            override val name = "calculation"
            override val keywords = setOf("calculate", "compute", "math")
        }

        val categoryMap = mapOf(
            "search_tool" to searchCategory,
            "calc_tool" to calcCategory
        )

        val selector = KeywordBasedToolSelector(categoryMap)

        val tools = listOf(
            createMockTool("search_tool", "Search documents"),
            createMockTool("calc_tool", "Calculate numbers"),
            createMockTool("general_tool", "General purpose tool")
        )

        // Should match search category
        val searchResult = selector.select("Please search for documents", tools)
        assertTrue(searchResult.any { it.name == "search_tool" })
        assertTrue(searchResult.any { it.name == "general_tool" }) // No category, always included
        assertFalse(searchResult.any { it.name == "calc_tool" }) // Different category

        // Should match calculation category
        val calcResult = selector.select("Calculate the sum of these numbers", tools)
        assertTrue(calcResult.any { it.name == "calc_tool" })
        assertTrue(calcResult.any { it.name == "general_tool" }) // No category, always included
        assertFalse(calcResult.any { it.name == "search_tool" }) // Different category
    }

    @Test
    fun `KeywordBasedToolSelector should return all when no category matches`() {
        val searchCategory = object : ToolCategory {
            override val name = "search"
            override val keywords = setOf("search", "find", "query")
        }

        val categoryMap = mapOf(
            "search_tool" to searchCategory
        )

        val selector = KeywordBasedToolSelector(categoryMap)

        val tools = listOf(
            createMockTool("search_tool", "Search documents"),
            createMockTool("other_tool", "Other functionality")
        )

        // Prompt doesn't match any category
        val result = selector.select("Hello, how are you?", tools)

        assertEquals(2, result.size) // Returns all tools
    }

    @Test
    fun `ToolCategory should match keywords case-insensitively`() {
        val category = object : ToolCategory {
            override val name = "search"
            override val keywords = setOf("search", "find", "query")
        }

        assertTrue(category.matches("I want to SEARCH for something"))
        assertTrue(category.matches("please find this document"))
        assertTrue(category.matches("Run a query"))
        assertFalse(category.matches("Hello world"))
    }

    @Test
    fun `KeywordBasedToolSelector should handle multiple matching categories`() {
        val searchCategory = object : ToolCategory {
            override val name = "search"
            override val keywords = setOf("search", "find")
        }
        val dataCategory = object : ToolCategory {
            override val name = "data"
            override val keywords = setOf("data", "database")
        }

        val categoryMap = mapOf(
            "search_tool" to searchCategory,
            "data_tool" to dataCategory
        )

        val selector = KeywordBasedToolSelector(categoryMap)

        val tools = listOf(
            createMockTool("search_tool", "Search"),
            createMockTool("data_tool", "Data"),
            createMockTool("general_tool", "General")
        )

        // Prompt matches both categories
        val result = selector.select("Search the database for data", tools)

        assertEquals(3, result.size) // Both categorized tools + general
    }

    @Test
    fun `DefaultToolCategory should match prompts correctly`() {
        assertTrue(DefaultToolCategory.SEARCH.matches("검색해줘"))
        assertTrue(DefaultToolCategory.SEARCH.matches("Please search for documents"))
        assertTrue(DefaultToolCategory.CREATE.matches("Create a new report"))
        assertTrue(DefaultToolCategory.ANALYZE.matches("Analyze this data"))
        assertTrue(DefaultToolCategory.COMMUNICATE.matches("Send an email"))
        assertTrue(DefaultToolCategory.DATA.matches("Update the database"))

        assertFalse(DefaultToolCategory.SEARCH.matches("Hello world"))
    }

    @Test
    fun `DefaultToolCategory matchCategories should find all matching`() {
        val matched = DefaultToolCategory.matchCategories("검색해서 분석해줘")

        assertTrue(matched.contains(DefaultToolCategory.SEARCH))
        assertTrue(matched.contains(DefaultToolCategory.ANALYZE))
        assertFalse(matched.contains(DefaultToolCategory.CREATE))
    }
}
