package com.arc.reactor.agent.impl

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}
private val objectMapper = jacksonObjectMapper()
private val mapTypeRef = object : TypeReference<Map<String, Any?>>() {}

internal fun parseToolArguments(json: String?): Map<String, Any?> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        objectMapper.readValue(json, mapTypeRef)
    } catch (e: Exception) {
        logger.warn(e) { "Failed to parse JSON arguments" }
        emptyMap()
    }
}
