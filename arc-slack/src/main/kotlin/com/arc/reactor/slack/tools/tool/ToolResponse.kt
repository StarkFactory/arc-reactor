package com.arc.reactor.slack.tools.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

private val objectMapper = jacksonObjectMapper()

/** 도구 결과 객체를 JSON 문자열로 직렬화한다. */
fun toJson(value: Any): String = objectMapper.writeValueAsString(value)

/** 에러 메시지를 `{"error": "..."}` 형식의 JSON 문자열로 반환한다. */
fun errorJson(message: String): String = toJson(mapOf("error" to message))
