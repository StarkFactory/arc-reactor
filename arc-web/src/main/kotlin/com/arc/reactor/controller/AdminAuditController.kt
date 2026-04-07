package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.audit.recordAdminAudit
import com.arc.reactor.auth.AdminAuthorizationSupport.maskedAdminAccountRef
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 관리자 감사 로그 컨트롤러.
 *
 * 모든 관리자 작업(MCP 서버, 승인, 보안 정책 등)의 통합 감사 로그를 조회합니다.
 * 카테고리 및 액션으로 필터링하고 페이지네이션을 지원합니다.
 *
 * @see AdminAuditStore
 */
@Tag(name = "Admin Audits", description = "Unified admin audit logs (ADMIN)")
@RestController
@RequestMapping("/api/admin/audits")
class AdminAuditController(
    private val store: AdminAuditStore
) {

    /**
     * 관리자 감사 로그 목록을 조회한다.
     * WHY: actor 정보는 보안을 위해 마스킹하여 반환한다.
     */
    @Operation(summary = "관리자 감사 로그 목록 조회 (관리자)")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "Paginated list of admin audit logs"),
        ApiResponse(responseCode = "403", description = "Admin access required")
    ])
    @GetMapping
    fun list(
        @RequestParam(required = false) @Min(1) @Max(1000) limit: Int?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") pageLimit: Int,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val size = (limit ?: 1000).coerceIn(1, 1000)
        val clampedPageLimit = clampLimit(pageLimit)
        val rows = store.list(limit = size, category = category, action = action).map {
            AdminAuditResponse(
                id = it.id,
                category = it.category,
                action = it.action,
                actor = maskedAdminAccountRef(it.actor),
                resourceType = it.resourceType,
                resourceId = it.resourceId,
                detail = it.detail,
                createdAt = it.createdAt.toEpochMilli()
            )
        }
        return ResponseEntity.ok(rows.paginate(offset, clampedPageLimit))
    }

    /** 감사 로그를 CSV로 내보낸다 (감사팀/규제기관 제출용). */
    @Operation(summary = "감사 로그 CSV 내보내기 (관리자)")
    @GetMapping("/export")
    fun exportCsv(
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) action: String?,
        @RequestParam(defaultValue = "5000") @Min(1) @Max(50000) limit: Int?,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()

        val rows = store.list(limit = limit ?: 5000, category = category, action = action)
        val csv = buildCsvContent(rows)

        recordAdminAudit(
            store = store, category = "audit", action = "EXPORT",
            actor = currentActor(exchange), detail = "rows=${rows.size}"
        )

        val filename = "audit-export-${CSV_DATE_FORMAT.format(Instant.now())}.csv"
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(csv)
    }

    private fun buildCsvContent(rows: List<com.arc.reactor.audit.AdminAuditLog>): String {
        return buildString {
            append("id,timestamp,category,action,actor,resource_type,resource_id,detail\n")
            for (row in rows) {
                append(csvEscape(row.id)).append(',')
                append(CSV_TS_FORMAT.format(row.createdAt)).append(',')
                append(csvEscape(row.category)).append(',')
                append(csvEscape(row.action)).append(',')
                append(csvEscape(maskedAdminAccountRef(row.actor))).append(',')
                append(csvEscape(row.resourceType.orEmpty())).append(',')
                append(csvEscape(row.resourceId.orEmpty())).append(',')
                append(csvEscape(row.detail.orEmpty())).append('\n')
            }
        }
    }

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
        private val CSV_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(KST)
        private val CSV_TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(KST)

        /** CSV 필드 이스케이프: 콤마/줄바꿈/따옴표 포함 시 따옴표로 감싼다. */
        private fun csvEscape(value: String): String {
            return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
                "\"${value.replace("\"", "\"\"")}\""
            } else value
        }
    }
}

data class AdminAuditResponse(
    val id: String,
    val category: String,
    val action: String,
    val actor: String,
    val resourceType: String?,
    val resourceId: String?,
    val detail: String?,
    val createdAt: Long
)
