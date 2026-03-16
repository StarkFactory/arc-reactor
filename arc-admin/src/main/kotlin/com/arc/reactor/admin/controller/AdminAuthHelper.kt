package com.arc.reactor.admin.controller

import com.arc.reactor.auth.AdminAuthorizationSupport
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

/** 관리자 API 오류 응답 DTO. */
data class AdminErrorResponse(
    val error: String,
    val timestamp: String = Instant.now().toString()
)

/**
 * 현재 요청이 관리자 사용자인지 확인한다.
 * 정책 확인을 [AdminAuthorizationSupport]에 위임한다.
 */
fun isAdmin(exchange: ServerWebExchange): Boolean {
    return AdminAuthorizationSupport.isAdmin(exchange)
}

/** 관리자 또는 관리자-개발자 역할인지 확인한다. */
fun isAnyAdmin(exchange: ServerWebExchange): Boolean {
    return AdminAuthorizationSupport.isAnyAdmin(exchange)
}

/** 현재 요청의 관리자 식별자를 반환한다. */
fun currentActor(exchange: ServerWebExchange): String {
    return AdminAuthorizationSupport.currentActor(exchange)
}

/** 403 Forbidden 응답을 생성한다. 반드시 [AdminErrorResponse] 본문을 포함한다. */
fun forbiddenResponse(): ResponseEntity<Any> =
    ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(AdminErrorResponse(error = "Admin access required"))

fun notFoundResponse(message: String): ResponseEntity<Any> =
    ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(AdminErrorResponse(error = message))

fun conflictResponse(message: String): ResponseEntity<Any> =
    ResponseEntity.status(HttpStatus.CONFLICT)
        .body(AdminErrorResponse(error = message))

fun badRequestResponse(message: String): ResponseEntity<Any> =
    ResponseEntity.badRequest()
        .body(AdminErrorResponse(error = message))
