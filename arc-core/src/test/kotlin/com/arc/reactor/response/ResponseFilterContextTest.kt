package com.arc.reactor.response

import com.arc.reactor.agent.model.AgentCommand
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * [ResponseFilterContext] 데이터 클래스 및 [ResponseFilter] 인터페이스에 대한 테스트.
 *
 * 대상: ResponseFilterContext 생성 및 기본값, 동등성, 복사; ResponseFilter 기본 order 값.
 */
class ResponseFilterContextTest {

    private val baseCommand = AgentCommand(systemPrompt = "시스템", userPrompt = "사용자")

    @Nested
    inner class ResponseFilterContextDefaults {

        @Test
        fun `verifiedSources 미지정 시 emptyList를 기본값으로 가져야 한다`() {
            val ctx = ResponseFilterContext(
                command = baseCommand,
                toolsUsed = emptyList(),
                durationMs = 0
            )
            assertTrue(ctx.verifiedSources.isEmpty()) {
                "verifiedSources 기본값은 emptyList여야 하지만 실제: ${ctx.verifiedSources}"
            }
        }

        @Test
        fun `모든 필드를 지정하면 그대로 저장해야 한다`() {
            val sources = listOf(VerifiedSource(title = "이슈-1", url = "https://example.com/1", toolName = "jira"))
            val tools = listOf("search", "calculator")
            val ctx = ResponseFilterContext(
                command = baseCommand,
                toolsUsed = tools,
                verifiedSources = sources,
                durationMs = 250L
            )

            assertEquals(tools, ctx.toolsUsed) { "toolsUsed 필드가 올바르게 저장되어야 한다" }
            assertEquals(sources, ctx.verifiedSources) { "verifiedSources 필드가 올바르게 저장되어야 한다" }
            assertEquals(250L, ctx.durationMs) { "durationMs 필드가 올바르게 저장되어야 한다" }
            assertEquals(baseCommand, ctx.command) { "command 필드가 올바르게 저장되어야 한다" }
        }
    }

    @Nested
    inner class ResponseFilterContextEquality {

        @Test
        fun `같은 값으로 생성된 두 인스턴스는 동등해야 한다`() {
            val ctx1 = ResponseFilterContext(
                command = baseCommand,
                toolsUsed = listOf("tool-a"),
                durationMs = 100L
            )
            val ctx2 = ResponseFilterContext(
                command = baseCommand,
                toolsUsed = listOf("tool-a"),
                durationMs = 100L
            )
            assertEquals(ctx1, ctx2) { "동일한 필드를 가진 ResponseFilterContext는 동등해야 한다" }
        }

        @Test
        fun `durationMs가 다르면 동등하지 않아야 한다`() {
            val ctx1 = ResponseFilterContext(command = baseCommand, toolsUsed = emptyList(), durationMs = 100L)
            val ctx2 = ResponseFilterContext(command = baseCommand, toolsUsed = emptyList(), durationMs = 200L)
            assertNotEquals(ctx1, ctx2) { "durationMs가 다르면 동등하지 않아야 한다" }
        }

        @Test
        fun `toolsUsed가 다르면 동등하지 않아야 한다`() {
            val ctx1 = ResponseFilterContext(command = baseCommand, toolsUsed = listOf("a"), durationMs = 0L)
            val ctx2 = ResponseFilterContext(command = baseCommand, toolsUsed = listOf("b"), durationMs = 0L)
            assertNotEquals(ctx1, ctx2) { "toolsUsed가 다르면 동등하지 않아야 한다" }
        }
    }

    @Nested
    inner class ResponseFilterContextCopy {

        @Test
        fun `copy로 durationMs만 변경하면 나머지 필드는 유지되어야 한다`() {
            val original = ResponseFilterContext(
                command = baseCommand,
                toolsUsed = listOf("search"),
                verifiedSources = listOf(VerifiedSource(title = "문서", url = "https://example.com")),
                durationMs = 50L
            )
            val copied = original.copy(durationMs = 999L)

            assertEquals(999L, copied.durationMs) { "copy된 durationMs가 변경되어야 한다" }
            assertEquals(original.command, copied.command) { "command는 그대로 유지되어야 한다" }
            assertEquals(original.toolsUsed, copied.toolsUsed) { "toolsUsed는 그대로 유지되어야 한다" }
            assertEquals(original.verifiedSources, copied.verifiedSources) { "verifiedSources는 그대로 유지되어야 한다" }
        }

        @Test
        fun `copy로 toolsUsed를 변경하면 원본은 영향받지 않아야 한다`() {
            val original = ResponseFilterContext(command = baseCommand, toolsUsed = listOf("a"), durationMs = 0L)
            val copied = original.copy(toolsUsed = listOf("a", "b"))

            assertEquals(listOf("a"), original.toolsUsed) { "원본 toolsUsed는 변경되지 않아야 한다" }
            assertEquals(listOf("a", "b"), copied.toolsUsed) { "복사본의 toolsUsed가 변경되어야 한다" }
        }
    }

    @Nested
    inner class ResponseFilterDefaultOrder {

        @Test
        fun `ResponseFilter 기본 order는 100이어야 한다`() {
            val filter = object : ResponseFilter {
                override suspend fun filter(content: String, context: ResponseFilterContext) = content
            }
            assertEquals(100, filter.order) {
                "ResponseFilter 기본 order는 100(커스텀 필터 범위)이어야 하지만 실제: ${filter.order}"
            }
        }

        @Test
        fun `order 재정의 시 재정의된 값이 반환되어야 한다`() {
            val filter = object : ResponseFilter {
                override val order = 42
                override suspend fun filter(content: String, context: ResponseFilterContext) = content
            }
            assertEquals(42, filter.order) { "재정의된 order 값이 반환되어야 한다" }
        }

        @Test
        fun `내장 필터 범위(1-99)는 기본 order(100)보다 먼저 실행되어야 한다`() {
            val defaultOrderFilter = object : ResponseFilter {
                override suspend fun filter(content: String, context: ResponseFilterContext) = content
            }
            val builtinFilter = object : ResponseFilter {
                override val order = 50
                override suspend fun filter(content: String, context: ResponseFilterContext) = content
            }
            assertTrue(builtinFilter.order < defaultOrderFilter.order) {
                "내장 필터(order=50)는 기본 커스텀 필터(order=${defaultOrderFilter.order})보다 먼저 실행되어야 한다"
            }
        }
    }

    @Nested
    inner class ResponseFilterBehavior {

        @Test
        fun `filter가 콘텐츠를 그대로 반환할 수 있어야 한다`() = runTest {
            val passthrough = object : ResponseFilter {
                override suspend fun filter(content: String, context: ResponseFilterContext) = content
            }
            val ctx = ResponseFilterContext(command = baseCommand, toolsUsed = emptyList(), durationMs = 0L)
            val result = passthrough.filter("입력 텍스트", ctx)

            assertEquals("입력 텍스트", result) { "pass-through 필터는 콘텐츠를 그대로 반환해야 한다" }
        }

        @Test
        fun `filter가 콘텐츠를 변환할 수 있어야 한다`() = runTest {
            val uppercaseFilter = object : ResponseFilter {
                override suspend fun filter(content: String, context: ResponseFilterContext) = content.uppercase()
            }
            val ctx = ResponseFilterContext(command = baseCommand, toolsUsed = emptyList(), durationMs = 0L)
            val result = uppercaseFilter.filter("hello world", ctx)

            assertEquals("HELLO WORLD", result) { "변환 필터는 콘텐츠를 수정하여 반환해야 한다" }
        }

        @Test
        fun `filter가 컨텍스트의 toolsUsed를 활용할 수 있어야 한다`() = runTest {
            val toolAwareFilter = object : ResponseFilter {
                override suspend fun filter(content: String, context: ResponseFilterContext): String {
                    val toolList = context.toolsUsed.joinToString(", ")
                    return if (toolList.isNotBlank()) "$content [사용된 도구: $toolList]" else content
                }
            }
            val ctx = ResponseFilterContext(
                command = baseCommand,
                toolsUsed = listOf("검색", "계산기"),
                durationMs = 0L
            )
            val result = toolAwareFilter.filter("결과", ctx)

            assertTrue(result.contains("검색")) { "필터가 toolsUsed를 활용하여 결과를 보강해야 한다" }
            assertTrue(result.contains("계산기")) { "필터가 모든 사용된 도구 이름을 포함해야 한다" }
        }
    }
}
