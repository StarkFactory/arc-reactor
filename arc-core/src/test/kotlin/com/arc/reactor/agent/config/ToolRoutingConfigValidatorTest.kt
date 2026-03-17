package com.arc.reactor.agent.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * [ToolRoutingConfigValidator] 유효성 검증 테스트.
 */
class ToolRoutingConfigValidatorTest {

    @Test
    fun `valid config should pass without exception`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "test_route",
                    category = "confluence",
                    keywords = setOf("wiki"),
                    promptInstruction = "Use wiki tool",
                    preferredTools = listOf("wiki_search")
                )
            )
        )
        assertDoesNotThrow("Valid config should not throw") {
            ToolRoutingConfigValidator.validate(config)
        }
    }

    @Test
    fun `empty id should throw IllegalStateException`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "",
                    category = "confluence",
                    keywords = setOf("wiki"),
                    promptInstruction = "Use wiki tool"
                )
            )
        )
        val ex = assertThrows<IllegalStateException>("Empty id must throw") {
            ToolRoutingConfigValidator.validate(config)
        }
        ex.message shouldBe "tool-routing.yml validation failed: 1 route(s) have empty id"
    }

    @Test
    fun `blank id should throw IllegalStateException`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "   ",
                    category = "jira",
                    keywords = setOf("issue"),
                    promptInstruction = "Search issues"
                )
            )
        )
        assertThrows<IllegalStateException>("Blank id must throw") {
            ToolRoutingConfigValidator.validate(config)
        }
    }

    @Test
    fun `route without keywords and regexPatternRef should not throw`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "empty_keywords_route",
                    category = "work",
                    promptInstruction = "Do something"
                )
            )
        )
        assertDoesNotThrow("Missing keywords should warn but not throw") {
            ToolRoutingConfigValidator.validate(config)
        }
    }

    @Test
    fun `route with regexPatternRef but no keywords should pass`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "regex_only_route",
                    category = "jira",
                    regexPatternRef = "ISSUE_KEY",
                    promptInstruction = "Lookup issue"
                )
            )
        )
        assertDoesNotThrow("Route with regexPatternRef should pass") {
            ToolRoutingConfigValidator.validate(config)
        }
    }

    @Test
    fun `unknown category should not throw`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "unknown_cat_route",
                    category = "unknown_category",
                    keywords = setOf("test"),
                    promptInstruction = "Test"
                )
            )
        )
        assertDoesNotThrow("Unknown category should warn but not throw") {
            ToolRoutingConfigValidator.validate(config)
        }
    }

    @Test
    fun `empty routes should pass`() {
        val config = ToolRoutingConfig(routes = emptyList())
        assertDoesNotThrow("Empty routes should pass") {
            ToolRoutingConfigValidator.validate(config)
        }
    }

    @Test
    fun `classpath config should pass validation`() {
        val config = ToolRoutingConfig.loadFromClasspath()
        assertDoesNotThrow("Classpath tool-routing.yml should pass validation") {
            ToolRoutingConfigValidator.validate(config)
        }
    }

    @Test
    fun `multiple errors should report count`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "",
                    category = "jira",
                    keywords = setOf("issue"),
                    promptInstruction = "Search"
                ),
                ToolRoute(
                    id = "valid_route",
                    category = "work",
                    keywords = setOf("briefing"),
                    promptInstruction = "Brief"
                ),
                ToolRoute(
                    id = "",
                    category = "confluence",
                    keywords = setOf("wiki"),
                    promptInstruction = "Wiki"
                )
            )
        )
        val ex = assertThrows<IllegalStateException>("Multiple empty ids must throw") {
            ToolRoutingConfigValidator.validate(config)
        }
        ex.message shouldBe "tool-routing.yml validation failed: 2 route(s) have empty id"
    }
}
