package com.arc.reactor.response

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class ToolResponseSignal(
    val toolName: String,
    val grounded: Boolean? = null,
    val answerMode: String? = null,
    val freshness: Map<String, Any?>? = null,
    val retrievedAt: String? = null,
    val blockReason: String? = null
)

internal object ToolResponseSignalExtractor {
    private val objectMapper = jacksonObjectMapper()

    fun extract(toolName: String, output: String): ToolResponseSignal? {
        val tree = parseJson(output) ?: return null

        val grounded = tree.path("grounded").takeIf(JsonNode::isBoolean)?.booleanValue()
        val answerMode = tree.path("answerMode")
            .takeIf { !it.isMissingNode && !it.isNull }
            ?.asText()
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val freshness = tree.path("freshness")
            .takeIf { !it.isMissingNode && !it.isNull }
            ?.let(::toMap)
            ?.takeIf(Map<String, Any?>::isNotEmpty)
        val retrievedAt = tree.path("retrievedAt")
            .takeIf { !it.isMissingNode && !it.isNull }
            ?.asText()
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val blockReason = tree.path("blockReason")
            .takeIf { !it.isMissingNode && !it.isNull }
            ?.asText()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: inferBlockReason(
                tree.path("error")
                    .takeIf { !it.isMissingNode && !it.isNull }
                    ?.asText()
                    ?.trim()
            )

        if (grounded == null && answerMode == null && freshness == null && retrievedAt == null && blockReason == null) {
            return null
        }

        return ToolResponseSignal(
            toolName = toolName,
            grounded = grounded,
            answerMode = answerMode,
            freshness = freshness,
            retrievedAt = retrievedAt,
            blockReason = blockReason
        )
    }

    private fun parseJson(output: String): JsonNode? {
        return runCatching { objectMapper.readTree(output) }.getOrNull()
    }

    private fun toMap(node: JsonNode): Map<String, Any?> {
        if (!node.isObject) return emptyMap()
        val result = linkedMapOf<String, Any?>()
        node.fieldNames().forEachRemaining { key ->
            result[key] = toValue(node.path(key))
        }
        return result
    }

    private fun toValue(node: JsonNode): Any? {
        return when {
            node.isNull -> null
            node.isTextual -> node.asText()
            node.isBoolean -> node.booleanValue()
            node.isIntegralNumber -> node.longValue()
            node.isFloatingPointNumber -> node.doubleValue()
            node.isArray -> node.map(::toValue)
            node.isObject -> toMap(node)
            else -> node.asText()
        }
    }

    private fun inferBlockReason(errorMessage: String?): String? {
        val normalized = errorMessage?.lowercase()?.trim()?.takeIf(String::isNotBlank) ?: return null
        return when {
            "access denied" in normalized || "not allowed" in normalized -> "policy_denied"
            "read-only" in normalized || "readonly" in normalized || "mutating tool is disabled" in normalized ->
                "read_only_mutation"
            "approval policy blocked" in normalized -> "policy_denied"
            else -> null
        }
    }
}
