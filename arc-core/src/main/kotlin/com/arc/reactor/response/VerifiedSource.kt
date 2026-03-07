package com.arc.reactor.response

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class VerifiedSource(
    val title: String,
    val url: String,
    val toolName: String? = null
)

internal object VerifiedSourceExtractor {
    private val objectMapper = jacksonObjectMapper()

    fun extract(toolName: String, output: String): List<VerifiedSource> {
        val tree = parseJson(output) ?: return emptyList()
        val normalizedTree = if (tree.isTextual) parseJson(tree.asText()) ?: tree else tree
        return collectSources(normalizedTree, toolName)
            .distinctBy { it.url }
            .take(MAX_SOURCES)
    }

    private fun parseJson(output: String): JsonNode? {
        return runCatching { objectMapper.readTree(output) }.getOrNull()
    }

    private fun collectSources(node: JsonNode, toolName: String): List<VerifiedSource> {
        val collected = LinkedHashSet<VerifiedSource>()
        walk(node, toolName, collected)
        return collected.toList()
    }

    private fun walk(node: JsonNode, toolName: String, out: MutableSet<VerifiedSource>) {
        if (node.isObject) {
            toVerifiedSource(node, toolName)?.let(out::add)
            node.fieldNames().forEachRemaining { field -> walk(node.path(field), toolName, out) }
            return
        }
        if (node.isArray) node.forEach { child -> walk(child, toolName, out) }
    }

    private fun toVerifiedSource(node: JsonNode, toolName: String): VerifiedSource? {
        val url = readFirstText(node, URL_FIELDS) ?: return null
        if (!url.startsWith("http", ignoreCase = true) || isBlockedUrl(url)) return null
        val title = readFirstText(node, TITLE_FIELDS) ?: inferTitle(url)
        return VerifiedSource(title = title, url = url, toolName = toolName)
    }

    private fun readFirstText(node: JsonNode, fields: List<String>): String? {
        return fields.asSequence()
            .mapNotNull { field -> node.path(field).takeIf { !it.isMissingNode && !it.isNull }?.asText()?.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun inferTitle(url: String): String {
        val lastSegment = url.substringAfterLast('/').substringBefore('?').trim()
        return lastSegment.ifBlank { url }
    }

    private fun isBlockedUrl(url: String): Boolean {
        return BLOCKED_URL_PATTERNS.any { pattern -> url.contains(pattern, ignoreCase = true) }
    }

    private val URL_FIELDS = listOf("url", "webUrl", "htmlUrl", "link", "sourceUrl", "specUrl", "href")
    private val TITLE_FIELDS = listOf("title", "name", "summary", "key", "id", "specName")
    private val BLOCKED_URL_PATTERNS = listOf("/rest/api/", "/api/3/", "/download/attachments/")
    private const val MAX_SOURCES = 12
}
