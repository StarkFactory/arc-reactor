package com.arc.reactor.admin.controller

import com.arc.reactor.diagnostics.DoctorDiagnostics
import com.arc.reactor.diagnostics.DoctorReport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import mu.KotlinLogging
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

private val logger = KotlinLogging.logger {}

/**
 * R237: R236 [DoctorDiagnostics] 서비스를 HTTP로 노출하는 REST 컨트롤러.
 *
 * 관리자가 배포 직후 한 번의 GET 요청으로 R225~R236에서 도입한 opt-in 기능들의 현재
 * 활성화 상태를 확인할 수 있다.
 *
 * ## 엔드포인트
 *
 * - `GET /api/admin/doctor` — 전체 진단 보고서
 * - `GET /api/admin/doctor/summary` — 한 줄 요약만
 *
 * ## 인가
 *
 * 모든 엔드포인트는 관리자(`ADMIN` 또는 `ADMIN_DEVELOPER`) 전용이다. `isAnyAdmin()`
 * 검사를 통과하지 못하면 `403 Forbidden`을 반환한다. `DoctorDiagnostics` 자체는 read-only
 * 지만, 시스템 구성 정보 노출을 막기 위해 admin 권한이 필요하다.
 *
 * ## 활성화 조건
 *
 * - `arc-core`의 [DoctorDiagnostics] 빈이 등록되어 있어야 함 (R236 `DoctorDiagnosticsConfiguration`이
 *   기본 등록)
 * - arc-admin 모듈이 로드되어야 함 (arc-admin의 auto-config는 전통적으로 `arc.reactor.admin.enabled`
 *   플래그로 제어되지만, 이 컨트롤러는 의존성만 확인하면 충분하므로 `@ConditionalOnBean`으로 연결)
 *
 * ## 사용 예
 *
 * ```bash
 * curl -H "Authorization: Bearer <admin-token>" http://localhost:8080/api/admin/doctor
 * ```
 *
 * 응답 예시:
 * ```json
 * {
 *   "generatedAt": "2026-04-11T11:30:00Z",
 *   "sections": [
 *     {
 *       "name": "Approval Context Resolver",
 *       "status": "OK",
 *       "checks": [
 *         {"name": "resolver bean", "status": "OK", "detail": "등록됨: RedactedApprovalContextResolver"},
 *         {"name": "PII 마스킹 (R228)", "status": "OK", "detail": "활성 — 감사 로그에 이메일/토큰 노출 없음"},
 *         {"name": "sample resolve", "status": "OK", "detail": "정상 응답 — reversibility=REVERSIBLE"}
 *       ],
 *       "message": "활성 (RedactedApprovalContextResolver)"
 *     },
 *     ...
 *   ]
 * }
 * ```
 *
 * @see DoctorDiagnostics R236 진단 서비스
 * @see com.arc.reactor.diagnostics.DoctorReport 진단 결과 모델
 */
@Tag(name = "Admin Doctor", description = "Opt-in 기능 활성화 상태 진단 API (ADMIN)")
@RestController
@RequestMapping("/api/admin/doctor")
@ConditionalOnBean(DoctorDiagnostics::class)
class DoctorController(
    private val doctor: DoctorDiagnostics
) {

    /**
     * 전체 진단 보고서를 반환한다.
     *
     * 응답 상태 코드 정책:
     * - 200 OK: 모든 섹션이 OK 또는 SKIPPED (`allHealthy() == true`)
     * - 200 OK + `X-Doctor-Status: WARN`: WARN이 존재하나 ERROR는 없음
     * - 500 Internal Server Error + body: ERROR 섹션이 하나라도 있음
     *
     * 클라이언트는 HTTP 상태만 보고 판단할 수도 있고, body의 `sections[].status`를
     * 순회하여 상세 상태를 얻을 수도 있다.
     */
    @Operation(
        summary = "opt-in 기능 진단 보고서",
        description = "R225~R236에서 도입한 Approval/Summarizer/Evaluation/PromptLayer 기능의 " +
            "현재 활성화 상태를 진단한다."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "정상 또는 경고 포함"),
        ApiResponse(responseCode = "403", description = "관리자 권한 필요"),
        ApiResponse(responseCode = "500", description = "진단 섹션에 ERROR 발생")
    )
    @GetMapping
    fun report(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val report = runDiagnosticsSafely()
        return when {
            report.hasErrors() -> ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header(STATUS_HEADER, STATUS_ERROR)
                .body(report)
            report.hasWarningsOrErrors() -> ResponseEntity
                .status(HttpStatus.OK)
                .header(STATUS_HEADER, STATUS_WARN)
                .body(report)
            else -> ResponseEntity
                .status(HttpStatus.OK)
                .header(STATUS_HEADER, STATUS_OK)
                .body(report)
        }
    }

    /**
     * 진단 한 줄 요약만 반환한다.
     *
     * 모니터링 대시보드나 CLI 프로브처럼 상세 정보가 불필요한 경우에 사용.
     *
     * 예시 응답:
     * ```json
     * {"summary": "4 섹션 — OK 3, WARN 1", "status": "WARN", "generatedAt": "2026-04-11T11:30:00Z"}
     * ```
     */
    @Operation(
        summary = "진단 한 줄 요약",
        description = "상세 섹션 없이 overall status와 summary 문자열만 반환한다."
    )
    @GetMapping("/summary")
    fun summary(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAnyAdmin(exchange)) return forbiddenResponse()
        val report = runDiagnosticsSafely()
        val overall = when {
            report.hasErrors() -> STATUS_ERROR
            report.hasWarningsOrErrors() -> STATUS_WARN
            else -> STATUS_OK
        }
        val body = mapOf(
            "summary" to report.summary(),
            "status" to overall,
            "generatedAt" to report.generatedAt.toString(),
            "allHealthy" to report.allHealthy()
        )
        val httpStatus = if (report.hasErrors()) HttpStatus.INTERNAL_SERVER_ERROR else HttpStatus.OK
        return ResponseEntity
            .status(httpStatus)
            .header(STATUS_HEADER, overall)
            .body(body)
    }

    /**
     * 진단 실행을 안전하게 래핑한다. `DoctorDiagnostics` 자체가 이미 fail-safe 설계이지만,
     * 혹시라도 예외가 새어 나오면 여기서 catch하여 ERROR 보고서를 합성한다.
     */
    private fun runDiagnosticsSafely(): DoctorReport {
        return try {
            doctor.runDiagnostics()
        } catch (e: Exception) {
            logger.error(e) { "DoctorDiagnostics.runDiagnostics() 예외" }
            DoctorReport(
                generatedAt = java.time.Instant.now(),
                sections = listOf(
                    com.arc.reactor.diagnostics.DoctorSection(
                        name = "Doctor Diagnostics",
                        status = com.arc.reactor.diagnostics.DoctorStatus.ERROR,
                        checks = listOf(
                            com.arc.reactor.diagnostics.DoctorCheck(
                                name = "runDiagnostics",
                                status = com.arc.reactor.diagnostics.DoctorStatus.ERROR,
                                detail = "예외: ${e.javaClass.simpleName}: " +
                                    (e.message ?: "메시지 없음")
                            )
                        ),
                        message = "진단 실행 실패"
                    )
                )
            )
        }
    }

    companion object {
        /** 응답 헤더 — 클라이언트가 body 파싱 없이 overall 상태를 확인할 수 있도록 */
        const val STATUS_HEADER: String = "X-Doctor-Status"
        const val STATUS_OK: String = "OK"
        const val STATUS_WARN: String = "WARN"
        const val STATUS_ERROR: String = "ERROR"
    }
}
