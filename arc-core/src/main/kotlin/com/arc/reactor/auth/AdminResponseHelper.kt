package com.arc.reactor.auth

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

/**
 * 관리자 API 공통 인증/응답 헬퍼.
 * arc-web, arc-admin 모듈의 중복 로직을 통합한다.
 */
object AdminResponseHelper {

    /** 개발자/관리자 접근 권한을 확인한다. */
    fun isAdmin(exchange: ServerWebExchange): Boolean =
        AdminAuthorizationSupport.isAdmin(exchange)

    /** 매니저 포함 광범위 관리자 접근을 확인한다. */
    fun isAnyAdmin(exchange: ServerWebExchange): Boolean =
        AdminAuthorizationSupport.isAnyAdmin(exchange)

    /** 현재 요청의 actor(사용자 ID)를 반환한다. */
    fun currentActor(exchange: ServerWebExchange): String =
        AdminAuthorizationSupport.currentActor(exchange)

    /** 비관리자에 대한 표준 403 Forbidden 응답. */
    fun <T> forbiddenResponse(errorFactory: (String, String) -> T): ResponseEntity<T> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(errorFactory("Admin access required", Instant.now().toString()))

    /** 설명 메시지를 포함하는 표준 404 Not Found 응답. */
    fun <T> notFoundResponse(message: String, errorFactory: (String, String) -> T): ResponseEntity<T> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(errorFactory(message, Instant.now().toString()))

    /** 설명 메시지를 포함하는 표준 400 Bad Request 응답. */
    fun <T> badRequestResponse(message: String, errorFactory: (String, String) -> T): ResponseEntity<T> =
        ResponseEntity.badRequest()
            .body(errorFactory(message, Instant.now().toString()))

    /** 설명 메시지를 포함하는 표준 409 Conflict 응답. */
    fun <T> conflictResponse(message: String, errorFactory: (String, String) -> T): ResponseEntity<T> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(errorFactory(message, Instant.now().toString()))
}
