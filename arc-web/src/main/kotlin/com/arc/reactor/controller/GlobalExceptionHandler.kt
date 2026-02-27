package com.arc.reactor.controller

import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.reactive.resource.NoResourceFoundException
import org.springframework.web.server.ServerWebInputException
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Global exception handler for standardized error responses.
 *
 * Provides consistent error format across all controllers:
 * - Validation errors (400) with field-level detail
 * - Bad request errors (400) for malformed input
 * - Generic errors (500) with masked messages (no stack traces)
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

    @ExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error(ex) { "Unhandled exception" }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse(
                error = "Internal server error",
                timestamp = Instant.now().toString()
            )
        )
    }
}

/**
 * Standardized error response DTO.
 */
data class ErrorResponse(
    val error: String,
    val details: Map<String, String>? = null,
    val timestamp: String
)
