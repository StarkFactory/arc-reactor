package com.arc.reactor.admin.controller

import com.arc.reactor.audit.AdminAuditLog
import com.arc.reactor.audit.AdminAuditStore
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/** 관리자 감사 로그를 저장한다. 저장 실패 시 경고 로깅 후 무시한다 (fail-open). */
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
        logger.warn(e) {
            "Failed to persist admin audit log: category=$category action=$action resourceId=$resourceId"
        }
    }
}
