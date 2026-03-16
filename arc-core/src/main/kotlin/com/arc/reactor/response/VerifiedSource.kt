package com.arc.reactor.response

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * 검증된 출처 정보.
 *
 * 도구 출력에서 추출된 URL 기반 출처를 나타낸다.
 * 응답에 출처 섹션을 첨부하는 데 사용된다.
 *
 * @param title 출처 제목 (문서명, 이슈 키 등)
 * @param url 출처 URL
 * @param toolName 이 출처를 제공한 도구 이름 (선택적)
 */
data class VerifiedSource(
    val title: String,
    val url: String,
    val toolName: String? = null
)

/**
 * 도구 출력 JSON에서 검증된 출처를 추출하는 내부 유틸리티.
 *
 * JSON 트리를 재귀적으로 순회하며 URL 필드를 찾아 VerifiedSource로 변환한다.
 * URL 중복 제거 및 최대 출처 수 제한을 적용한다.
 */
internal object VerifiedSourceExtractor {
    private val objectMapper = jacksonObjectMapper()

    /**
     * 도구 출력에서 검증된 출처를 추출한다.
     *
     * @param toolName 출처를 제공한 도구 이름
     * @param output 도구 출력 문자열 (JSON 형식)
     * @return URL 중복 제거된 출처 목록 (최대 MAX_SOURCES개)
     */
    fun extract(toolName: String, output: String): List<VerifiedSource> {
        val tree = parseJson(output) ?: return emptyList()
        // 텍스트 노드인 경우 한 번 더 파싱 시도 (이스케이프된 JSON 처리)
        val normalizedTree = if (tree.isTextual) parseJson(tree.asText()) ?: tree else tree
        return collectSources(normalizedTree, toolName)
            .distinctBy { it.url }
            .take(MAX_SOURCES)
    }

    /** JSON 문자열을 파싱한다. 실패 시 null. */
    private fun parseJson(output: String): JsonNode? {
        return runCatching { objectMapper.readTree(output) }.getOrNull()
    }

    /** JSON 트리에서 모든 출처를 수집한다. */
    private fun collectSources(node: JsonNode, toolName: String): List<VerifiedSource> {
        val collected = LinkedHashSet<VerifiedSource>()
        walk(node, null, toolName, collected)
        return collected.toList()
    }

    /**
     * JSON 트리를 재귀적으로 순회하며 URL이 포함된 노드를 찾는다.
     * 오브젝트, 텍스트, 배열 각각에 대해 다른 추출 전략을 적용한다.
     */
    private fun walk(
        node: JsonNode,
        parentField: String?,
        toolName: String,
        out: MutableSet<VerifiedSource>
    ) {
        if (node.isObject) {
            toVerifiedSource(node, toolName)?.let(out::add)
            node.fieldNames().forEachRemaining { field -> walk(node.path(field), field, toolName, out) }
            return
        }
        if (node.isTextual) {
            readUrlTextNode(parentField, node, toolName)?.let(out::add)
            return
        }
        if (node.isArray) node.forEach { child -> walk(child, toolName, out) }
    }

    private fun walk(node: JsonNode, toolName: String, out: MutableSet<VerifiedSource>) {
        walk(node, null, toolName, out)
    }

    /**
     * JSON 오브젝트 노드에서 URL과 제목을 추출하여 VerifiedSource를 생성한다.
     * URL 필드가 없거나 차단된 URL이면 null을 반환한다.
     */
    private fun toVerifiedSource(node: JsonNode, toolName: String): VerifiedSource? {
        val url = readFirstText(node, URL_FIELDS) ?: return null
        if (!url.startsWith("http", ignoreCase = true) || isBlockedUrl(url)) return null
        val title = readFirstText(node, TITLE_FIELDS) ?: inferTitle(url)
        return VerifiedSource(title = title, url = url, toolName = toolName)
    }

    /** 텍스트 노드가 URL 필드에 해당하면 VerifiedSource로 변환한다. */
    private fun readUrlTextNode(
        parentField: String?,
        node: JsonNode,
        toolName: String
    ): VerifiedSource? {
        if (!node.isTextual) return null
        if (parentField == null || !URL_TEXT_FIELDS.contains(parentField)) return null
        val value = node.asText().trim()
        if (!value.startsWith("http", ignoreCase = true) || isBlockedUrl(value)) return null
        return VerifiedSource(
            title = inferTitle(value),
            url = value,
            toolName = toolName
        )
    }

    /** 여러 필드명 중 첫 번째 유효한 텍스트 값을 반환한다. */
    private fun readFirstText(node: JsonNode, fields: List<String>): String? {
        return fields.asSequence()
            .mapNotNull { field -> node.path(field).takeIf { !it.isMissingNode && !it.isNull }?.asText()?.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    /** URL에서 제목을 추론한다. 마지막 경로 세그먼트를 사용. */
    private fun inferTitle(url: String): String {
        val lastSegment = url.substringAfterLast('/').substringBefore('?').trim()
        return lastSegment.ifBlank { url }
    }

    /** 차단된 URL 패턴인지 확인한다 (예: 첨부파일 다운로드 링크). */
    private fun isBlockedUrl(url: String): Boolean {
        return BLOCKED_URL_PATTERNS.any { pattern -> url.contains(pattern, ignoreCase = true) }
    }

    /** URL로 인식하는 JSON 필드명 목록 */
    private val URL_FIELDS = listOf(
        "url", "webUrl", "htmlUrl", "link", "sourceUrl", "specUrl", "href", "self", "webui"
    )
    /** 텍스트 노드에서 URL로 취급하는 부모 필드명 집합 */
    private val URL_TEXT_FIELDS = setOf("self", "webUrl", "htmlUrl", "url", "link", "specUrl", "sourceUrl", "href", "webui")
    /** 제목으로 사용할 JSON 필드명 목록 (우선순위순) */
    private val TITLE_FIELDS = listOf("title", "name", "summary", "key", "id", "specName")
    /** 출처에서 제외할 URL 패턴 */
    private val BLOCKED_URL_PATTERNS = listOf("/download/attachments/")
    /** 최대 출처 수 */
    private const val MAX_SOURCES = 12
}
