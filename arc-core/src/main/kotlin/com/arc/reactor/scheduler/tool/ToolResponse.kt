package com.arc.reactor.scheduler.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

internal val objectMapper = jacksonObjectMapper()

internal fun toJson(value: Any): String = objectMapper.writeValueAsString(value)

internal fun errorJson(message: String): String = toJson(mapOf("error" to message))
