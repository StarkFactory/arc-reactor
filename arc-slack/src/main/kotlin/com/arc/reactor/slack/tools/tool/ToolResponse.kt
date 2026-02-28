package com.arc.reactor.slack.tools.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

private val objectMapper = jacksonObjectMapper()

fun toJson(value: Any): String = objectMapper.writeValueAsString(value)

fun errorJson(message: String): String = toJson(mapOf("error" to message))
