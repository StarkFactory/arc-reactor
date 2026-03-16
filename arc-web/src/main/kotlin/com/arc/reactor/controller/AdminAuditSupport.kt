package com.arc.reactor.controller

import com.arc.reactor.audit.AdminAuditLog
import com.arc.reactor.audit.AdminAuditStore
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * 관리자 감사 로그를 안전하게 저장한다.
 * WHY: 감사 로그 저장 실패가 주 요청을 중단시키지 않도록 runCatching으로 감싼다.
 * 감사 로그는 fail-open (저장 실패 시 경고 로그만 남기고 계속 진행).
 */
fun recordAdminAudit(
    store: AdminAuditStore,
    category: String,
    action: String,
    actor: String,
    resourceType: String? = null,
    resourceId: String? = null,
    detail: String? = null
) {
    runCatching {
        store.save(
            AdminAuditLog(
                category = category,
                action = action,
                actor = actor,
                resourceType = resourceType,
                resourceId = resourceId,
                detail = detail
            )
        )
    }.onFailure { e ->
        logger.warn(e) { "Failed to persist admin audit log: category=$category action=$action resourceId=$resourceId" }
    }
}
