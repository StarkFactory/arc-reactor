package com.arc.reactor.agent.impl

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Normalizes tool response payloads so providers that require strict JSON
 * (Gemini/Vertex in Spring AI) can consume tool outputs safely.
 */
internal object ToolResponsePayloadNormalizer {

    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)

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

    private fun isStrictJson(value: String): Boolean {
        return runCatching {
            objectMapper.readValue(value, Any::class.java)
            true
        }.getOrDefault(false)
    }

    private fun wrapAsResult(value: String): String {
        return objectMapper.writeValueAsString(mapOf("result" to value))
    }
}
