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
            it.field to (it.defaultMessage ?: "유효하지 않은 값입니다")
        }
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error = "요청 형식이 올바르지 않습니다",
                details = fieldErrors,
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(ServerWebInputException::class)
    fun handleInputException(ex: ServerWebInputException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error = "잘못된 요청입니다",
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(FileSizeLimitException::class)
    fun handleFileSizeLimit(ex: FileSizeLimitException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error = "입력 길이가 제한을 초과했습니다",
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(ex: NoResourceFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(
                error = "요청한 리소스를 찾을 수 없습니다",
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ResponseEntity<ErrorResponse> {
        val message = statusMessageFor(ex.statusCode.value())
        return ResponseEntity.status(ex.statusCode).body(
            ErrorResponse(
                error = message,
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        logger.warn(ex) { "잘못된 요청" }
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error = "잘못된 요청입니다",
                timestamp = Instant.now().toString()
            )
        )
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "잘못된 상태 예외" }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(
                error = "서버 오류가 발생했습니다",
                timestamp = Instant.now().toString()
            )
        )
    }

    /** 코루틴 취소를 500이 아닌 503으로 처리하고 재전파한다. */
    @ExceptionHandler(CancellationException::class)
    fun handleCancellation(ex: CancellationException): ResponseEntity<Void> {
        logger.debug { "요청 취소됨" }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        if (ex is CancellationException) throw ex
        logger.error(ex) { "처리되지 않은 예외" }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(
                error = "서버 오류가 발생했습니다",
                timestamp = Instant.now().toString()
            )
        )
    }

    /** HTTP 상태 코드에 대응하는 사용자 친화적 한글 메시지. */
    private fun statusMessageFor(statusCode: Int): String = when (statusCode) {
        400 -> "잘못된 요청입니다"
        401 -> "인증이 필요합니다"
        403 -> "접근이 거부되었습니다"
        404 -> "요청한 리소스를 찾을 수 없습니다"
        409 -> "요청이 충돌합니다"
        429 -> "요청이 너무 많습니다"
        in 500..599 -> "서버 오류가 발생했습니다"
        else -> "요청을 처리할 수 없습니다"
    }
}

/** 표준화된 오류 응답 DTO. */
data class ErrorResponse(
    val error: String,
    val details: Map<String, String>? = null,
    val timestamp: String
)
