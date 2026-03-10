package com.arc.reactor.response

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class ToolResponseSignal(
    val toolName: String,
    val grounded: Boolean? = null,
    val answerMode: String? = null,
    val freshness: Map<String, Any?>? = null,
    val retrievedAt: String? = null,
    val blockReason: String? = null,
    val deliveryPlatform: String? = null,
    val deliveryMode: String? = null
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
        val deliverySignal = extractDeliverySignal(toolName, tree)

        if (
            grounded == null &&
            answerMode == null &&
            freshness == null &&
            retrievedAt == null &&
            blockReason == null &&
            deliverySignal == null
        ) {
            return null
        }

        return ToolResponseSignal(
            toolName = toolName,
            grounded = grounded,
            answerMode = answerMode,
            freshness = freshness,
            retrievedAt = retrievedAt,
            blockReason = blockReason,
            deliveryPlatform = deliverySignal?.first,
            deliveryMode = deliverySignal?.second
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
            "authentication failed" in normalized || "invalid api token" in normalized -> "upstream_auth_failed"
            "permission denied" in normalized || "not permitted to use confluence" in normalized ||
                "do not have permission to see it" in normalized ||
                "cannot access confluence" in normalized -> "upstream_permission_denied"
            "rate limit exceeded" in normalized || "too many requests" in normalized -> "upstream_rate_limited"
            "read-only" in normalized || "readonly" in normalized || "mutating tool is disabled" in normalized ->
                "read_only_mutation"
            "requester identity could not be resolved" in normalized ||
                "requesteremail mapping failed" in normalized ||
                "jira user found for supplied requesteremail" in normalized -> "identity_unresolved"
            "approval policy blocked" in normalized -> "policy_denied"
            else -> null
        }
    }

    private fun extractDeliverySignal(toolName: String, tree: JsonNode): Pair<String, String>? {
        val ok = tree.path("ok").takeIf(JsonNode::isBoolean)?.booleanValue() ?: return null
        if (!ok) return null
        return when (toolName) {
            "send_message" -> "slack" to "message_send"
            "reply_to_thread" -> "slack" to "thread_reply"
            else -> null
        }
    }
}
