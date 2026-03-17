package com.arc.reactor.tool.enrichment

import com.arc.reactor.tool.ToolCallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * DefaultToolDescriptionEnricher 테스트.
 *
 * 도구 설명 품질 분석의 다양한 시나리오를 검증한다.
 */
class DefaultToolDescriptionEnricherTest {

    private val enricher = DefaultToolDescriptionEnricher()

    private fun tool(
        name: String,
        description: String,
        inputSchema: String = """{"type":"object","properties":{}}"""
    ): ToolCallback = object : ToolCallback {
        override val name = name
        override val description = description
        override val inputSchema = inputSchema
        override suspend fun call(arguments: Map<String, Any?>): Any? = null
    }

    @Nested
    inner class HighQualityToolTest {

        @Test
        fun `완전한 도구는 높은 점수를 받아야 한다`() {
            val tool = tool(
                name = "get_weather",
                description = "Get the current weather information for a specified location including temperature, humidity, and conditions",
                inputSchema = """
                    {
                        "type": "object",
                        "properties": {
                            "location": {
                                "type": "string",
                                "description": "City name or coordinates"
                            },
                            "unit": {
                                "type": "string",
                                "description": "Temperature unit (celsius or fahrenheit)"
                            }
                        },
                        "required": ["location"]
                    }
                """.trimIndent()
            )

            val result = enricher.analyze(tool)

            assertEquals("get_weather", result.toolName) { "도구 이름이 일치해야 한다" }
            assertTrue(result.score > 0.8) { "완전한 도구는 0.8 이상의 점수여야 한다, 실제: ${result.score}" }
            assertTrue(result.hasInputSchema) { "입력 스키마가 존재해야 한다" }
            assertTrue(result.warnings.isEmpty()) { "경고가 없어야 한다, 실제: ${result.warnings}" }
        }

        @Test
        fun `camelCase 도구 이름도 유효해야 한다`() {
            val tool = tool(
                name = "getWeather",
                description = "Get weather information for a given location with full details about temperature and conditions",
                inputSchema = """
                    {
                        "type": "object",
                        "properties": {
                            "location": {
                                "type": "string",
                                "description": "City name"
                            }
                        }
                    }
                """.trimIndent()
            )

            val result = enricher.analyze(tool)

            assertTrue(result.score > 0.7) { "camelCase 도구도 0.7 이상의 점수여야 한다, 실제: ${result.score}" }
        }
    }

    @Nested
    inner class EmptyDescriptionTest {

        @Test
        fun `설명이 비어있으면 낮은 점수와 경고를 반환해야 한다`() {
            val tool = tool(
                name = "search",
                description = ""
            )

            val result = enricher.analyze(tool)

            assertTrue(result.score < 0.3) { "빈 설명은 0.3 미만의 점수여야 한다, 실제: ${result.score}" }
            assertEquals(0, result.descriptionLength) { "빈 설명의 길이는 0이어야 한다" }
            assertTrue(result.warnings.isNotEmpty()) { "경고가 있어야 한다" }
            assertTrue(result.warnings.any { it.contains("비어있습니다") }) {
                "빈 설명 경고를 포함해야 한다, 실제: ${result.warnings}"
            }
        }

        @Test
        fun `공백만 있는 설명도 비어있는 것으로 취급해야 한다`() {
            val tool = tool(
                name = "search",
                description = "   "
            )

            val result = enricher.analyze(tool)

            assertEquals(0, result.descriptionLength) { "공백만 있는 설명은 길이 0이어야 한다" }
            assertTrue(result.warnings.any { it.contains("비어있습니다") }) {
                "공백만 있는 설명은 비어있는 것으로 취급해야 한다"
            }
        }
    }

    @Nested
    inner class ShortDescriptionTest {

        @Test
        fun `짧은 설명은 경고를 포함해야 한다`() {
            val tool = tool(
                name = "search_items",
                description = "Search items"
            )

            val result = enricher.analyze(tool)

            assertTrue(result.score < 0.7) { "짧은 설명은 0.7 미만이어야 한다, 실제: ${result.score}" }
            assertTrue(result.warnings.any { it.contains("짧습니다") }) {
                "짧은 설명 경고를 포함해야 한다, 실제: ${result.warnings}"
            }
        }
    }

    @Nested
    inner class InputSchemaTest {

        @Test
        fun `빈 입력 스키마는 경고를 발생시켜야 한다`() {
            val tool = tool(
                name = "list_items",
                description = "List all available items in the catalog with pagination and sorting support",
                inputSchema = """{"type":"object","properties":{}}"""
            )

            val result = enricher.analyze(tool)

            assertTrue(!result.hasInputSchema) { "빈 스키마는 hasInputSchema가 false여야 한다" }
            assertTrue(result.warnings.isNotEmpty()) { "경고가 있어야 한다" }
            assertTrue(result.warnings.any { it.contains("파라미터가 정의되지 않았습니다") }) {
                "파라미터 미정의 경고를 포함해야 한다, 실제: ${result.warnings}"
            }
        }

        @Test
        fun `파라미터에 description이 누락되면 경고해야 한다`() {
            val tool = tool(
                name = "update_record",
                description = "Update an existing record in the database with the provided field values and return confirmation",
                inputSchema = """
                    {
                        "type": "object",
                        "properties": {
                            "id": {
                                "type": "string"
                            },
                            "value": {
                                "type": "number",
                                "description": "The new value"
                            }
                        }
                    }
                """.trimIndent()
            )

            val result = enricher.analyze(tool)

            assertTrue(result.hasInputSchema) { "입력 스키마가 존재해야 한다" }
            assertTrue(result.warnings.any { it.contains("description이 누락") }) {
                "파라미터 설명 누락 경고를 포함해야 한다, 실제: ${result.warnings}"
            }
            assertTrue(result.warnings.any { it.contains("id") }) {
                "'id' 파라미터가 누락 목록에 포함되어야 한다, 실제: ${result.warnings}"
            }
        }
    }

    @Nested
    inner class ToolNameTest {

        @Test
        fun `유효하지 않은 도구 이름은 경고를 발생시켜야 한다`() {
            val tool = tool(
                name = "Get-Weather!",
                description = "Get the current weather information for a specified location including temperature and humidity details",
                inputSchema = """
                    {
                        "type": "object",
                        "properties": {
                            "location": {
                                "type": "string",
                                "description": "City name"
                            }
                        }
                    }
                """.trimIndent()
            )

            val result = enricher.analyze(tool)

            assertTrue(result.warnings.any { it.contains("규칙을 따르지 않습니다") }) {
                "잘못된 이름 형식 경고를 포함해야 한다, 실제: ${result.warnings}"
            }
        }

        @Test
        fun `빈 도구 이름은 경고를 발생시켜야 한다`() {
            val tool = tool(
                name = "",
                description = "A tool that does something useful for the user with various parameters and detailed operations",
                inputSchema = """
                    {
                        "type": "object",
                        "properties": {
                            "query": {
                                "type": "string",
                                "description": "Search query"
                            }
                        }
                    }
                """.trimIndent()
            )

            val result = enricher.analyze(tool)

            assertTrue(result.warnings.any { it.contains("규칙을 따르지 않습니다") }) {
                "빈 이름 경고를 포함해야 한다, 실제: ${result.warnings}"
            }
        }
    }

    @Nested
    inner class ScoreBoundsTest {

        @Test
        fun `점수는 항상 0_0과 1_0 사이여야 한다`() {
            val worstTool = tool(name = "!!!", description = "")
            val bestTool = tool(
                name = "perfect_tool",
                description = "A comprehensive tool description that explains exactly what this tool does, including all edge cases and expected behavior patterns",
                inputSchema = """
                    {
                        "type": "object",
                        "properties": {
                            "param1": {
                                "type": "string",
                                "description": "First parameter"
                            }
                        }
                    }
                """.trimIndent()
            )

            val worstResult = enricher.analyze(worstTool)
            val bestResult = enricher.analyze(bestTool)

            assertTrue(worstResult.score >= 0.0) { "최저 점수는 0.0 이상이어야 한다, 실제: ${worstResult.score}" }
            assertTrue(worstResult.score <= 1.0) { "최저 점수는 1.0 이하여야 한다, 실제: ${worstResult.score}" }
            assertTrue(bestResult.score >= 0.0) { "최고 점수는 0.0 이상이어야 한다, 실제: ${bestResult.score}" }
            assertTrue(bestResult.score <= 1.0) { "최고 점수는 1.0 이하여야 한다, 실제: ${bestResult.score}" }
        }
    }
}
