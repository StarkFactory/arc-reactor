package com.arc.reactor.mcp

import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * McpToolCallback의 커버리지 공백을 보강하는 테스트.
 *
 * 기존 테스트에서 다루지 않는 영역:
 * - 출력 길이 초과 시 잘라내기
 * - 비어있는 출력 반환
 * - null mcpInputSchema일 때 기본 inputSchema
 * - ImageContent/EmbeddedResource 포함 혼합 콘텐츠 출력
 * - structuredContent가 빈 값일 때 textContent 우선
 */
class McpToolCallbackCoverageGapTest {

    private val client = mockk<McpSyncClient>()

    /** 기본 CallToolResult 빌더 헬퍼 */
    private fun makeResult(
        textContents: List<String> = emptyList(),
        structuredContent: Any? = null
    ): McpSchema.CallToolResult {
        val contents = textContents.map { McpSchema.TextContent(it) }
        return McpSchema.CallToolResult(contents, false, structuredContent, emptyMap())
    }

    // ─────────────────────────────────────────────────────────────────────
    // 출력 잘라내기 (maxOutputLength)
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class OutputTruncation {

        @Test
        fun `maxOutputLength 초과 시 출력이 잘린다`() = runTest {
            val maxLen = 50
            val callback = McpToolCallback(
                client = client,
                name = "tool",
                description = "desc",
                mcpInputSchema = null,
                maxOutputLength = maxLen
            )
            val longText = "A".repeat(200)
            every { client.callTool(any()) } returns makeResult(listOf(longText))

            val output = callback.call(emptyMap()).toString()

            // 잘라낸 앞부분이 maxLen 자 이내이고, 나머지는 잘라내기 마커여야 한다
            val truncatedPart = output.substringBefore("\n[TRUNCATED:")
            assertTrue(truncatedPart.length <= maxLen) {
                "잘라낸 앞부분의 길이가 maxOutputLength($maxLen) 이하여야 한다. 실제: ${truncatedPart.length}"
            }
            assertTrue(output.contains("[TRUNCATED:"), "잘라낸 출력에는 [TRUNCATED: 마커가 있어야 한다")
        }

        @Test
        fun `maxOutputLength 이내일 때 잘라내기 마커 없이 전체를 반환한다`() = runTest {
            val callback = McpToolCallback(
                client = client,
                name = "tool",
                description = "desc",
                mcpInputSchema = null,
                maxOutputLength = 1000
            )
            val shortText = "Hello"
            every { client.callTool(any()) } returns makeResult(listOf(shortText))

            val output = callback.call(emptyMap()).toString()

            assertEquals(shortText, output, "짧은 출력은 변환 없이 그대로 반환되어야 한다")
            assertFalse(output.contains("[TRUNCATED:"), "짧은 출력에는 [TRUNCATED: 마커가 없어야 한다")
        }

        @Test
        fun `DEFAULT_MAX_OUTPUT_LENGTH는 50000이다`() {
            assertEquals(
                50_000,
                McpToolCallback.DEFAULT_MAX_OUTPUT_LENGTH,
                "DEFAULT_MAX_OUTPUT_LENGTH가 예상 값(50000)과 달라 운영 장애가 발생할 수 있다"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 빈 출력 처리
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class EmptyOutput {

        @Test
        fun `콘텐츠와 structuredContent가 모두 없을 때 빈 문자열을 반환한다`() = runTest {
            val callback = McpToolCallback(
                client = client,
                name = "empty-tool",
                description = "desc",
                mcpInputSchema = null
            )
            every { client.callTool(any()) } returns makeResult(emptyList(), null)

            val output = callback.call(emptyMap()).toString()

            assertEquals("", output, "콘텐츠 없는 결과는 빈 문자열을 반환해야 한다")
        }

        @Test
        fun `공백만 있는 textContent는 빈 문자열로 처리된다`() = runTest {
            val callback = McpToolCallback(
                client = client,
                name = "whitespace-tool",
                description = "desc",
                mcpInputSchema = null
            )
            every { client.callTool(any()) } returns makeResult(listOf("   "), null)

            val output = callback.call(emptyMap()).toString()

            assertEquals("", output, "공백만 있는 콘텐츠는 빈 문자열을 반환해야 한다")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // inputSchema 직렬화
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class InputSchema {

        @Test
        fun `mcpInputSchema가 null이면 빈 스키마 JSON을 사용한다`() {
            val callback = McpToolCallback(
                client = client,
                name = "tool",
                description = "desc",
                mcpInputSchema = null
            )

            val schema = callback.inputSchema

            assertNotNull(schema, "inputSchema는 null이 아니어야 한다")
            assertTrue(schema.contains("\"type\""), "기본 스키마에는 'type' 필드가 있어야 한다")
            assertTrue(schema.contains("\"properties\""), "기본 스키마에는 'properties' 필드가 있어야 한다")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 혼합 콘텐츠 타입 처리
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class MixedContentTypes {

        @Test
        fun `ImageContent를 포함한 혼합 콘텐츠는 개행으로 연결한다`() = runTest {
            val callback = McpToolCallback(
                client = client,
                name = "mixed-tool",
                description = "desc",
                mcpInputSchema = null
            )
            // ImageContent를 mock으로 구성
            val imageContent = mockk<McpSchema.ImageContent>()
            every { imageContent.mimeType() } returns "image/png"
            val textContent = McpSchema.TextContent("텍스트 결과")

            val result = McpSchema.CallToolResult(
                listOf(textContent, imageContent),
                false,
                null,
                emptyMap()
            )
            every { client.callTool(any()) } returns result

            val output = callback.call(emptyMap()).toString()

            assertTrue(output.contains("텍스트 결과"), "텍스트 콘텐츠가 출력에 포함되어야 한다")
            assertTrue(output.contains("[Image: image/png]"), "이미지 콘텐츠가 '[Image: mime/type]' 형식으로 포함되어야 한다")
        }

        @Test
        fun `EmbeddedResource를 포함한 콘텐츠는 URI와 함께 표시된다`() = runTest {
            val callback = McpToolCallback(
                client = client,
                name = "resource-tool",
                description = "desc",
                mcpInputSchema = null
            )
            val resourceContent = mockk<McpSchema.EmbeddedResource>()
            val resourceContents = mockk<McpSchema.ResourceContents>()
            every { resourceContent.resource() } returns resourceContents
            every { resourceContents.uri() } returns "file:///report.pdf"

            val result = McpSchema.CallToolResult(
                listOf(resourceContent),
                false,
                null,
                emptyMap()
            )
            every { client.callTool(any()) } returns result

            val output = callback.call(emptyMap()).toString()

            assertTrue(
                output.contains("[Resource: file:///report.pdf]"),
                "EmbeddedResource는 '[Resource: URI]' 형식으로 표시되어야 한다"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // structuredContent 우선순위 로직
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class StructuredContentPriority {

        @Test
        fun `textContent와 structuredContent 모두 JSON일 때 textContent를 반환한다`() = runTest {
            val callback = McpToolCallback(
                client = client,
                name = "dual-json-tool",
                description = "desc",
                mcpInputSchema = null
            )
            val jsonText = """{"source":"text"}"""
            val result = McpSchema.CallToolResult(
                listOf(McpSchema.TextContent(jsonText)),
                false,
                mapOf("source" to "structured"),
                emptyMap()
            )
            every { client.callTool(any()) } returns result

            val output = callback.call(emptyMap()).toString()

            // textContent가 이미 JSON이면 textContent를 우선한다
            assertEquals(jsonText, output, "textContent가 JSON이면 structuredContent보다 우선해야 한다")
        }

        @Test
        fun `structuredContent가 비어있는 맵일 때 textContent를 반환한다`() = runTest {
            val callback = McpToolCallback(
                client = client,
                name = "empty-struct-tool",
                description = "desc",
                mcpInputSchema = null
            )
            val text = "일반 텍스트 결과"
            val result = McpSchema.CallToolResult(
                listOf(McpSchema.TextContent(text)),
                false,
                emptyMap<String, Any>(), // 직렬화하면 "{}" — 빈 맵은 null로 간주
                emptyMap()
            )
            every { client.callTool(any()) } returns result

            val output = callback.call(emptyMap()).toString()

            assertEquals(text, output, "structuredContent가 빈 맵({})이면 textContent를 사용해야 한다")
        }

        @Test
        fun `textContent가 없고 structuredContent가 JSON일 때 structuredContent를 반환한다`() = runTest {
            val callback = McpToolCallback(
                client = client,
                name = "struct-only-tool",
                description = "desc",
                mcpInputSchema = null
            )
            val structuredData = mapOf("items" to listOf("a", "b"))
            val result = McpSchema.CallToolResult(
                emptyList(), // textContent 없음
                false,
                structuredData,
                emptyMap()
            )
            every { client.callTool(any()) } returns result

            val output = callback.call(emptyMap()).toString()

            assertTrue(output.contains("items"), "textContent 없을 때 structuredContent가 반환되어야 한다")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 연결 오류 콜백 — onConnectionError 없는 경우
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    inner class ConnectionErrorHandling {

        @Test
        fun `onConnectionError 없이 callTool 실패 시 Error 문자열을 반환한다`() = runTest {
            val callback = McpToolCallback(
                client = client,
                name = "no-callback-tool",
                description = "desc",
                mcpInputSchema = null,
                onConnectionError = null  // 콜백 없음
            )
            every { client.callTool(any()) } throws RuntimeException("네트워크 오류")

            val output = callback.call(emptyMap()).toString()

            assertTrue(output.startsWith("Error:"), "연결 오류 시 Error: 로 시작하는 문자열을 반환해야 한다")
            assertFalse(output.contains("네트워크 오류"), "예외 메시지가 HTTP 응답에 노출되면 안 된다")
        }

        @Test
        fun `callTool 실패 시 예외 클래스명만 포함한다`() = runTest {
            val callback = McpToolCallback(
                client = client,
                name = "error-detail-tool",
                description = "desc",
                mcpInputSchema = null
            )
            every { client.callTool(any()) } throws IllegalStateException("상세 내부 메시지")

            val output = callback.call(emptyMap()).toString()

            assertTrue(
                output.contains("IllegalStateException"),
                "오류 응답에 예외 클래스명이 포함되어야 한다 (diagnose 가능 수준)"
            )
            assertFalse(
                output.contains("상세 내부 메시지"),
                "내부 예외 메시지가 응답에 포함되어 보안 정보가 노출되면 안 된다"
            )
        }
    }
}
