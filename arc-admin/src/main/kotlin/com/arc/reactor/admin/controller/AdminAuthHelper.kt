package com.arc.reactor.admin.controller

import com.arc.reactor.auth.AdminResponseHelper
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ServerWebExchange
import java.time.Instant

/** 관리자 API 오류 응답 DTO. */
data class AdminErrorResponse(
    val error: String,
    val timestamp: String = Instant.now().toString()
)

/** arc-admin용 오류 응답 팩토리. */
private val errorFactory: (String, String) -> AdminErrorResponse = ::AdminErrorResponse

/**
 * 관리자 API 인가 공통 헬퍼.
 *
 * 실제 로직은 [AdminResponseHelper]에 위임한다.
 */
fun isAdmin(exchange: ServerWebExchange): Boolean =
    AdminResponseHelper.isAdmin(exchange)

/** 관리자 또는 관리자-개발자 역할인지 확인한다. */
fun isAnyAdmin(exchange: ServerWebExchange): Boolean =
    AdminResponseHelper.isAnyAdmin(exchange)

/** 현재 요청의 관리자 식별자를 반환한다. */
fun currentActor(exchange: ServerWebExchange): String =
    AdminResponseHelper.currentActor(exchange)

/** 403 Forbidden 응답을 생성한다. 반드시 [AdminErrorResponse] 본문을 포함한다. */
fun forbiddenResponse(): ResponseEntity<Any> =
    AdminResponseHelper.forbiddenResponse(errorFactory)

fun notFoundResponse(message: String): ResponseEntity<Any> =
    AdminResponseHelper.notFoundResponse(message, errorFactory)

fun conflictResponse(message: String): ResponseEntity<Any> =
    AdminResponseHelper.conflictResponse(message, errorFactory)

fun badRequestResponse(message: String): ResponseEntity<Any> =
    AdminResponseHelper.badRequestResponse(message, errorFactory)
