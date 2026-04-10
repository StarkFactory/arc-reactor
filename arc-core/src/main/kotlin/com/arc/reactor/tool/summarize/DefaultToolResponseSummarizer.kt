package com.arc.reactor.tool.summarize

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 기본 휴리스틱 기반 [ToolResponseSummarizer] 구현체.
 *
 * JSON 파싱을 시도하여 다음 순서로 분류한다:
 *
 * 1. 실패 응답 (success=false) 또는 `error` 필드 존재 → [SummaryKind.ERROR_CAUSE_FIRST]
 * 2. 빈 payload → [SummaryKind.EMPTY]
 * 3. JSON 배열 루트 → [SummaryKind.LIST_TOP_N]
 * 4. JSON 객체에 알려진 리스트 필드 (issues, pages, pullRequests, repositories, results, items) → [SummaryKind.LIST_TOP_N]
 * 5. JSON 객체 기타 → [SummaryKind.STRUCTURED]
 * 6. 긴 텍스트 (> textFullThreshold) → [SummaryKind.TEXT_HEAD_TAIL]
 * 7. 짧은 텍스트 → [SummaryKind.TEXT_FULL]
 *
 * @param config 요약 동작 설정 (head/tail 길이, topN 등)
 */
class DefaultToolResponseSummarizer(
    private val config: ToolResponseSummarizerConfig = ToolResponseSummarizerConfig()
) : ToolResponseSummarizer {

    private val objectMapper = jacksonObjectMapper()

    override fun summarize(
        toolName: String,
        rawPayload: String,
        success: Boolean
    ): ToolResponseSummary? {
        return try {
            summarizeInternal(toolName, rawPayload, success)
        } catch (e: Exception) {
            logger.debug(e) { "요약 생성 실패 (무시): tool=$toolName, length=${rawPayload.length}" }
            null
        }
    }

    private fun summarizeInternal(
        toolName: String,
        rawPayload: String,
        success: Boolean
    ): ToolResponseSummary? {
        val originalLength = rawPayload.length
        val trimmed = rawPayload.trim()

        if (trimmed.isEmpty()) {
            return ToolResponseSummary(
                text = "응답 없음",
                kind = SummaryKind.EMPTY,
                originalLength = originalLength
            )
        }

        // JSON 파싱 시도
        val node = tryParseJson(trimmed)

        // 에러 응답 우선 처리 (success=false 이거나 error 필드 존재)
        if (!success || hasErrorField(node)) {
            return summarizeError(trimmed, node, originalLength)
        }

        // JSON 구조 기반 분류
        if (node != null) {
            return summarizeJson(toolName, node, originalLength)
        }

        // 일반 텍스트
        return summarizeText(trimmed, originalLength)
    }

    private fun tryParseJson(trimmed: String): JsonNode? {
        return runCatching { objectMapper.readTree(trimmed) }.getOrNull()
    }

    private fun hasErrorField(node: JsonNode?): Boolean {
        if (node == null || !node.isObject) return false
        val errorNode = node.get("error") ?: return false
        return !errorNode.isNull && !(errorNode.isTextual && errorNode.textValue().isBlank())
    }

    /** 에러 응답은 원인을 앞에 배치한다. */
    private fun summarizeError(
        trimmed: String,
        node: JsonNode?,
        originalLength: Int
    ): ToolResponseSummary {
        val errorText = when {
            node != null && node.has("error") -> node.get("error").asText()
            trimmed.startsWith("Error:") -> trimmed.substringAfter("Error:").trim()
            else -> trimmed
        }
        val truncated = errorText.truncate(config.errorMaxChars)
        return ToolResponseSummary(
            text = "에러: $truncated",
            kind = SummaryKind.ERROR_CAUSE_FIRST,
            originalLength = originalLength
        )
    }

    /** JSON 구조 기반 요약. */
    private fun summarizeJson(
        toolName: String,
        node: JsonNode,
        originalLength: Int
    ): ToolResponseSummary {
        // 루트가 배열
        if (node.isArray) {
            return summarizeArray(node, originalLength)
        }
        // 객체에 알려진 리스트 필드
        if (node.isObject) {
            val (listField, arrayNode) = findKnownListField(node)
            if (listField != null && arrayNode != null) {
                return summarizeArray(
                    array = arrayNode,
                    originalLength = originalLength,
                    label = listField
                )
            }
            return summarizeStructured(node, originalLength)
        }
        // primitive — 텍스트로 처리
        return summarizeText(node.asText(), originalLength)
    }

    /** 알려진 리스트 필드 이름을 찾아서 반환한다 (우선순위 순). */
    private fun findKnownListField(node: JsonNode): Pair<String?, JsonNode?> {
        for (field in KNOWN_LIST_FIELDS) {
            val child = node.get(field) ?: continue
            if (child.isArray) return field to child
        }
        return null to null
    }

    /** 배열 요약 — 상위 N개 key 추출 + 전체 개수. */
    private fun summarizeArray(
        array: JsonNode,
        originalLength: Int,
        label: String = "items"
    ): ToolResponseSummary {
        val total = array.size()
        if (total == 0) {
            return ToolResponseSummary(
                text = "$label: 0건",
                kind = SummaryKind.LIST_TOP_N,
                originalLength = originalLength,
                itemCount = 0
            )
        }
        val topKeys = extractTopIdentifiers(array, config.listTopN)
        val firstKey = topKeys.firstOrNull()
        val preview = if (topKeys.isNotEmpty()) {
            topKeys.joinToString(prefix = " [", separator = ", ", postfix = "]")
        } else {
            ""
        }
        return ToolResponseSummary(
            text = "$label: ${total}건$preview",
            kind = SummaryKind.LIST_TOP_N,
            originalLength = originalLength,
            itemCount = total,
            primaryKey = firstKey
        )
    }

    /** 리스트 항목에서 상위 N개의 식별자를 추출한다. */
    private fun extractTopIdentifiers(array: JsonNode, n: Int): List<String> {
        val limit = minOf(n, array.size())
        val result = ArrayList<String>(limit)
        for (i in 0 until limit) {
            val item = array.get(i) ?: continue
            val id = extractIdentifier(item) ?: continue
            result.add(id)
        }
        return result
    }

    /** 단일 항목에서 식별자를 추출한다. */
    private fun extractIdentifier(item: JsonNode): String? {
        if (item.isTextual) return item.asText().takeIf { it.isNotBlank() }?.truncate(config.idMaxChars)
        if (!item.isObject) return null
        for (key in IDENTIFIER_FIELDS) {
            val field = item.get(key) ?: continue
            val text = field.asText()
            if (text.isNotBlank()) return text.truncate(config.idMaxChars)
        }
        return null
    }

    /** 구조화 객체 — 주요 필드 이름과 타입을 나열. */
    private fun summarizeStructured(
        node: JsonNode,
        originalLength: Int
    ): ToolResponseSummary {
        val fields = node.fieldNames().asSequence().toList()
        val previewFields = fields.take(config.structuredFieldPreview)
        val text = if (fields.size <= config.structuredFieldPreview) {
            "필드(${fields.size}): ${previewFields.joinToString()}"
        } else {
            "필드(${fields.size}): ${previewFields.joinToString()}..."
        }
        return ToolResponseSummary(
            text = text,
            kind = SummaryKind.STRUCTURED,
            originalLength = originalLength
        )
    }

    /** 텍스트 요약 — 길이에 따라 전체 또는 head+tail. */
    private fun summarizeText(
        text: String,
        originalLength: Int
    ): ToolResponseSummary {
        val trimmed = text.trim()
        if (trimmed.length <= config.textFullThreshold) {
            return ToolResponseSummary(
                text = trimmed,
                kind = SummaryKind.TEXT_FULL,
                originalLength = originalLength
            )
        }
        val head = trimmed.take(config.textHeadChars).trim()
        val tail = trimmed.takeLast(config.textTailChars).trim()
        return ToolResponseSummary(
            text = "$head ... [${trimmed.length}자 중 ${config.textHeadChars + config.textTailChars}자 발췌] ... $tail",
            kind = SummaryKind.TEXT_HEAD_TAIL,
            originalLength = originalLength
        )
    }

    private fun String.truncate(max: Int): String {
        if (length <= max) return this
        return take(max) + "..."
    }

    companion object {
        /** 리스트를 담을 수 있는 알려진 필드 이름 (우선순위 순). */
        internal val KNOWN_LIST_FIELDS = listOf(
            "issues", "pages", "pullRequests", "prs",
            "repositories", "repos", "results", "items",
            "data", "records", "list"
        )

        /** 리스트 항목에서 식별자로 사용할 필드 (우선순위 순). */
        internal val IDENTIFIER_FIELDS = listOf(
            "key", "id", "issueKey", "prId", "pullRequestId",
            "title", "name", "summary", "slug"
        )
    }
}

/**
 * [DefaultToolResponseSummarizer] 동작 설정.
 *
 * @property textFullThreshold 이 길이(문자) 이하는 전체 포함 ([SummaryKind.TEXT_FULL])
 * @property textHeadChars 긴 텍스트의 앞 부분 문자 수
 * @property textTailChars 긴 텍스트의 뒤 부분 문자 수
 * @property listTopN 리스트 요약 시 상위 N개 항목의 식별자 추출
 * @property structuredFieldPreview 구조화 객체 요약 시 미리보기 필드 수
 * @property errorMaxChars 에러 메시지 최대 길이
 * @property idMaxChars 각 식별자 최대 길이
 */
data class ToolResponseSummarizerConfig(
    val textFullThreshold: Int = 200,
    val textHeadChars: Int = 150,
    val textTailChars: Int = 100,
    val listTopN: Int = 5,
    val structuredFieldPreview: Int = 5,
    val errorMaxChars: Int = 200,
    val idMaxChars: Int = 80
)
