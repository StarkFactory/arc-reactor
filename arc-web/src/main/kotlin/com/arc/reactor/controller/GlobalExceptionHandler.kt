package com.arc.reactor.controller

import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.reactive.resource.NoResourceFoundException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebInputException
import kotlinx.coroutines.CancellationException
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * 표준화된 오류 응답을 위한 전역 예외 핸들러.
 *
 * 모든 컨트롤러에 걸쳐 일관된 오류 형식을 제공합니다:
 * - 유효성 검사 오류 (400) -- 필드 수준 상세 정보 포함
 * - 잘못된 요청 오류 (400) -- 형식 오류 입력
 * - 일반 오류 (500) -- 마스킹된 메시지 (스택 트레이스 미포함)
 *
 * WHY: 보안을 위해 500 에러의 상세 메시지는 클라이언트에 노출하지 않는다.
 * 내부 오류 정보는 서버 로그에만 기록된다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(WebExchangeBindException::class)
    fun handleValidationErrors(ex: WebExchangeBindException): ResponseEntity<ErrorResponse> {
        val fieldErrors = ex.bindingResult.fieldErrors.associate {
            it.field to (it.defaultMessage ?: "Invalid value")
        }
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error = "Validation failed",
                details = fieldErrors,
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(ServerWebInputException::class)
    fun handleInputException(ex: ServerWebInputException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error = "Invalid request: ${ex.reason ?: "Bad request"}",
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(FileSizeLimitException::class)
    fun handleFileSizeLimit(ex: FileSizeLimitException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error = ex.reason ?: "File upload limit exceeded",
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(ex: NoResourceFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(
                error = "Not found",
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<ErrorResponse> {
        val message = ex.reason?.takeIf { it.isNotBlank() } ?: ex.statusCode.toString()
        return ResponseEntity.status(ex.statusCode).body(
            ErrorResponse(
                error = message,
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        logger.warn(ex) { "Bad request: ${ex.message}" }
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error = "Bad request",
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "Illegal state" }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(
                error = "Internal server error",
                timestamp = Instant.now().toString()
            )
        )
    }

    /** 코루틴 취소를 500이 아닌 503으로 처리하고 재전파한다. */
    @ExceptionHandler(CancellationException::class)
    fun handleCancellation(ex: CancellationException): ResponseEntity<Void> {
        logger.debug { "Request cancelled: ${ex.message}" }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        if (ex is CancellationException) throw ex
        logger.error(ex) { "Unhandled exception" }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(
                error = "Internal server error",
                timestamp = Instant.now().toString()
            )
        )
    }
}

/** 표준화된 오류 응답 DTO. */
data class ErrorResponse(
    val error: String,
    val details: Map<String, String>? = null,
    val timestamp: String
)
