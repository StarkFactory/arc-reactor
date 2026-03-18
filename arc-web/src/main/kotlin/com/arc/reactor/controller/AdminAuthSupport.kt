package com.arc.reactor.controller

import com.arc.reactor.auth.AdminResponseHelper
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ServerWebExchange

/** arc-web용 오류 응답 팩토리. [ErrorResponse] 생성자를 래핑한다. */
private val errorFactory: (String, String) -> ErrorResponse = { error, timestamp ->
    ErrorResponse(error = error, timestamp = timestamp)
}

/**
 * 컨트롤러용 관리자 인가 공통 헬퍼.
 *
 * 실제 로직은 [AdminResponseHelper]에 위임한다.
 */
fun isAdmin(exchange: ServerWebExchange): Boolean =
    AdminResponseHelper.isAdmin(exchange)

/** 대시보드 등 읽기 전용 화면을 위한 넓은 범위의 관리자 접근 검사. */
fun isAnyAdmin(exchange: ServerWebExchange): Boolean =
    AdminResponseHelper.isAnyAdmin(exchange)

/** 관리자 감사 로그용 현재 actor ID를 추출한다. */
fun currentActor(exchange: ServerWebExchange): String =
    AdminResponseHelper.currentActor(exchange)

/** 비관리자에 대한 표준 403 Forbidden 응답. */
fun forbiddenResponse(): ResponseEntity<Any> =
    AdminResponseHelper.forbiddenResponse(errorFactory)

/** 설명 메시지를 포함하는 표준 404 Not Found 응답. */
fun notFoundResponse(message: String): ResponseEntity<Any> =
    AdminResponseHelper.notFoundResponse(message, errorFactory)

/** 설명 메시지를 포함하는 표준 400 Bad Request 응답. */
fun badRequestResponse(message: String): ResponseEntity<Any> =
    AdminResponseHelper.badRequestResponse(message, errorFactory)

/** 설명 메시지를 포함하는 표준 409 Conflict 응답. */
fun conflictResponse(message: String): ResponseEntity<Any> =
    AdminResponseHelper.conflictResponse(message, errorFactory)
