package com.arc.reactor.tool

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * ToolSelector 구현체들에 대한 테스트.
 *
 * AllToolSelector, KeywordBasedToolSelector, DefaultToolCategory 등
 * 도구 선택 전략의 동작을 검증합니다.
 */
class ToolSelectorTest {

    private fun createMockTool(toolName: String, toolDescription: String): ToolCallback {
        return object : ToolCallback {
            override val name = toolName
            override val description = toolDescription
            override suspend fun call(arguments: Map<String, Any?>): Any? = null
        }
    }

    @Nested
    inner class AllToolSelectorTest {

        @Test
        fun `return all tools해야 한다`() {
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
    }

    @Nested
    inner class KeywordBasedToolSelectorTest {

        @Test
        fun `no category map일 때 return all tools해야 한다`() {
            val selector = KeywordBasedToolSelector()
            val tools = listOf(
                createMockTool("tool1", "Description 1"),
                createMockTool("tool2", "Description 2")
            )

            val selected = selector.select("any prompt", tools)

            assertEquals(2, selected.size)
        }

        @Test
        fun `filter by matched category해야 한다`() {
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

            // 검색 카테고리와 매칭되어야 합니다
            val searchResult = selector.select("Please search for documents", tools)
            assertTrue(searchResult.any { it.name == "search_tool" }) {
                "Search result should include search_tool, got: ${searchResult.map { it.name }}"
            }
            assertTrue(searchResult.any { it.name == "general_tool" }) {
                "Search result should include general_tool (uncategorized), got: ${searchResult.map { it.name }}"
            }
            assertFalse(searchResult.any { it.name == "calc_tool" }) {
                "Search result should not include calc_tool, got: ${searchResult.map { it.name }}"
            }

            // 계산 카테고리와 매칭되어야 합니다
            val calcResult = selector.select("Calculate the sum of these numbers", tools)
            assertTrue(calcResult.any { it.name == "calc_tool" }) {
                "Calc result should include calc_tool, got: ${calcResult.map { it.name }}"
            }
            assertTrue(calcResult.any { it.name == "general_tool" }) {
                "Calc result should include general_tool (uncategorized), got: ${calcResult.map { it.name }}"
            }
            assertFalse(calcResult.any { it.name == "search_tool" }) {
                "Calc result should not include search_tool, got: ${calcResult.map { it.name }}"
            }
        }

        @Test
        fun `no category matches일 때 return all해야 한다`() {
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

            // 프롬프트가 어떤 카테고리에도 매칭되지 않음
            val result = selector.select("Hello, how are you?", tools)

            assertEquals(2, result.size) // 모든 도구를 반환
        }

        @Test
        fun `handle multiple matching categories해야 한다`() {
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

            // 프롬프트가 두 카테고리 모두에 매칭됨
            val result = selector.select("Search the database for data", tools)

            assertEquals(3, result.size)  // 두 categorized tools + general
        }
    }

    @Nested
    inner class ToolCategoryTest {

        @Test
        fun `match keywords case-insensitively해야 한다`() {
            val category = object : ToolCategory {
                override val name = "search"
                override val keywords = setOf("search", "find", "query")
            }

            assertTrue(category.matches("I want to SEARCH for something")) {
                "Category should match prompt containing 'SEARCH'"
            }
            assertTrue(category.matches("please find this document")) {
                "Category should match prompt containing 'find'"
            }
            assertTrue(category.matches("Run a query")) {
                "Category should match prompt containing 'query'"
            }
            assertFalse(category.matches("Hello world")) {
                "Category should not match prompt 'Hello world' with no keywords"
            }
        }
    }

    @Nested
    inner class DefaultToolCategoryTest {

        @Test
        fun `match prompts correctly해야 한다`() {
            assertTrue(DefaultToolCategory.SEARCH.matches("검색해줘")) {
                "SEARCH category should match Korean keyword '검색해줘'"
            }
            assertTrue(DefaultToolCategory.SEARCH.matches("Please search for documents")) {
                "SEARCH category should match 'search'"
            }
            assertTrue(DefaultToolCategory.CREATE.matches("Create a new report")) {
                "CREATE category should match 'Create'"
            }
            assertTrue(DefaultToolCategory.ANALYZE.matches("Analyze this data")) {
                "ANALYZE category should match 'Analyze'"
            }
            assertTrue(DefaultToolCategory.COMMUNICATE.matches("Send an email")) {
                "COMMUNICATE category should match 'Send'"
            }
            assertTrue(DefaultToolCategory.DATA.matches("Update the database")) {
                "DATA category should match 'database'"
            }

            assertFalse(DefaultToolCategory.SEARCH.matches("Hello world")) {
                "SEARCH category should not match 'Hello world'"
            }
        }

        @Test
        fun `matchCategories은(는) find all matching해야 한다`() {
            val matched = DefaultToolCategory.matchCategories("검색해서 분석해줘")

            assertTrue(matched.contains(DefaultToolCategory.SEARCH)) {
                "Matched categories should contain SEARCH, got: ${matched.map { it.name }}"
            }
            assertTrue(matched.contains(DefaultToolCategory.ANALYZE)) {
                "Matched categories should contain ANALYZE, got: ${matched.map { it.name }}"
            }
            assertFalse(matched.contains(DefaultToolCategory.CREATE)) {
                "Matched categories should not contain CREATE, got: ${matched.map { it.name }}"
            }
        }
    }
}
