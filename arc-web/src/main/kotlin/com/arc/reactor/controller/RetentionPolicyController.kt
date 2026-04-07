package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditStore
import com.arc.reactor.audit.recordAdminAudit
import com.arc.reactor.settings.RuntimeSettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * 데이터 보존 정책 API.
 *
 * 세션/대화/감사 로그의 자동 삭제 기간을 관리한다.
 * RuntimeSettingsService를 통해 DB에 저장되어 재배포 없이 변경 가능.
 */
@Tag(name = "Retention Policy", description = "데이터 보존 정책 (ADMIN)")
@RestController
@RequestMapping("/api/admin/retention")
class RetentionPolicyController(
    private val settingsProvider: ObjectProvider<RuntimeSettingsService>,
    private val adminAuditStore: AdminAuditStore
) {

    /** 현재 보존 정책을 조회한다. */
    @Operation(summary = "보존 정책 조회")
    @GetMapping
    fun get(exchange: ServerWebExchange): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val settings = settingsProvider.ifAvailable
        return ResponseEntity.ok(
            RetentionPolicyResponse(
                sessionRetentionDays = settings?.getInt(KEY_SESSION, DEFAULT_SESSION_DAYS) ?: DEFAULT_SESSION_DAYS,
                conversationRetentionDays = settings?.getInt(KEY_CONVERSATION, DEFAULT_CONVERSATION_DAYS) ?: DEFAULT_CONVERSATION_DAYS,
                auditRetentionDays = settings?.getInt(KEY_AUDIT, DEFAULT_AUDIT_DAYS) ?: DEFAULT_AUDIT_DAYS,
                metricRetentionDays = settings?.getInt(KEY_METRIC, DEFAULT_METRIC_DAYS) ?: DEFAULT_METRIC_DAYS
            )
        )
    }

    /** 보존 정책을 변경한다. */
    @Operation(summary = "보존 정책 변경")
    @PutMapping
    fun update(
        @Valid @RequestBody request: UpdateRetentionRequest,
        exchange: ServerWebExchange
    ): ResponseEntity<Any> {
        if (!isAdmin(exchange)) return forbiddenResponse()
        val settings = settingsProvider.ifAvailable
            ?: return badRequestResponse("RuntimeSettingsService가 활성화되지 않았습니다")

        request.sessionRetentionDays?.let { settings.set(KEY_SESSION, it.toString()) }
        request.conversationRetentionDays?.let { settings.set(KEY_CONVERSATION, it.toString()) }
        request.auditRetentionDays?.let { settings.set(KEY_AUDIT, it.toString()) }
        request.metricRetentionDays?.let { settings.set(KEY_METRIC, it.toString()) }

        recordAdminAudit(
            store = adminAuditStore, category = "retention", action = "UPDATE",
            actor = currentActor(exchange), detail = request.toString()
        )
        return get(exchange)
    }

    companion object {
        private const val KEY_SESSION = "retention.session.days"
        private const val KEY_CONVERSATION = "retention.conversation.days"
        private const val KEY_AUDIT = "retention.audit.days"
        private const val KEY_METRIC = "retention.metric.days"
        private const val DEFAULT_SESSION_DAYS = 90
        private const val DEFAULT_CONVERSATION_DAYS = 365
        private const val DEFAULT_AUDIT_DAYS = 730
        private const val DEFAULT_METRIC_DAYS = 180
    }
}

data class RetentionPolicyResponse(
    val sessionRetentionDays: Int,
    val conversationRetentionDays: Int,
    val auditRetentionDays: Int,
    val metricRetentionDays: Int
)

data class UpdateRetentionRequest(
    @field:Min(1) val sessionRetentionDays: Int? = null,
    @field:Min(1) val conversationRetentionDays: Int? = null,
    @field:Min(1) val auditRetentionDays: Int? = null,
    @field:Min(1) val metricRetentionDays: Int? = null
)
