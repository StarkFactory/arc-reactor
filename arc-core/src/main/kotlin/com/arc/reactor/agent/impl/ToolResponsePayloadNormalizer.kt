package com.arc.reactor.agent.impl

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * 도구 응답 페이로드를 정규화하여 엄격한 JSON을 요구하는 프로바이더
 * (Spring AI의 Gemini/Vertex)가 도구 출력을 안전하게 사용할 수 있도록 한다.
 *
 * 이미 유효한 JSON이면 그대로 반환하고, 아니면 `{"result": "원본 텍스트"}` 형태로 래핑한다.
 *
 * @see ToolCallOrchestrator Google GenAI 프로바이더 사용 시 이 정규화 적용
 */
internal object ToolResponsePayloadNormalizer {

    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)

    /**
     * 엄격한 JSON 프로바이더를 위해 페이로드를 정규화한다.
     *
     * @param rawPayload 원본 도구 출력
     * @return 유효한 JSON이면 그대로, 아니면 JSON 객체로 래핑된 문자열
     */
    fun normalizeForStrictJsonProvider(rawPayload: String): String {
        val candidate = rawPayload.trim()
        if (candidate.isEmpty()) {
            return wrapAsResult(rawPayload)
        }
        if (isStrictJson(candidate)) {
            return candidate
        }
        return wrapAsResult(rawPayload)
    }

    /** Jackson으로 엄격한 JSON 유효성을 검증한다. 후행 토큰이 있으면 실패. */
    private fun isStrictJson(value: String): Boolean {
        return runCatching {
            objectMapper.readValue(value, Any::class.java)
            true
        }.getOrElse { false }
    }

    /** 텍스트를 `{"result": "..."}` 형태의 JSON 객체로 래핑한다. */
    private fun wrapAsResult(value: String): String {
        return objectMapper.writeValueAsString(mapOf("result" to value))
    }
}
