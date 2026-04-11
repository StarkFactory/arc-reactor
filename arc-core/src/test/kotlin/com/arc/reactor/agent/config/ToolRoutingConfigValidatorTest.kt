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
        ex.message shouldBe
            "tool-routing.yml validation failed: 1 error(s) (empty id 또는 unknown regexPatternRef)"
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

    // ── preferredTools 레지스트리 크로스체크 테스트 ──

    @Test
    fun `registry check should pass when all preferredTools exist`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "test_route",
                    category = "jira",
                    keywords = setOf("issue"),
                    promptInstruction = "Search",
                    preferredTools = listOf("jira_search_issues", "jira_get_issue")
                )
            )
        )
        assertDoesNotThrow("All preferredTools exist in registry") {
            ToolRoutingConfigValidator.validatePreferredToolsAgainstRegistry(
                config,
                listOf("jira_search_issues", "jira_get_issue", "confluence_search")
            )
        }
    }

    @Test
    fun `registry check should warn for unregistered preferredTools`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "test_route",
                    category = "jira",
                    keywords = setOf("issue"),
                    promptInstruction = "Search",
                    preferredTools = listOf("jira_search_issues", "typo_tool_name")
                )
            )
        )
        // warn-only, no exception expected
        assertDoesNotThrow("Unregistered preferredTools should warn but not throw") {
            ToolRoutingConfigValidator.validatePreferredToolsAgainstRegistry(
                config,
                listOf("jira_search_issues", "confluence_search")
            )
        }
    }

    @Test
    fun `registry check should handle empty tool registry`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "test_route",
                    category = "jira",
                    keywords = setOf("issue"),
                    promptInstruction = "Search",
                    preferredTools = listOf("jira_search_issues")
                )
            )
        )
        assertDoesNotThrow("Empty registry should skip check") {
            ToolRoutingConfigValidator.validatePreferredToolsAgainstRegistry(
                config,
                emptyList()
            )
        }
    }

    @Test
    fun `registry check should handle routes with no preferredTools`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "no_tools_route",
                    category = "work",
                    keywords = setOf("briefing"),
                    promptInstruction = "Brief"
                )
            )
        )
        assertDoesNotThrow("Routes without preferredTools should pass") {
            ToolRoutingConfigValidator.validatePreferredToolsAgainstRegistry(
                config,
                listOf("jira_search_issues")
            )
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
        ex.message shouldBe
            "tool-routing.yml validation failed: 2 error(s) (empty id 또는 unknown regexPatternRef)"
    }

    // ── R306: regexPatternRef 화이트리스트 검증 ──

    @Test
    fun `R306 known regexPatternRef ISSUE_KEY should pass`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "jira_issue_route",
                    category = "jira",
                    regexPatternRef = "ISSUE_KEY",
                    promptInstruction = "Lookup"
                )
            )
        )
        assertDoesNotThrow("ISSUE_KEY is whitelisted") {
            ToolRoutingConfigValidator.validate(config)
        }
    }

    @Test
    fun `R306 known regexPatternRef OPENAPI_URL should pass`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "swagger_url_route",
                    category = "swagger",
                    regexPatternRef = "OPENAPI_URL",
                    promptInstruction = "Fetch"
                )
            )
        )
        assertDoesNotThrow("OPENAPI_URL is whitelisted") {
            ToolRoutingConfigValidator.validate(config)
        }
    }

    @Test
    fun `R306 unknown regexPatternRef should throw IllegalStateException at validation time`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "bad_regex_route",
                    category = "jira",
                    regexPatternRef = "NONEXISTENT_PATTERN",
                    promptInstruction = "Lookup"
                )
            )
        )
        val ex = assertThrows<IllegalStateException>(
            "Unknown regexPatternRef must fail-fast at startup instead of crashing at request time"
        ) {
            ToolRoutingConfigValidator.validate(config)
        }
        ex.message shouldBe
            "tool-routing.yml validation failed: 1 error(s) (empty id 또는 unknown regexPatternRef)"
    }

    @Test
    fun `R306 null regexPatternRef should not trigger validation error`() {
        val config = ToolRoutingConfig(
            routes = listOf(
                ToolRoute(
                    id = "no_regex_route",
                    category = "work",
                    keywords = setOf("briefing"),
                    regexPatternRef = null,
                    promptInstruction = "Brief"
                )
            )
        )
        assertDoesNotThrow("null regexPatternRef is allowed (optional)") {
            ToolRoutingConfigValidator.validate(config)
        }
    }
}
