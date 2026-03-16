package com.arc.reactor.errorreport.controller

import com.arc.reactor.errorreport.config.ErrorReportProperties
import com.arc.reactor.errorreport.handler.ErrorReportHandler
import com.arc.reactor.errorreport.model.ErrorReportRequest
import com.arc.reactor.errorreport.model.ErrorReportResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * 프로덕션 서버로부터 오류 리포트를 수신하여 AI 자율 분석을 트리거하는 컨트롤러.
 *
 * 처리는 비동기로 수행된다 -- 엔드포인트는 즉시 200을 반환한다.
 * AI 에이전트가 등록된 도구(MCP 및/또는 로컬 도구)를 사용하여 오류를 분석하고
 * 결과 리포트를 Slack으로 전송한다.
 *
 * 동시성 제어: [Semaphore]로 최대 동시 처리 수를 제한한다.
 * 스택 트레이스 보호: 설정된 최대 길이를 초과하면 잘라서 처리한다.
 * API 키 인증: 설정된 경우 상수 시간 비교로 검증한다 (타이밍 공격 방지).
 *
 * @see ErrorReportHandler 오류 리포트 처리 인터페이스
 * @see ErrorReportProperties 설정 프로퍼티
 */
@RestController
@RequestMapping("/api/error-report")
@ConditionalOnProperty(prefix = "arc.reactor.error-report", name = ["enabled"], havingValue = "true")
@Tag(name = "Error Report", description = "Production error report ingestion")
class ErrorReportController(
    private val handler: ErrorReportHandler,
    private val properties: ErrorReportProperties
) : org.springframework.beans.factory.DisposableBean {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val effectiveMaxConcurrentRequests = properties.maxConcurrentRequests.coerceAtLeast(1)
    private val effectiveMaxStackTraceLength = properties.maxStackTraceLength.coerceAtLeast(1)
    private val semaphore = Semaphore(effectiveMaxConcurrentRequests)

    init {
        if (properties.maxConcurrentRequests < 1) {
            logger.warn {
                "Invalid maxConcurrentRequests=${properties.maxConcurrentRequests}; " +
                    "using $effectiveMaxConcurrentRequests"
            }
        }
        if (properties.maxStackTraceLength < 1) {
            logger.warn {
                "Invalid maxStackTraceLength=${properties.maxStackTraceLength}; " +
                    "using $effectiveMaxStackTraceLength"
            }
        }
    }

    /** 프로덕션 오류 리포트를 접수하고 비동기 AI 분석을 시작한다. */
    @PostMapping
    @Operation(summary = "Submit a production error report for AI-powered analysis")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Error report accepted for processing"),
        ApiResponse(responseCode = "400", description = "Invalid request"),
        ApiResponse(responseCode = "401", description = "Invalid or missing API key")
    ])
    suspend fun report(
        @Valid @RequestBody request: ErrorReportRequest,
        @RequestHeader(value = "X-API-Key", required = false) apiKey: String?
    ): ResponseEntity<Any> {
        if (!validateApiKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(
                    ErrorReportErrorResponse(
                        error = "Invalid or missing API key",
                        timestamp = Instant.now().toString()
                    )
                )
        }

        val requestId = UUID.randomUUID().toString()

        // 스택 트레이스가 최대 길이를 초과하면 잘라서 처리한다
        val truncatedRequest = if (request.stackTrace.length > effectiveMaxStackTraceLength) {
            request.copy(stackTrace = request.stackTrace.take(effectiveMaxStackTraceLength) + "\n... [truncated]")
        } else {
            request
        }

        processAsync(requestId, truncatedRequest)

        return ResponseEntity.ok(ErrorReportResponse(accepted = true, requestId = requestId))
    }

    /** 코루틴으로 비동기 처리를 시작한다. 세마포어로 동시성을 제한한다. */
    private fun processAsync(requestId: String, request: ErrorReportRequest) {
        scope.launch {
            semaphore.withPermit {
                try {
                    withTimeout(properties.requestTimeoutMs.coerceAtLeast(1)) {
                        handler.handle(requestId, request)
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.error(e) {
                        "Error report processing timed out requestId=$requestId timeoutMs=${properties.requestTimeoutMs}"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Unhandled error in error report processing requestId=$requestId" }
                }
            }
        }
    }

    override fun destroy() {
        scope.cancel()
    }

    /** API 키를 상수 시간 비교로 검증한다. 키가 미설정이면 인증을 건너뛴다. */
    private fun validateApiKey(headerKey: String?): Boolean {
        if (properties.apiKey.isBlank()) return true
        if (headerKey == null) return false
        return java.security.MessageDigest.isEqual(
            headerKey.toByteArray(Charsets.UTF_8),
            properties.apiKey.toByteArray(Charsets.UTF_8)
        )
    }
}

/** 오류 리포트 API 에러 응답 DTO. */
private data class ErrorReportErrorResponse(
    val error: String,
    val timestamp: String
)
