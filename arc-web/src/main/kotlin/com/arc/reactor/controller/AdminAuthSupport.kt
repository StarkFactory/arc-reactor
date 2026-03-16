package com.arc.reactor.controller

import com.arc.reactor.auth.AdminAuthorizationSupport
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ServerWebExchange

/**
 * 컨트롤러용 관리자 인가 공통 헬퍼.
 *
 * 실제 정책 검사는 [AdminAuthorizationSupport]에 위임합니다.
 */
fun isAdmin(exchange: ServerWebExchange): Boolean {
    return AdminAuthorizationSupport.isAdmin(exchange)
}

/** 대시보드 등 읽기 전용 화면을 위한 넓은 범위의 관리자 접근 검사. */
fun isAnyAdmin(exchange: ServerWebExchange): Boolean {
    return AdminAuthorizationSupport.isAnyAdmin(exchange)
}

/** 비관리자에 대한 표준 403 Forbidden 응답. */
fun forbiddenResponse(): ResponseEntity<Any> {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ErrorResponse(error = "Admin access required", timestamp = java.time.Instant.now().toString()))
}

/** 설명 메시지를 포함하는 표준 404 Not Found 응답. */
fun notFoundResponse(message: String): ResponseEntity<Any> =
    ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse(error = message, timestamp = java.time.Instant.now().toString()))

/** 설명 메시지를 포함하는 표준 409 Conflict 응답. */
fun conflictResponse(message: String): ResponseEntity<Any> =
    ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse(error = message, timestamp = java.time.Instant.now().toString()))

/** 설명 메시지를 포함하는 표준 400 Bad Request 응답. */
fun badRequestResponse(message: String): ResponseEntity<Any> =
    ResponseEntity.badRequest()
        .body(ErrorResponse(error = message, timestamp = java.time.Instant.now().toString()))

/** 관리자 감사 로그용 현재 actor ID를 추출한다. */
fun currentActor(exchange: ServerWebExchange): String {
    return AdminAuthorizationSupport.currentActor(exchange)
}
