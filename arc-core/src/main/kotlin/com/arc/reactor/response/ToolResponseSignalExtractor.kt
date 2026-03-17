package com.arc.reactor.response

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * 도구 응답에서 추출한 신호(signal) 데이터.
 *
 * 도구 출력 JSON에 포함된 근거(grounded), 응답 모드, 신선도(freshness),
 * 차단 사유, 전달 플랫폼 등의 메타 정보를 담는다.
 *
 * @param toolName 도구 이름
 * @param grounded 근거 기반 응답 여부
 * @param answerMode 응답 모드 (예: rag, direct 등)
 * @param freshness 데이터 신선도 메타데이터
 * @param retrievedAt 데이터 조회 시각
 * @param blockReason 차단 사유 (에러에서 추론 포함)
 * @param deliveryPlatform 전달 플랫폼 (예: slack)
 * @param deliveryMode 전달 방식 (예: message_send, thread_reply)
 */
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

/**
 * 도구 출력 JSON에서 [ToolResponseSignal]을 파싱하는 추출기.
 *
 * 도구 응답이 JSON 형식이면 `grounded`, `answerMode`, `freshness`, `retrievedAt`,
 * `blockReason` 등의 필드를 추출한다. `error` 필드가 존재하면 알려진 패턴과 대조하여
 * 차단 사유를 추론한다. Slack 전송 도구의 경우 전달 플랫폼/방식도 추출한다.
 *
 * 모든 신호가 `null`이면 `null`을 반환하여 불필요한 객체 생성을 방지한다.
 */
internal object ToolResponseSignalExtractor {
    private val objectMapper = jacksonObjectMapper()

    /**
     * 도구 출력 문자열에서 신호를 추출한다.
     *
     * @param toolName 도구 이름
     * @param output 도구 출력 문자열 (JSON 형식)
     * @return 추출된 신호. 유효한 신호가 없으면 `null`
     */
    fun extract(toolName: String, output: String): ToolResponseSignal? {
        val tree = parseJson(output) ?: return null

        // 개별 신호 필드 추출
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

        // blockReason: 명시적 필드 우선, 없으면 error 메시지에서 추론
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

        // Slack 전송 도구에서 전달 신호 추출
        val deliverySignal = extractDeliverySignal(toolName, tree)

        // 유효한 신호가 하나도 없으면 null 반환
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

    /** JSON 문자열을 파싱한다. 실패 시 `null` 반환. */
    private fun parseJson(output: String): JsonNode? {
        return runCatching { objectMapper.readTree(output) }.getOrNull()
    }

    /** JSON 객체 노드를 [Map]으로 변환한다. */
    private fun toMap(node: JsonNode): Map<String, Any?> {
        if (!node.isObject) return emptyMap()
        val result = linkedMapOf<String, Any?>()
        node.fieldNames().forEachRemaining { key ->
            result[key] = toValue(node.path(key))
        }
        return result
    }

    /** JSON 노드를 Kotlin 타입으로 변환한다. */
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

    /**
     * 에러 메시지에서 차단 사유를 추론한다.
     *
     * 알려진 에러 패턴(접근 거부, 인증 실패, 권한 부족, 속도 제한, 읽기 전용, 신원 미확인)과
     * 대조하여 정규화된 사유 코드를 반환한다.
     *
     * @param errorMessage 에러 메시지 (소문자 변환 후 매칭)
     * @return 추론된 차단 사유 코드. 매칭 없으면 `null`
     */
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

    /**
     * Slack 전송 도구의 성공 응답에서 전달 플랫폼/방식 쌍을 추출한다.
     *
     * @param toolName 도구 이름
     * @param tree JSON 응답 트리
     * @return (플랫폼, 방식) 쌍. 해당 없으면 `null`
     */
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
