package com.arc.reactor.scheduler.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** 스케줄러 도구 응답 직렬화를 위한 공유 ObjectMapper */
internal val objectMapper = jacksonObjectMapper()

/** 임의 객체를 JSON 문자열로 변환한다 */
internal fun toJson(value: Any): String = objectMapper.writeValueAsString(value)

/** 에러 응답 JSON을 생성한다 */
internal fun errorJson(message: String): String = toJson(mapOf("error" to message))
