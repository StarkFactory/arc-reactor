package com.arc.reactor.mcp

import com.arc.reactor.support.throwIfCancellation
import com.arc.reactor.tool.ToolCallback
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val objectMapper = jacksonObjectMapper()

/**
 * MCP 도구 콜백 래퍼
 *
 * MCP 도구를 Arc Reactor의 ToolCallback으로 래핑하여 통합 도구 처리를 가능하게 한다.
 *
 * ## 동작 방식
 * 1. 도구 호출 인자를 Map으로 받는다
 * 2. MCP CallToolRequest로 변환한다
 * 3. MCP 클라이언트를 통해 도구를 호출한다
 * 4. 텍스트 콘텐츠를 추출하여 반환한다
 *
 * [callTool]이 연결 수준 오류로 실패하면 [onConnectionError]가 호출되어
 * 호출자(예: [DefaultMcpManager])가 서버를 FAILED로 표시하고 재연결을 트리거한다.
 *
 * WHY: MCP 프로토콜의 도구를 에이전트가 내부 도구와 동일한 인터페이스로
 * 호출할 수 있게 하기 위함. ToolCallback 인터페이스를 구현하여
 * 에이전트가 MCP 도구인지 로컬 도구인지 구분할 필요 없게 한다.
 *
 * CLAUDE.md 규칙: ToolCallback은 throw하지 않고 "Error: ..." 문자열을 반환해야 한다.
 *
 * @param client MCP 클라이언트 연결
 * @param name 도구 식별자
 * @param description LLM을 위한 도구 설명
 * @param mcpInputSchema 도구 파라미터의 JSON 스키마 (선택)
 * @param maxOutputLength 출력 최대 문자 수 (초과 시 잘라냄)
 * @param onConnectionError 하위 MCP 호출 실패 시 호출됨 (취소가 아닌 경우). R330: 실패한
 *   클라이언트 인스턴스를 인자로 받아 호출자가 "현재 연결된 클라이언트가 이 failing client와
 *   동일한지" identity 비교를 할 수 있게 한다. connect() 재실행 후에도 stale callback이 도착해
 *   신규 클라이언트를 잘못 무효화하는 race를 차단하기 위한 시그니처.
 * @see DefaultMcpManager 도구 콜백 관리
 * @see com.arc.reactor.tool.ToolCallback 도구 콜백 인터페이스
 */
class McpToolCallback(
    private val client: McpSyncClient,
    override val name: String,
    override val description: String,
    private val mcpInputSchema: McpSchema.JsonSchema?,
    private val maxOutputLength: Int = DEFAULT_MAX_OUTPUT_LENGTH,
    private val onConnectionError: ((failingClient: McpSyncClient) -> Unit)? = null
) : ToolCallback {

    /** 입력 스키마를 JSON 문자열로 직렬화한다. 실패 시 기본 빈 스키마를 사용한다. */
    override val inputSchema: String = mcpInputSchema?.let {
        try {
            objectMapper.writeValueAsString(it)
        } catch (_: Exception) {
            """{"type":"object","properties":{}}"""
        }
    } ?: """{"type":"object","properties":{}}"""

    /**
     * MCP 도구를 호출하고 결과를 반환한다.
     *
     * 출력이 [maxOutputLength]를 초과하면 잘라내고 경고 로그를 남긴다.
     * 연결 오류 시 [onConnectionError]를 호출하여 재연결을 트리거한다.
     *
     * @param arguments 도구 호출 인자 맵
     * @return 도구 실행 결과 문자열, 또는 오류 시 "Error: ..." 문자열
     */
    override suspend fun call(arguments: Map<String, Any?>): Any? {
        return try {
            val request = McpSchema.CallToolRequest(name, arguments)
            // McpSyncClient.callTool은 블로킹 호출이므로 IO 디스패처로 전환한다
            val result = withContext(Dispatchers.IO) {
                client.callTool(request)
            }

            val output = extractOutput(result)

            // 출력이 최대 길이를 초과하면 잘라낸다
            if (output.length > maxOutputLength) {
                logger.warn { "MCP 도구 '$name' 출력 잘림: ${output.length} -> $maxOutputLength 문자" }
                output.take(maxOutputLength) +
                    "\n[TRUNCATED: output exceeded $maxOutputLength characters]"
            } else {
                output
            }
        } catch (e: Exception) {
            e.throwIfCancellation()
            logger.error(e) { "MCP 도구 호출 실패: $name" }
            // R330: 실패한 client ref를 함께 전달해 호출자가 identity 비교로 stale 이벤트를 걸러낸다
            onConnectionError?.invoke(client)
            "Error: MCP 도구 호출 중 오류가 발생했습니다 (${e.javaClass.simpleName})"
        }
    }

    /**
     * MCP CallToolResult에서 텍스트 출력을 추출한다.
     *
     * 출력 선택 우선순위:
     * 1. structuredContent가 JSON이고 textContent가 JSON이 아닌 경우 → structuredContent
     * 2. textContent가 비어있지 않은 경우 → textContent
     * 3. structuredContent가 있는 경우 → structuredContent
     * 4. 모두 비어있으면 빈 문자열
     *
     * WHY: MCP 서버마다 텍스트/구조화 콘텐츠 중 다른 것을 주로 사용한다.
     * JSON 페이로드는 구조화 콘텐츠에서, 비JSON은 텍스트에서 가져오는 것이 올바르다.
     */
    private fun extractOutput(result: McpSchema.CallToolResult): String {
        // 텍스트 콘텐츠 추출 (여러 콘텐츠 항목을 개행으로 연결)
        val textOutput = result.content().joinToString("\n") { content ->
            when (content) {
                is McpSchema.TextContent -> content.text()
                is McpSchema.ImageContent -> "[Image: ${content.mimeType()}]"
                is McpSchema.EmbeddedResource -> "[Resource: ${content.resource().uri()}]"
                else -> content.toString()
            }
        }.trim()

        // 구조화 콘텐츠 직렬화
        val structuredOutput = serializeStructuredContent(result.structuredContent())

        return when {
            structuredOutput != null && looksLikeJsonPayload(structuredOutput) && !looksLikeJsonPayload(textOutput) ->
                structuredOutput
            textOutput.isNotBlank() -> textOutput
            structuredOutput != null -> structuredOutput
            else -> ""
        }
    }

    /** 구조화 콘텐츠를 JSON 문자열로 직렬화한다. null/빈값이면 null 반환. */
    private fun serializeStructuredContent(structuredContent: Any?): String? {
        if (structuredContent == null) return null
        return runCatching { objectMapper.writeValueAsString(structuredContent) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" && it != "{}" && it != "[]" }
    }

    /** 문자열이 JSON 페이로드처럼 보이는지 확인한다 ({}나 []로 시작/끝). */
    private fun looksLikeJsonPayload(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return false
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }

    companion object {
        /** MCP 도구 출력의 기본 최대 문자 수 */
        const val DEFAULT_MAX_OUTPUT_LENGTH = 50_000
    }
}
