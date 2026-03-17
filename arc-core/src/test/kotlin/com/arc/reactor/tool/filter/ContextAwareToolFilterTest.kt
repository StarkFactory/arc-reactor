package com.arc.reactor.tool.filter

import com.arc.reactor.tool.ToolCallback
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * ContextAwareToolFilter 및 NoOpToolFilter에 대한 테스트.
 *
 * 인텐트, 채널, 역할 기반 도구 필터링 로직을 검증한다.
 */
class ContextAwareToolFilterTest {

    private fun tool(name: String): ToolCallback = object : ToolCallback {
        override val name = name
        override val description = "Test tool: $name"
        override suspend fun call(arguments: Map<String, Any?>): Any? = null
    }

    private val allTools = listOf(
        tool("search_order"),
        tool("delete_order"),
        tool("check_status"),
        tool("drop_database")
    )

    @Nested
    inner class DisabledFilterTest {

        @Test
        fun `비활성화 시 모든 도구를 반환해야 한다`() {
            val filter = ContextAwareToolFilter(ToolFilterProperties(enabled = false))
            val context = ToolFilterContext(userId = "user-1", channel = "slack")

            val result = filter.filter(allTools, context)

            assertEquals(allTools.size, result.size) {
                "비활성화된 필터는 모든 도구를 반환해야 한다"
            }
        }
    }

    @Nested
    inner class IntentFilterTest {

        @Test
        fun `인텐트의 allowedTools에 포함된 도구만 반환해야 한다`() {
            val filter = ContextAwareToolFilter(
                ToolFilterProperties(
                    enabled = true,
                    intentAllowedTools = mapOf(
                        "order_inquiry" to setOf("search_order", "check_status")
                    )
                )
            )
            val context = ToolFilterContext(userId = "user-1", intent = "order_inquiry")

            val result = filter.filter(allTools, context)

            assertEquals(2, result.size) { "order_inquiry 인텐트에 허용된 2개 도구만 반환해야 한다" }
            assertTrue(result.all { it.name in setOf("search_order", "check_status") }) {
                "허용된 도구만 포함해야 한다, 실제: ${result.map { it.name }}"
            }
        }

        @Test
        fun `등록되지 않은 인텐트이면 모든 도구를 반환해야 한다`() {
            val filter = ContextAwareToolFilter(
                ToolFilterProperties(
                    enabled = true,
                    intentAllowedTools = mapOf(
                        "order_inquiry" to setOf("search_order")
                    )
                )
            )
            val context = ToolFilterContext(userId = "user-1", intent = "unknown_intent")

            val result = filter.filter(allTools, context)

            assertEquals(allTools.size, result.size) { "등록되지 않은 인텐트는 모든 도구를 반환해야 한다" }
        }

        @Test
        fun `intent가 null이면 인텐트 필터를 건너뛰어야 한다`() {
            val filter = ContextAwareToolFilter(
                ToolFilterProperties(
                    enabled = true,
                    intentAllowedTools = mapOf(
                        "order_inquiry" to setOf("search_order")
                    )
                )
            )
            val context = ToolFilterContext(userId = "user-1", intent = null)

            val result = filter.filter(allTools, context)

            assertEquals(allTools.size, result.size) { "intent가 null이면 모든 도구를 반환해야 한다" }
        }
    }

    @Nested
    inner class ChannelFilterTest {

        @Test
        fun `채널에서 제한된 도구를 차단해야 한다`() {
            val filter = ContextAwareToolFilter(
                ToolFilterProperties(
                    enabled = true,
                    channelRestrictions = mapOf(
                        "slack" to setOf("delete_order", "drop_database")
                    )
                )
            )
            val context = ToolFilterContext(userId = "user-1", channel = "slack")

            val result = filter.filter(allTools, context)

            assertEquals(2, result.size) { "slack에서 2개 제한 도구가 차단되어야 한다" }
            assertTrue(result.none { it.name in setOf("delete_order", "drop_database") }) {
                "제한된 도구가 포함되면 안 된다, 실제: ${result.map { it.name }}"
            }
        }

        @Test
        fun `제한이 없는 채널에서는 모든 도구를 반환해야 한다`() {
            val filter = ContextAwareToolFilter(
                ToolFilterProperties(
                    enabled = true,
                    channelRestrictions = mapOf(
                        "slack" to setOf("delete_order")
                    )
                )
            )
            val context = ToolFilterContext(userId = "user-1", channel = "web")

            val result = filter.filter(allTools, context)

            assertEquals(allTools.size, result.size) { "제한이 없는 채널은 모든 도구를 반환해야 한다" }
        }

        @Test
        fun `channel이 null이면 채널 필터를 건너뛰어야 한다`() {
            val filter = ContextAwareToolFilter(
                ToolFilterProperties(
                    enabled = true,
                    channelRestrictions = mapOf(
                        "slack" to setOf("delete_order")
                    )
                )
            )
            val context = ToolFilterContext(userId = "user-1", channel = null)

            val result = filter.filter(allTools, context)

            assertEquals(allTools.size, result.size) { "channel이 null이면 모든 도구를 반환해야 한다" }
        }
    }

    @Nested
    inner class RoleFilterTest {

        @Test
        fun `역할에 허용된 도구만 반환해야 한다`() {
            val filter = ContextAwareToolFilter(
                ToolFilterProperties(
                    enabled = true,
                    roleAllowedTools = mapOf(
                        "user" to setOf("search_order", "check_status")
                    )
                )
            )
            val context = ToolFilterContext(userId = "user-1", userRole = "user")

            val result = filter.filter(allTools, context)

            assertEquals(2, result.size) { "user 역할에 허용된 2개 도구만 반환해야 한다" }
            assertTrue(result.all { it.name in setOf("search_order", "check_status") }) {
                "허용된 도구만 포함해야 한다, 실제: ${result.map { it.name }}"
            }
        }

        @Test
        fun `와일드카드 역할은 모든 도구를 허용해야 한다`() {
            val filter = ContextAwareToolFilter(
                ToolFilterProperties(
                    enabled = true,
                    roleAllowedTools = mapOf(
                        "admin" to setOf("*")
                    )
                )
            )
            val context = ToolFilterContext(userId = "admin-1", userRole = "admin")

            val result = filter.filter(allTools, context)

            assertEquals(allTools.size, result.size) { "와일드카드 역할은 모든 도구를 반환해야 한다" }
        }

        @Test
        fun `등록되지 않은 역할은 모든 도구를 반환해야 한다`() {
            val filter = ContextAwareToolFilter(
                ToolFilterProperties(
                    enabled = true,
                    roleAllowedTools = mapOf(
                        "admin" to setOf("*")
                    )
                )
            )
            val context = ToolFilterContext(userId = "user-1", userRole = "unknown_role")

            val result = filter.filter(allTools, context)

            assertEquals(allTools.size, result.size) { "등록되지 않은 역할은 모든 도구를 반환해야 한다" }
        }

        @Test
        fun `userRole이 null이면 역할 필터를 건너뛰어야 한다`() {
            val filter = ContextAwareToolFilter(
                ToolFilterProperties(
                    enabled = true,
                    roleAllowedTools = mapOf(
                        "user" to setOf("search_order")
                    )
                )
            )
            val context = ToolFilterContext(userId = "user-1", userRole = null)

            val result = filter.filter(allTools, context)

            assertEquals(allTools.size, result.size) { "userRole이 null이면 모든 도구를 반환해야 한다" }
        }
    }

    @Nested
    inner class CombinedFilterTest {

        @Test
        fun `모든 필터가 결합되어 적용되어야 한다`() {
            val filter = ContextAwareToolFilter(
                ToolFilterProperties(
                    enabled = true,
                    intentAllowedTools = mapOf(
                        "order_inquiry" to setOf("search_order", "delete_order", "check_status")
                    ),
                    channelRestrictions = mapOf(
                        "slack" to setOf("delete_order")
                    ),
                    roleAllowedTools = mapOf(
                        "user" to setOf("search_order", "check_status")
                    )
                )
            )
            val context = ToolFilterContext(
                userId = "user-1",
                intent = "order_inquiry",
                channel = "slack",
                userRole = "user"
            )

            val result = filter.filter(allTools, context)

            // 인텐트: search_order, delete_order, check_status
            // 채널: delete_order 차단 -> search_order, check_status
            // 역할: search_order, check_status 허용 -> search_order, check_status
            assertEquals(2, result.size) { "3단계 필터 모두 적용되어야 한다" }
            assertTrue(result.all { it.name in setOf("search_order", "check_status") }) {
                "모든 조건을 만족하는 도구만 포함해야 한다, 실제: ${result.map { it.name }}"
            }
        }
    }

    @Nested
    inner class NoOpToolFilterTest {

        @Test
        fun `모든 도구를 그대로 반환해야 한다`() {
            val filter = NoOpToolFilter()
            val context = ToolFilterContext(userId = "user-1", channel = "slack", userRole = "user")

            val result = filter.filter(allTools, context)

            assertEquals(allTools.size, result.size) { "NoOpToolFilter는 모든 도구를 반환해야 한다" }
            assertEquals(allTools, result) { "NoOpToolFilter는 원본 리스트를 그대로 반환해야 한다" }
        }
    }
}
